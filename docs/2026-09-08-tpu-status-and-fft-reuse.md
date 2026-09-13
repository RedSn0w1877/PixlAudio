# Tensor G5 status and instrumental render allocation improvement

Audit date: September 8, 2026. Source inspection only; no phone inference was run for this audit.

## What this build actually runs

The stem separator currently uses the original 66,848,828-byte MDX-Net TFLite model through
the LiteRT 2.2 CPU Interpreter, with XNNPACK enabled and four threads. No weights were trained
or replaced. `app/src/main/assets/tais/` does not contain
`stem_separation_tensor_g5.tflite` or `stem_separation_tensor_g5.json`.

`TaisAiEngine` detects Tensor G5, checks for those two artifacts, and retains CPU execution
when they are absent. The actual LiteRT `CompiledModel` runner and arm64 Google Tensor
dispatch library are integrated. They are not evidence of active TPU inference by themselves.
The guarded path requires a validated compiled artifact and CPU output comparison before
reporting TPU execution; other operations may still remain on CPU in a partially compiled graph.

The wav2vec2 lyric aligner separately requests ONNX Runtime's NNAPI provider and falls back
to CPU on initialization failure. A provider request does not establish which operators ran
on an accelerator. Downloading existing synced lyrics does not run an acoustic model.

No TPU latency improvement, full-graph delegation, or new model quality improvement has been
measured. Beta 2 should not advertise verified TPU acceleration.

## Current official requirements

Google documents Tensor G5 support through AOT compilation and `CompiledModel`; Google Tensor
does not yet support on-device JIT compilation. Pixel 10 Pro is among the supported devices.
A supported phone therefore still needs a separately compiled model and compatible runtime.
[LiteRT NPU documentation](https://developers.google.cn/edge/litert/next/npu),
[Google Tensor SDK](https://developers.google.com/edge/tensor-sdk),
[Google's supported-device announcement](https://developers.googleblog.com/google-tensor-sdk-beta-with-litert/).

The current official installer points to public compiler artifact version `6.1.0` and retains
the `GOOGLE_TENSOR_SDK_BETA` override for a locally supplied compiler archive. Compilation is
supported on Linux x86_64. This is a separate vendor compiler, not the Android dispatch library.
[Official installer source](https://raw.githubusercontent.com/google-ai-edge/LiteRT/main/ci/tools/python/vendor_sdk/google_tensor/setup.py).

The existing September 7 research recorded HTTP 404 for that public compiler URL. A September 8
direct availability probe could not connect because of the local sandbox network restriction;
this audit does not claim a freshly verified 404 or that every distribution route is unavailable.
No SDK archive or large model was downloaded. The compiler, compiled model, dispatch/runtime,
and phone platform must be validated together before deployment. Google's published compatibility
table also ties its SDK versions to LiteRT and Android platform versions.
[Tensor SDK release notes](https://developers.google.cn/edge/tensor-sdk/release-notes).

The app also accepts a future signed model pack in its private
`files/tais_compiled_models/` directory. The pack must contain
`stem_separation_tensor_g5.tflite` and `stem_separation_tensor_g5.json`; the manifest's source
hash, compiled hash, tensor signature, dispatch partition counts, target, and LiteRT version are
checked before the file is opened. This is a delivery hook only: no model pack is present in this
source tree, and the CPU asset remains the active fallback.

## Allocation improvement in this change

The separator now owns one fixed-size `Fft.Workspace` for the complete render. It reuses that
workspace for left/right forward STFT and inverse reconstruction frames. For the model's
6,144-point FFT, Bluestein uses a 16,384-point convolution. Reusing its two scratch arrays avoids
allocating 128 KiB of scratch for every frame transform after workspace creation.

The convolution padding is explicitly cleared before each transform; input values overwrite
the rest of scratch. The operations, their order, float precision, model input shape, overlap,
and audio reconstruction remain unchanged. Immutable chirp/kernel plans are shared under a
synchronized cache. Mutable scratch belongs to the render and is not stored in a ThreadLocal
or global cache. Shared-workspace calls serialize; separate workspaces can execute concurrently.

This is a CPU allocation optimization. No device speedup percentage is claimed.

## Validation added

`FftWorkspaceTest` includes seven cases: an independent complex DFT comparison, bit-exact
equivalence with fresh scratch at the production size, production-size roundtrips, silence
after nonzero input, concurrent independent renders, a concurrently shared workspace, and
rejection of mismatched input sizes before mutation. Build and test execution are delegated
to the release validation run; this audit did not start a build.
