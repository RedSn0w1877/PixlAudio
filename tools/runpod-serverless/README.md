# On-demand stem separation (Runpod serverless)

This is the **pay-per-use** version of the separation backend. It sits at zero workers costing
nothing, starts when a job arrives, bills per second, and scales back to zero.

Compare with `../runpod/` (a pod): that one is a rented machine billing every hour it is up,
whether or not the app uses it. Use this one.

## What a job costs

The app sends only the loop region the studio is actually playing — not the whole song — and the
worker trims before separating. Demucs runs roughly in proportion to input length, so:

| | 30 s region | whole 4-minute song |
|---|---|---|
| GPU time | ~10–20 s | ~60–90 s |
| Cost on a 24 GB GPU | well under a cent | a few cents |

The model weights are baked into the image, so a cold start is container pull + load rather than
a 300 MB download you pay for on every job.

## Deploying it (no Docker needed on your PC)

1. Push this folder to a GitHub repo — either its own, or this project's repo with the Dockerfile
   path set below.
2. Runpod console → **Serverless** → **New Endpoint** → **Import Git Repository** (connect GitHub
   the first time). Pick the repo and branch, then set **Dockerfile path** to
   `/tools/runpod-serverless/Dockerfile`.

   Runpod builds with the **repository root** as the build context and offers no separate context
   field, which is why the `COPY` in the Dockerfile spells out the full path. Its "could not find
   `runpod.serverless.start()` in your repo" notice is a warning, not an error — that call lives in
   `handler.py`, which the scanner only looks for at the repo root.
3. Settings that matter:
   - **Active (min) workers: 0** — this is the setting that makes it free when idle.
   - **Max workers: 1** — one job at a time is plenty for a phone, and caps the damage.
   - **Idle timeout: 5 s** — how long a worker lingers after a job, still billing.
   - **GPU: any 24 GB** class. Bigger is not faster here.
   - **Container disk: 20 GB.**
4. When it finishes building, copy the **endpoint ID**.
5. In the app: Remix Studio → *Separation server*
   - Server address: `https://api.runpod.ai/v2/<endpoint-id>`
   - Access token: a Runpod **API key** (Settings → API Keys in the console)
   - Turn on *Upload tracks*.

## Protocol

Standard Runpod serverless, which is why the app's client works against both this and a plain
job server:

```
POST {base}/run        {"input": {"audio_base64": "...", "start_ms": 0, "duration_ms": 30000}}
                    -> {"id": "...", "status": "IN_QUEUE"}
GET  {base}/status/{id}
                    -> {"status": "COMPLETED", "output": {"stems": {"vocals": "<base64 wav>", ...}}}
```

Stems come back as base64 16-bit PCM WAV at 44.1 kHz — the format the Android engine reads
directly.

## Limits worth knowing

- Runpod caps a `/run` payload at about 10 MB. A 30-second stereo region is ~5 MB of PCM, ~7 MB
  once base64-encoded, so it fits; a whole uncompressed song would not. That is the second reason
  the app sends just the region.
- `STEM_MAX_DURATION_MS` (default 60000) clamps the server side too, so a malformed request
  cannot turn into a long, expensive job.
