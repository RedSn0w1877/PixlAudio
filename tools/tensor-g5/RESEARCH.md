# Google Tensor G5 compiler availability — 2026-09-07

The compiler has not been installed and the stem model has not been AOT compiled.
No phone tests were performed by this task.

## Verified blocker

The [current official SDK installer](https://github.com/google-ai-edge/LiteRT/blob/main/ci/tools/python/vendor_sdk/google_tensor/setup.py) and the published
`ai-edge-litert-sdk-google-tensor-nightly==2.3.0.dev20260906` source package use this public URL:

https://redirector.gvt1.com/edgedl/tensor-ml-sdk/sdk/releases/6.1.0/litert_plugin_compiler.tar.gz

On 2026-09-07, HTTP HEAD and ranged GET from Windows and a separate urllib GET
from WSL all ended in HTTP 404 after the official CDN redirects to dl.google.com.
The public URL was added by [commit a154a4f](https://github.com/google-ai-edge/LiteRT/commit/a154a4f371e6309196524dc3d29f29b3286dfb0f)
on 2026-08-07. It is an intended public distribution route, but it does not
currently provide a downloadable compiler from this workstation.

The stable 2.2.0 installer still requires a local beta archive. The
[official codelab](https://codelabs.developers.google.com/codelabs/google-tensor-ml-sdk)
instructs developers to [request beta access](https://services.google.com/fb/forms/tensor_ml_sdk_experimental_access/),
wait for Google's emailed download link, and supply `litert_plugin_compiler.tar.gz`.
A [Google maintainer's September 2 response](https://github.com/google-ai-edge/LiteRT/issues/8943#issuecomment-5507280735)
references compiler `v1.1.0_2026_07_02`. That archive is separate from the LiteRT
package version; its compatibility cannot be inferred from matching version numbers.

## Public artifacts checked

- [LiteRT 2.2.0 release](https://github.com/google-ai-edge/LiteRT/releases/tag/v2.2.0), published 2026-08-13.
- [AOT runtime archive](https://github.com/google-ai-edge/LiteRT/releases/download/v2.2.0/litert_npu_runtime_libraries.zip), downloaded here as `litert_npu_runtime_libraries-2.2.0.zip`.
  Contains `google_tensor_runtime/src/main/jni/arm64-v8a/libLiteRtDispatch_GoogleTensor.so`.
  It contains no Linux Tensor compiler.
  SHA256: `b4c8380df3e9652677dbb93a5aad4499eb756a9b7d9651a9baacb122faadbf0d`.
- [SDK nightly source package](https://pypi.org/project/ai-edge-litert-sdk-google-tensor-nightly/2.3.0.dev20260906/), downloaded here as `google-tensor-sdk-nightly-source.tar.gz`.
  Its 13,526-byte archive contains installer code, not the compiler.
  SHA256: `beb300089ac85e052d7f4943cb64c0c2c8c47e2e3d5e8b578155da9a7140a511`.

The public `libLiteRtCompilerPlugin_google_tensor.so` is the LiteRT adapter. Its
[AOT implementation](https://github.com/google-ai-edge/LiteRT/blob/v2.2.0/litert/vendors/google_tensor/adapter_aot.cc)
loads the separate `liblitert_plugin_compiler.so` and calls
`GoogleTensorCompileFlatbuffer`. Building or downloading the adapter does not
replace the missing vendor compiler.

## Environment and next command sequence

This workstation has WSL Ubuntu, Linux x86_64, Python 3.14.4. An isolated `.venv`
now contains `ai-edge-litert==2.2.0`; its official schema/AOT imports work. No
system packages or vendor compiler were installed. Google's documented
development environment is Ubuntu 22.04 x86_64 with at least 16 GB RAM; Python
3.11 is the documented optional conversion version.

After obtaining Google's compiler archive, create an isolated environment and
use a pinned LiteRT/SDK wrapper pair. This is the stable 2.2.0 route to attempt;
the actual compiler/archive and Android driver compatibility must still be verified:

```bash
python3 -m venv tools/tensor-g5/.venv
tools/tensor-g5/.venv/bin/python -m pip install ai-edge-litert==2.2.0
export GOOGLE_TENSOR_SDK_BETA=/absolute/path/to/litert_plugin_compiler.tar.gz
tools/tensor-g5/.venv/bin/python -m pip install ai-edge-litert-sdk-google-tensor==2.2.0
```

The corresponding compile API for an existing TFLite model is:

```python
import os
os.environ["GOOGLE_TENSOR_BACKEND_ENABLED"] = "1"
from ai_edge_litert.aot import aot_compile as aot
from ai_edge_litert.aot.vendors.google_tensor import target as gt

result = aot.aot_compile(
    "app/src/main/assets/tais/stem_separation.tflite",
    output_dir="tools/tensor-g5/output",
    target=[gt.Target(gt.SocModel.TENSOR_G5)],
    keep_going=False,
)
print(result.compilation_report())
result.export("tools/tensor-g5/output", model_name="stem_separation")
```

Use an explicit G5 target and fail on errors. Inspect partition coverage and
validate outputs before treating an exported model as accelerated. The model
path above is the current asset, not a promise that all of its operations compile.

If Google repairs the public 6.1.0 URL, the published nightly SDK wrapper can
download it automatically. Pin both `ai-edge-litert-nightly` and
`ai-edge-litert-sdk-google-tensor-nightly` to `2.3.0.dev20260906` for reproducing
the installer inspected here; do not install stable and nightly LiteRT into the
same environment. A matching validated Android dispatch/runtime is still required.

[Google's NPU documentation](https://developers.google.com/edge/litert/next/npu)
specifies AOT-only support for Google Tensor: enabling an NPU option on an
ordinary uncompiled model cannot perform Google Tensor JIT compilation.

## Implemented local workflow

Run from the repository root inside WSL (Python 3.11 or later):

```bash
python3 tools/tensor-g5/prepare.py --sdk /absolute/path/to/litert_plugin_compiler.tar.gz \
  --sdk-version v1.1.0_2026_07_02
tools/tensor-g5/.venv/bin/python tools/tensor-g5/compile_model.py
```

`--sdk` also accepts an extracted SDK directory or the vendor compiler `.so`.
`prepare.py` safely extracts an archive under its SHA256 in this tools directory,
checks the Linux x86_64 ELF header, installs only into `.venv`, and records the
vendor library hash. It uses the official backend's `GOOGLE_TENSOR_COMPILER_LIB`
directory override, avoiding a second SDK-package copy. It does not infer a
compiler version or modify system packages. To retry the official public URL:

```bash
python3 tools/tensor-g5/prepare.py --download-public
```

The default compile mode requests `no_truncation` (`precision=float32`). Optional
`--precision float16` or `--precision bfloat16` deliberately changes arithmetic
precision; output parity must be assessed before use. All modes require unchanged
FLOAT32 input/output `[1,4,3072,256]`, signature `serving_default`, and signature
tensor names `args_0`/`output_0`.

Compilation produces `output/stem_separation_tensor_g5.tflite` and its JSON
manifest only after verifying actual CUSTOM `DISPATCH_OP` instances, nonempty
in-bounds bytecode ranges from their FlexBuffers options, and a 250-byte
`LiteRtStamp` containing manufacturer `Google` and model `Tensor_G5`. Merely
declaring an unused dispatch opcode is rejected. Partition count is the number
of dispatched operator instances in the single inference subgraph. The manifest
records original/compiled SHA256, compiler hash, LiteRT version, tensor contract,
target and precision. Existing output files are preserved; choose a fresh
`--output-dir` for another build. It never writes an app asset or installs the APK.

Validation completed on computer: all 9 parser/archive regression tests passed;
the actual source asset parsed as 0 dispatch operations and was rejected as an
NPU artifact. The compile entry point failed clearly when the vendor SDK was
absent and did not create model/manifest outputs. Test fixtures are synthetic
FlatBuffers in temporary directories and are never used as app models.

```bash
cd tools/tensor-g5
.venv/bin/python -m unittest -v test_compile_model
```
