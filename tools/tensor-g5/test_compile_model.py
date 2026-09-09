"""Parser fixtures only: these are not executable compiled models or app assets."""
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

import flatbuffers
from flatbuffers import flexbuffers
from ai_edge_litert import schema_py_generated as schema

from compile_model import EXPECTED_SHAPE, inspect_model, write_manifest
from prepare import extract_sdk, sha256


def fixture(*, dispatch=True, stamp=True, soc="Tensor_G5", size=4, offset=4096,
            dtype=schema.TensorType.FLOAT32, input_name="args_0"):
    model = schema.ModelT()
    model.version = 3
    graph = schema.SubGraphT()
    graph.name = "test_fixture"
    graph.inputs, graph.outputs = [0], [1]
    graph.tensors = []
    for name in ("test_input", "test_output"):
        tensor = schema.TensorT()
        tensor.name, tensor.shape, tensor.type = name, EXPECTED_SHAPE, dtype
        graph.tensors.append(tensor)
    code = schema.OperatorCodeT()
    code.builtinCode = schema.BuiltinOperator.CUSTOM
    code.deprecatedBuiltinCode = schema.BuiltinOperator.CUSTOM
    code.customCode = "DISPATCH_OP"
    model.operatorCodes = [code]
    graph.operators = []
    if dispatch:
        op = schema.OperatorT()
        op.opcodeIndex, op.inputs, op.outputs = 0, [0], [1]
        op.customOptions = list(flexbuffers.Dumps({"bytecode_offset": offset, "bytecode_size": size, "name": "test_only"}))
        graph.operators = [op]
    model.subgraphs = [graph]
    model.buffers = [schema.BufferT()]
    model.metadata = []
    if stamp:
        buffer = schema.BufferT()
        buffer.data = list(b"Google".ljust(125, b"\0") + soc.encode().ljust(125, b"\0"))
        model.buffers.append(buffer)
        metadata = schema.MetadataT()
        metadata.name, metadata.buffer = "LiteRtStamp", 1
        model.metadata = [metadata]
    signature = schema.SignatureDefT()
    signature.signatureKey = "serving_default"
    signature.inputs, signature.outputs = [], []
    for name, index, target in ((input_name, 0, signature.inputs), ("output_0", 1, signature.outputs)):
        entry = schema.TensorMapT()
        entry.name, entry.tensorIndex = name, index
        target.append(entry)
    model.signatureDefs = [signature]
    builder = flatbuffers.Builder(4096)
    builder.Finish(model.Pack(builder), file_identifier=b"TFL3")
    raw = bytes(builder.Output())
    assert len(raw) < 4096
    return raw.ljust(4096, b"\0") + b"\x01\x02\x03\x04"


class CompileValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)

    def inspect(self, **kwargs):
        path = self.directory / "fixture.tflite"
        path.write_bytes(fixture(**kwargs))
        return inspect_model(path, require_compiled=True)

    def test_counts_executed_dispatch_with_real_bytecode_reference(self):
        result = self.inspect()
        self.assertEqual(1, result["dispatchOpCount"])
        self.assertEqual(1, result["partitionCount"])
        self.assertEqual(12_582_912, result["input"]["byteSize"])
        self.assertEqual("args_0", result["input"]["name"])

    def test_unused_dispatch_opcode_does_not_claim_acceleration(self):
        with self.assertRaisesRegex(ValueError, "no executable"):
            self.inspect(dispatch=False)

    def test_missing_stamp_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "LiteRtStamp"):
            self.inspect(stamp=False)

    def test_wrong_chip_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Tensor_G5"):
            self.inspect(soc="Tensor_G6")

    def test_empty_and_out_of_bounds_bytecode_are_rejected(self):
        for options in ({"size": 0}, {"size": 99}, {"offset": 8000}):
            with self.subTest(options=options), self.assertRaisesRegex(ValueError, "bytecode"):
                self.inspect(**options)

    def test_wrong_dtype_and_signature_are_rejected(self):
        for options in ({"dtype": schema.TensorType.FLOAT16}, {"input_name": "wrong_input"}):
            with self.subTest(options=options), self.assertRaises(ValueError):
                self.inspect(**options)

    def test_manifest_binds_original_and_compiled_bytes(self):
        source, compiled, manifest = [self.directory / name for name in ("source.tflite", "compiled.tflite", "result.json")]
        source.write_bytes(fixture(dispatch=False, stamp=False))
        compiled.write_bytes(fixture())
        result = write_manifest(source, compiled, manifest, {}, "float32")
        self.assertEqual(sha256(source), result["sourceSha256"])
        self.assertEqual(sha256(compiled), result["compiledSha256"])
        self.assertEqual("Google", json.loads(manifest.read_text())["manufacturer"])

    def test_uncompiled_original_cannot_generate_manifest(self):
        source = self.directory / "source.tflite"
        destination = self.directory / "not_created.json"
        source.write_bytes(fixture(dispatch=False, stamp=False))
        with self.assertRaises(ValueError):
            write_manifest(source, source, destination, {}, "float32")
        self.assertFalse(destination.exists())

    def test_sdk_archive_traversal_rejected_before_any_file_written(self):
        archive = self.directory / "unsafe.tar.gz"
        with tarfile.open(archive, "w:gz") as tar:
            safe = tarfile.TarInfo("safe.txt")
            safe.size = 1
            tar.addfile(safe, io.BytesIO(b"a"))
            escape = tarfile.TarInfo("../outside.txt")
            escape.size = 1
            tar.addfile(escape, io.BytesIO(b"b"))
        destination = self.directory / "sdk"
        with self.assertRaises(ValueError):
            extract_sdk(archive, destination)
        self.assertFalse((destination / "safe.txt").exists())
        self.assertFalse((self.directory / "outside.txt").exists())


if __name__ == "__main__":
    unittest.main()
