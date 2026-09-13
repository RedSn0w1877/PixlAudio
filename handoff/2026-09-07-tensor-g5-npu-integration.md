# Tensor G5 NPU integration follow-up

**Authorization update:** The user subsequently explicitly authorized automated phone testing.
Initial Beta 2 phone results are in `build/phone-qa/results.json`: 124 retained downloads,
1.128-second cold offline launch, and downloaded audio playing offline past 160 seconds.
A Bluetooth device sent immediate media-pause commands; disabling Bluetooth temporarily
isolated that external cause. Connectivity, Bluetooth and speaker volume were restored.
Restore `stay_on_while_plugged_in` to its original value **7** after remaining tests.
Latest follow-up: `2026-09-07-lyrics-resync.md`. The resync APK built successfully with 487 passing tests and is not yet installed. The phone is now disconnected; reconnect is pending. The earlier no-phone-test statements
below describe the integration before this new authorization.

Project: `C:\Users\Hoa Vo\Downloads\PixelPlayer-master\PixelPlayer-master`.
The `Downloads\Code Projects\PixelPlayer-master` directory is an older copy.
This checkout has no Git metadata. NPU originals are in `backups/npu-2026-09-07/`;
prior Beta 2 originals remain in `backups/beta2-2026-09-07/`.

## Installed build and authorization

The user authorized installing the completed Beta 2 build and then continuing work.
After they accepted USB debugging, `adb install -r` returned `Success` for the
existing ARM64 debug APK. Read-only package metadata confirmed version
`0.7.6-beta2`, code `11`, package `com.theveloper.pixelplay.debug`, and update time
2026-09-07 10:24:55. The install used the data-preserving update option.

Installed APK SHA-256:
`9677f8358c5abb7688df3cc2beed014eb80704110144a8bb12e339d87142410d`.
Its verification is in `build/beta2-verification.json` and the previous Beta 2
handoff. That JSON records the earlier pre-install snapshot.

Computer checks remain authorized. **No app launch, phone playback, phone model
benchmark, or functional phone testing was performed.** User approval is still
required for those tests. The installed APK predates this NPU source integration.

## Actual NPU status

**No Tensor G5 compiled stem model exists yet and TPU execution is unverified.**
Google Tensor requires ahead-of-time compilation; adding a runtime or requesting
NPU execution does not compile an ordinary model on the phone.

Google's stable SDK documentation still points to beta access. The official
nightly SDK installer advertises a public compiler archive, but that exact
download returned HTTP 404 from both Windows and WSL on 2026-09-07:

`https://redirector.gvt1.com/edgedl/tensor-ml-sdk/sdk/releases/6.1.0/litert_plugin_compiler.tar.gz`

The public LiteRT Google Tensor adapter is not the separate vendor compiler.
The user has been asked for their Tensor SDK archive or authorized download link;
none has been provided. Do not substitute the original TFLite or a test fixture
for an accelerated model.

## Integration

- Migrated the CPU Interpreter dependency to LiteRT 2.2.0, which also supplies
  the actual `CompiledModel` API. Do not add the old TensorFlow Lite artifact beside
  it: the Java Interpreter classes conflict.
- Bundled the unchanged official ARM64 Google Tensor dispatch library. Provenance,
  checksum and packaging explanation: `tools/litert-runtime/README.md`.
  Attribution is included in the app's third-party notices.
- `jniLibs.useLegacyPackaging=true` exposes dispatch in installed `nativeLibraryDir`.
  Merged Debug manifests contain `extractNativeLibs=true` and optional vendor
  drivers `libedgetpu_litert.so`/`libedgetpu_util.so`; NPU hardware is optional.
- Google's required `litert` and `litert-api` 2.2.0 AARs share a manifest namespace.
  `android.uniquePackageNames=false` permits their metadata under AGP 9; Java
  duplicate-class checking remains enabled. Remove the compatibility flag when
  Google publishes artifacts with unique namespaces.
