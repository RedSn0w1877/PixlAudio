# Cloud Studio boundary

`CloudStudioClient` is the optional hosted accelerator boundary for Plus. It reuses the existing
Gradio and direct POST stem clients, but keeps the UI and workers independent of a particular
provider.

Before a render is attempted, `CloudStudioConfig` requires all of the following:

- an endpoint URL;
- explicit `consentToUpload = true` for sending audio off-device;
- HTTPS for non-local endpoints (HTTP is accepted only for local development hosts);
- an existing, non-empty source file.

When any requirement is missing, or the server fails, the client returns
`CloudStudioOutcome.Unavailable` so callers can continue with the on-device TAIS pipeline. No
server URL, API key, or cloud entitlement is bundled in the APK. A production Plus backend still
needs receipt/license verification, signed job authorization, usage quotas, retention/deletion
policy, and an endpoint configured at build or account provisioning time.

`AdvancedAudioExporter` produces an atomic ZIP containing the rendered instrumental WAV, optional
vocals WAV, and a small metadata JSON file. Callers must check the Plus entitlement before exposing
the export action; the utility itself stays reusable and does not grant access.
