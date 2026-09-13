# Beta 2 release packaging audit

Audited September 8, 2026 against the release staging tree and the exact cached dependency
artifacts. This review did not run Gradle, install an APK, or test a phone.

## Release-only issues fixed

1. `data.repository.LyricsData` is a private Gson disk-cache DTO with four unannotated fields:
   `plainLyrics`, `syncedLyrics`, `wordByWordLyrics`, and `lyricsDocument`. Its package is outside
   the existing model/network rules. Added an exact class rule preserving fields and constructors
   so obfuscation cannot change the saved JSON keys or remove the reflective object contract.
   No repository-wide keep was added.

2. The new home catalog cache nests `data.youtube.YouTubeSearchResult`, whose fields also
   lack Gson annotations and are outside the existing DTO package rules. The home container's
   `@Keep` does not recursively retain nested field types. Added an exact fields/constructors
   rule for this value; the annotated home containers, `Song`, and Spotify DTOs already have
   coverage.

3. `litert-api-2.2.0.aar` has consumer rules for the older
   `org.tensorflow.lite.annotations.UsedByReflection` annotation. Its new API classes are not
   annotated with that marker. The bundled arm64 `liblitert_jni.so` contains literal class names
   and constructor descriptors for `LiteRtException`, `TensorBufferRequirements`, `TensorType`,
   `TensorType$ElementType`, and `TensorType$Layout`, and literal enum field names including
   `FLOAT`, `INT`, and `UNKNOWN`. Added exact keep rules for these five native-created types.
   AGP 9.2.1's `proguard-common.txt` already contains the native-method name/descriptor rule,
   covering the directly called `CompiledModel`, `Environment`, and `TensorBuffer` entry points.
   No entire LiteRT package keep was added.

## Existing coverage reviewed

- `LyricsDoc`, `LyricsMetadata`, `Voice`, `TimedLine`, and `TimedSyllable` use compiler-generated
  kotlinx.serialization serializers. Existing project serializer/companion rules plus
  `kotlinx-serialization-core-jvm:1.11.0`'s embedded consumer rules cover their lookup paths.
  Their JSON fields come from generated descriptors, not obfuscated Kotlin property names.
- LRCLIB metadata, including `lyricsfile`, uses `@SerializedName` and is inside the existing
  `data.network` keep. Gson 2.14 also supplies annotated-field and TypeToken consumer rules.
- NetEase response handling uses `JsonObject`/`JsonArray` with explicit JSON keys; YRC/RichSync
  converters parse values into the typed lyric model. No new reflective DTO rule is needed.
- SnakeYAML 2.4 supplies no consumer rules. The actual lyric path explicitly constructs
  `SafeConstructor`, a resolver override, and a map/list document. It does not construct app
  beans from YAML class names, so a broad SnakeYAML/app-model keep is not justified.
- TFLite and ONNX assets remain uncompressed for file-descriptor/mapping access.
- Native legacy packaging remains enabled so the Google Tensor dispatch `.so` is extracted
  into `applicationInfo.nativeLibraryDir`. The LiteRT manifests declare Google Tensor driver
  libraries as optional. This packaging does not establish TPU execution; the compiled stem
  artifact is still absent, as described in the separate acceleration audit.

## Previous release signing identity

Read-only certificate extraction from the APK Signature Scheme v2 block of both existing
August 6 release APKs under the original checkout's `app/build/outputs/apk/release` found:

| Artifact | Bytes | Leaf certificate SHA-256 |
| --- | ---: | --- |
| `app-arm64-v8a-release.apk` | 173026364 | `3189ce56aeecf435eace45369fe2d9bc1a7420774e9d0a607015105a2d130ce6` |
| `app-armeabi-v7a-release.apk` | 166780842 | `3189ce56aeecf435eace45369fe2d9bc1a7420774e9d0a607015105a2d130ce6` |

This matches the certificate fingerprint reported for the user-tested debug build. It is
evidence about the stored old APKs' signing identity, not proof that those exact files were
publicly distributed or that the current debug keystore has not changed.

**This was certificate extraction, not full signature verification.** The attempted
`java -jar apksigner.jar verify --print-certs` could not access the Android SDK jar, and a
read-only directory listing of `Sdk/build-tools` returned Access denied. No escalation was
attempted. The release owner must run the normal APK signature verifier on the finished
artifacts and compare the final certificate, application ID, and version code before delivery.
No signing password, private key, or signing-property contents were read or printed.

## Remaining validation

The keep changes require the normal minified release build to complete successfully.
Debug unit tests alone do not exercise R8 output. Device runtime validation of the minified
artifact and real TPU execution remain separate from this static review.

## Repeatable artifact checks

`tools/verify_beta2_release.py` uses Python 3.11+ standard-library modules only. After Gradle
finishes, run it with the finished release directory, JUnit result directory, and AGP metadata:

```powershell
python tools/verify_beta2_release.py --apk app/build/outputs/apk/release `
  --tests app/build/test-results/testDebugUnitTest `
  --metadata app/build/outputs/apk/release/output-metadata.json `
  --expected-version-name YOUR_RELEASE_VERSION --expected-version-code YOUR_VERSION_CODE `
  --output build/beta2-release-verification.json
```

The report fails on absent APKs/tests, test failures, invalid expected model hashes, changed
stem bytes/hash, missing required model assets, mismatched metadata versions, and invalid
native-library alignment. It records each native library's ZIP data offset and ELF PT_LOAD
segments. Stored native libraries require a 16 KiB ZIP offset on the 64-bit ABIs. Compressed
libraries are extracted by Android, so their ZIP offsets are reported without an alignment
failure; their ELF segments must still be compatible. The default 16 KiB enforcement ABIs are
`arm64-v8a` and `x86_64`; 32-bit ELF alignment is reported separately. Overrides are available
through repeatable `--require-16k-abi` and `--required-asset` arguments.

The script writes only its requested JSON report and refuses to overwrite supplied inputs.
It does not execute/build an APK, access a phone, or verify APK signatures. Eight small
stdlib fixture tests for ELF, ZIP offsets, JUnit aggregation/failures, missing inputs, and
metadata mismatches passed with `python -B -m unittest -v test_verify_beta2_release` from
`tools/`. Final release APK inspection and Android-tool verification remain the release
owner's responsibility.
