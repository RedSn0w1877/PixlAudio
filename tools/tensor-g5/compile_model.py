#!/usr/bin/env python3
"""AOT compile the stem model for Tensor G5 and verify the exported FlatBuffer."""
import argparse
from datetime import datetime, timezone
import importlib.metadata
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

from prepare import ROOT, VERSION, COMPILER, compiler_directory, sha256

REPO = ROOT.parent.parent
EXPECTED_SHAPE = [1, 4, 3072, 256]
DISPATCH_CODE = "DISPATCH_OP"


def require_dependencies():
    try:
        installed = importlib.metadata.version("ai-edge-litert")
    except importlib.metadata.PackageNotFoundError as error:
        raise RuntimeError("Run prepare.py inside Linux/WSL; ai-edge-litert==2.2.0 is required") from error
    if installed != VERSION:
        raise RuntimeError(f"Expected ai-edge-litert=={VERSION}, found {installed}")
    try:
        importlib.metadata.version("ai-edge-litert-nightly")
    except importlib.metadata.PackageNotFoundError:
        return
    raise RuntimeError("Stable and nightly LiteRT share a Python namespace. Use a clean stable-only venv.")


def _bytes(vector, size):
    return bytes(vector) if size else b""


def _buffer_bytes(model, index, raw):
    if index < 0 or index >= model.BuffersLength():
        raise ValueError("Metadata references a missing buffer")
    buffer = model.Buffers(index)
    if buffer.DataLength():
        return bytes(buffer.DataAsNumpy())
    offset, size = int(buffer.Offset()), int(buffer.Size())
    if offset <= 0 or size <= 0 or offset + size > len(raw):
        raise ValueError("Metadata buffer is missing or truncated")
    return raw[offset:offset + size]


def inspect_model(path, *, require_compiled):
    from ai_edge_litert import schema_py_generated as schema
    from flatbuffers import flexbuffers

    raw = Path(path).read_bytes()
    if len(raw) < 8 or raw[4:8] != b"TFL3":
        raise ValueError("Model is not a TFLite FlatBuffer")
    model = schema.Model.GetRootAsModel(raw, 0)
    if model.SubgraphsLength() != 1:
        raise ValueError("Stem contract currently supports exactly one inference subgraph")
    graph = model.Subgraphs(0)
    if graph.InputsLength() != 1 or graph.OutputsLength() != 1:
        raise ValueError("Stem contract requires one input and one output")
    if model.SignatureDefsLength() != 1:
        raise ValueError("Stem contract requires one named inference signature")
    signature = model.SignatureDefs(0)
    if signature.SubgraphIndex() != 0 or signature.SignatureKey() != b"serving_default":
        raise ValueError("Expected serving_default signature on the inference subgraph")
    if signature.InputsLength() != 1 or signature.OutputsLength() != 1:
        raise ValueError("Signature must map one input and one output")
    if (signature.Inputs(0).Name() != b"args_0" or signature.Outputs(0).Name() != b"output_0"
            or signature.Inputs(0).TensorIndex() != graph.Inputs(0)
            or signature.Outputs(0).TensorIndex() != graph.Outputs(0)):
        raise ValueError("Signature names or tensor bindings differ from the stem model contract")

    def tensor_info(index):
        tensor = graph.Tensors(index)
        shape = [int(tensor.Shape(i)) for i in range(tensor.ShapeLength())]
        signature = [int(tensor.ShapeSignature(i)) for i in range(tensor.ShapeSignatureLength())]
        if tensor.Type() != schema.TensorType.FLOAT32 or shape != EXPECTED_SHAPE:
            raise ValueError(f"Stem tensor must be float32 {EXPECTED_SHAPE}; got type={tensor.Type()}, shape={shape}")
        if signature and signature != shape:
            raise ValueError("Dynamic input/output shapes are not supported")
        return {"dtype": "float32", "shape": shape, "byteSize": math.prod(shape) * 4}

    result = {"signatureKey": "serving_default", "input": {"name": "args_0", **tensor_info(graph.Inputs(0))},
              "output": {"name": "output_0", **tensor_info(graph.Outputs(0))}}
    dispatches, custom_codes = [], set()
    for index in range(graph.OperatorsLength()):
        op = graph.Operators(index)
        if op.OpcodeIndex() >= model.OperatorCodesLength():
            raise ValueError("Operator references a missing opcode")
        code = model.OperatorCodes(op.OpcodeIndex())
        if max(code.BuiltinCode(), code.DeprecatedBuiltinCode()) != schema.BuiltinOperator.CUSTOM:
            continue
        custom = (code.CustomCode() or b"").decode("utf-8", errors="strict")
        custom_codes.add(custom)
        if custom != DISPATCH_CODE:
            continue
        options = flexbuffers.Loads(_bytes(op.CustomOptionsAsNumpy(), op.CustomOptionsLength()))
        if not isinstance(options, dict):
            raise ValueError("Dispatch custom options must be a FlexBuffers map")
        offset, size = options.get("bytecode_offset"), options.get("bytecode_size")
        if type(offset) is not int or type(size) is not int or offset <= 8 or size <= 0 or offset + size > len(raw):
            raise ValueError("Dispatch bytecode is missing, empty, or outside the model file")
        if not any(raw[offset:offset + size]):
            raise ValueError("Dispatch bytecode is entirely zero")
        name = options.get("name")
        if not isinstance(name, str) or not name:
            raise ValueError("Dispatch entry point name is missing")
        dispatches.append({"operatorIndex": index, "bytecodeOffset": offset, "bytecodeSize": size, "entryPoint": name})

    if require_compiled:
        if not dispatches:
            raise ValueError("Model has no executable DISPATCH_OP operations; a fallback model is not NPU compiled")
        stamps = []
        for i in range(model.MetadataLength()):
            metadata = model.Metadata(i)
            if metadata.Name() == b"LiteRtStamp":
                stamp = _buffer_bytes(model, metadata.Buffer(), raw)
                if len(stamp) != 250:
                    raise ValueError("Unsupported LiteRT build stamp format")
                stamps.append((stamp[:125].split(b"\0", 1)[0], stamp[125:].split(b"\0", 1)[0]))
        if stamps != [(b"Google", b"Tensor_G5")]:
            raise ValueError(f"Compiled model must contain one Google/Tensor_G5 LiteRtStamp, got {stamps}")

    result.update(dispatchOpCount=len(dispatches), partitionCount=len(dispatches),
                  customOpCodes=sorted(custom_codes), dispatchPartitions=dispatches)
    return result