- `TaisNpuRunner` validates actual tensor metadata, reuses native buffers, rejects
  non-finite output, and releases partial native initialization. LiteRT permits CPU
  operations around compiled NPU partitions; do not claim full-graph TPU coverage.
- `TaisNpuModelContract` validates SHA-bound identity, target, runtime, signature,
  dispatch manifest and tensor shape. CPU/NPU comparison checks finite output and
  precision-specific numerical tolerance. This is not a listening test.
- `TaisAiEngine` retains CPU operation when no valid compiled model is bundled.
  Its first real audio chunk must pass CPU parity before reporting TPU execution;
  a synthetic benchmark cannot replace that check. CPU fallback retains the chunk
  if TPU initialization, execution or validation fails. CPU reference execution
  finishes and closes before lazy TPU initialization to reduce peak memory.
- No model weights were retrained. NPU speed, coverage, memory, temperature and
  separation quality remain unmeasured on the phone.

## Compiler tooling and next step

`tools/tensor-g5/RESEARCH.md` records sources, versions, model contract and workflow.
WSL Ubuntu x86_64 is available. The isolated environment contains
`ai-edge-litert==2.2.0`; no system packages were changed.

Once an actual vendor SDK is supplied, run from this checkout inside WSL:

```bash
python3 tools/tensor-g5/prepare.py --sdk /absolute/path/litert_plugin_compiler.tar.gz --sdk-version ACTUAL_VERSION
tools/tensor-g5/.venv/bin/python tools/tensor-g5/compile_model.py
```

The tools validate archive extraction, compiler ELF/hash, real dispatch instances,
bytecode ranges, target stamp and unchanged FLOAT32 I/O. They produce a model and
manifest only after a successful compile, and never write app assets or install
an APK. Default compilation requests float32 without truncation; reduced precision
is explicit and requires parity checks.

After a successful compile, inspect the report before placing the output pair at
`app/src/main/assets/tais/stem_separation_tensor_g5.tflite` and
`app/src/main/assets/tais/stem_separation_tensor_g5.json`. Build a candidate and
request approval for specific phone tests. Neither asset currently exists.

## Computer validation

Compiler parser/archive regression tests: **9 passed** in WSL. The real source
model was rejected as an NPU artifact (zero dispatch operations). Compilation
without the vendor SDK failed clearly and created no output model or manifest.
Official ARM64 dispatch and LiteRT runtime ELF load segments satisfy 16 KiB alignment.

Android checks are recorded in `build/npu-validation.log`. They run unit tests and
duplicate-class validation without rebuilding the installed Beta 2 APK.
Final Android result is recorded below when complete. A real CPU inference of the original
model under LiteRT 2.2.0 passed in WSL: all 3,145,728 output floats were finite.
See `build/npu-cpu-model-check.json`; this does not establish phone speed or audio quality.

PowerShell, from the actual checkout:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS = '"-Djdk.net.unixdomain.tmpdir=C:\Users\Hoa Vo\Downloads\PixelPlayer-master\PixelPlayer-master"'
.\gradlew.bat :app:testDebugUnitTest :app:checkDebugDuplicateClasses --no-daemon --console=plain '-Pksp.incremental=false'
```

If generated-directory cleanup fails, inspect and clear ReadOnly attributes only
inside verified `app/build` and `shared/build` paths. Do not recursively delete
user directories or run concurrent Gradle builds.

## Primary references

- [Google Tensor SDK](https://developers.google.com/edge/litert/next/tensor-sdk)
- [LiteRT NPU acceleration](https://developers.google.com/edge/litert/next/npu)
- [Official SDK installer](https://github.com/google-ai-edge/LiteRT/blob/main/ci/tools/python/vendor_sdk/google_tensor/setup.py)
- [SDK access](https://services.google.com/fb/forms/tensor_ml_sdk_experimental_access/)
- [LiteRT 2.2.0 runtime](https://github.com/google-ai-edge/LiteRT/releases/tag/v2.2.0)
