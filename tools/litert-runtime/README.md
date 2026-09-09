# LiteRT 2.2 Google Tensor runtime

The arm64 dispatch binary bundled at
`app/src/main/jniLibs/arm64-v8a/libLiteRtDispatch_GoogleTensor.so` is copied unchanged
from the [official LiteRT 2.2.0 AOT runtime archive](https://github.com/google-ai-edge/LiteRT/releases/download/v2.2.0/litert_npu_runtime_libraries.zip).

Binary SHA-256: `35b59265eb8595a1d28c2f69693b1cad39d1fa4a38c2c18d5d54550d079264ac`.
The dispatch source is Apache 2.0; its notice and license are included in the
application's `THIRD_PARTY_NOTICES.md`.

`TaisNpuRunner` uses the actual 2.2 `CompiledModel` API and an explicit
`Environment.Option.DispatchLibraryDir` pointing at `applicationInfo.nativeLibraryDir`.
`jniLibs.useLegacyPackaging = true` ensures Android extracts the library there.
The dispatch binary is arm64 only; other ABIs must retain the CPU fallback.

The official 2.2 AAR manifests already declare the optional Android native libraries
`libedgetpu_litert.so` and `libedgetpu_util.so`. The
[Google Tensor dispatch loader](https://github.com/google-ai-edge/LiteRT/blob/v2.2.0/litert/vendors/google_tensor/dispatch/sb_api_late_binding.cc)
loads `libedgetpu_litert.so` by soname, allowing the system's public-library namespace
to resolve its vendor driver. Do not bundle a vendor driver from a phone or add
undocumented native-library requirements.

The app must validate the model's SHA-bound AOT manifest before opening the runner.
The runner separately validates the actual `serving_default` signature's `args_0`
and `output_0` tensor metadata: one FLOAT32 input and output, shape `[1,4,3072,256]`,
contiguous layout, 12 MiB each. It retains the native tensor buffers and one input
float array across chunks. LiteRT 2.2's public Kotlin `readFloat()` API allocates a
fresh output array; it does not offer an existing-array or ByteBuffer destination.
Invalid output is rejected before writing into the caller's output buffer.

LiteRT 2.2 internally adds CPU support to NPU-only options to support partial AOT
partitioning. An NPU option alone is therefore insufficient evidence of TPU use or
complete graph coverage. Validate real dispatch partitions in the compiled artifact,
and verify execution and numerical parity on the target device before claiming
acceleration. No device validation was performed while adding this runtime.

The vendor compiler and a valid compiled stem model are separate prerequisites;
see `../tensor-g5/RESEARCH.md` for compiler availability and the deployment workflow.