def write_manifest(source, compiled, destination, settings, precision):
    inspect_model(source, require_compiled=False)
    inspection = inspect_model(compiled, require_compiled=True)
    source_hash, compiled_hash = sha256(source), sha256(compiled)
    if source_hash == compiled_hash:
        raise ValueError("Compiler output is identical to the original model")
    manifest = {"schemaVersion": 1, "manufacturer": "Google", "target": "Tensor_G5", "sourceSha256": source_hash,
                "compiledSha256": compiled_hash, "litertVersion": VERSION,
                "compilerVersion": settings.get("compilerVersion"),
                "sdkArchiveSha256": settings.get("sdkArchiveSha256"),
                "compilerLibrarySha256": settings.get("compilerLibrarySha256"),
                "precision": precision, "compiledFile": "stem_separation_tensor_g5.tflite",
                "createdAtUtc": datetime.now(timezone.utc).isoformat(), **inspection}
    Path(destination).write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, default=REPO / "app/src/main/assets/tais/stem_separation.tflite")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "output")
    parser.add_argument("--precision", choices=["float32", "float16", "bfloat16"], default="float32")
    args = parser.parse_args()
    settings_file = ROOT / "environment.json"
    if not settings_file.is_file():
        raise RuntimeError("Vendor compiler is not prepared. Run prepare.py --sdk /path/to/Google-SDK.tar.gz. "
                           "The official public compiler URL returned HTTP 404 on 2026-09-07.")
    settings = json.loads(settings_file.read_text())
    sdk_dir = compiler_directory(settings["sdkDir"])
    if sha256(sdk_dir / COMPILER) != settings["compilerLibrarySha256"]:
        raise RuntimeError("Compiler library changed after preparation; run prepare.py again")
    require_dependencies()
    # The native adapter uses dlopen, whose search path is initialized at process startup.
    if os.environ.get("TENSOR_G5_COMPILER_WORKER") != "1":
        env = os.environ.copy()
        env.update(GOOGLE_TENSOR_BACKEND_ENABLED="1", GOOGLE_TENSOR_COMPILER_LIB=str(sdk_dir),
                   LD_LIBRARY_PATH=str(sdk_dir) + os.pathsep + env.get("LD_LIBRARY_PATH", ""),
                   TENSOR_G5_COMPILER_WORKER="1")
        subprocess.run([sys.executable, str(Path(__file__).resolve()), *sys.argv[1:]], env=env, check=True)
        return

    from ai_edge_litert.aot import aot_compile as aot
    from ai_edge_litert.aot.vendors.google_tensor import target as gt
    inspect_model(args.model, require_compiled=False)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    model_name = "stem_separation_tensor_g5"
    output_model, output_manifest = args.output_dir / f"{model_name}.tflite", args.output_dir / f"{model_name}.json"
    if output_model.exists() or output_manifest.exists():
        raise ValueError("Output already exists; choose a new --output-dir to preserve earlier artifacts")
    with tempfile.TemporaryDirectory(prefix="compile-", dir=ROOT) as temporary:
        result = aot.aot_compile(str(args.model.resolve()), output_dir=temporary,
                                target=[gt.Target(gt.SocModel.TENSOR_G5)], keep_going=False,
                                google_tensor_truncation_type={"float32": "no_truncation", "float16": "half",
                                                               "bfloat16": "bfloat16"}[args.precision])
        report = str(result.compilation_report())
        print(report)
        result.export(temporary, model_name="stem_separation")
        candidates = list(Path(temporary).glob("*Google_Tensor_G5.tflite"))
        if len(candidates) != 1:
            raise ValueError(f"Expected one exported Google_Tensor_G5 model; found {len(candidates)}")
        staged_manifest = Path(temporary) / "manifest.json"
        manifest = write_manifest(args.model, candidates[0], staged_manifest, settings, args.precision)
        shutil.copyfile(candidates[0], output_model)
        # Manifest appears last; runtime cannot select a partially published pair.
        shutil.copyfile(staged_manifest, output_manifest)
        (args.output_dir / "compilation-report.txt").write_text(report + "\n")
    print(f"Verified {manifest['dispatchOpCount']} dispatch partitions: {output_model}")
    print("NPU output parity and phone performance still require validation; this script does not install or run the app.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Compilation failed: {error}", file=sys.stderr)
        sys.exit(1)
