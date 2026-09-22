"""
PixlAudio 4-stem separation — Runpod **serverless** worker.

Unlike the pod version (tools/runpod/stem_server.py), this costs nothing when idle: Runpod keeps
zero workers running, starts one when a job arrives, bills per second, and scales back to zero.

Job input (all fields except `audio_base64` optional):

    {
      "input": {
        "audio_base64": "<the track, base64>",
        "filename": "track.mp3",
        "model": "htdemucs",
        "start_ms": 0,            # separate only the part the studio actually loops…
        "duration_ms": 30000      # …which is far cheaper than the whole song
      }
    }

Output:

    {
      "stems": {"vocals": "<base64 wav>", "drums": "...", "bass": "...", "other": "..."},
      "sample_rate": 44100,
      "duration_ms": 30000
    }

Stems come back as base64 16-bit PCM WAV — the exact format the Android engine memory-maps. Four
30-second stems are ~5 MB each before encoding, so the reply stays inside Runpod's payload limit.
Separating a whole song would not, which is the other reason `duration_ms` exists.
"""

import base64
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import runpod

MODEL = os.environ.get("STEM_MODEL", "htdemucs")
STEMS = ("vocals", "drums", "bass", "other")
MAX_DURATION_MS = int(os.environ.get("STEM_MAX_DURATION_MS", "60000"))
SAMPLE_RATE = 44100


def _decode_input(job_input, work_dir: Path) -> Path:
    audio_b64 = job_input.get("audio_base64")
    if not audio_b64:
        raise ValueError("audio_base64 is required")
    suffix = Path(job_input.get("filename") or "input.audio").suffix or ".audio"
    source = work_dir / f"input{suffix}"
    source.write_bytes(base64.b64decode(audio_b64))
    return source


def _trim(source: Path, work_dir: Path, start_ms: int, duration_ms: int) -> Path:
    """
    Cut the requested window *before* separation, and normalise to 44.1 kHz stereo.

    This is the whole cost story: Demucs runs roughly in proportion to input length, so
    separating the 30 seconds the user is actually looping costs about an eighth of what a
    four-minute track would.
    """
    trimmed = work_dir / "trimmed.wav"
    command = ["ffmpeg", "-y", "-loglevel", "error"]
    if start_ms > 0:
        command += ["-ss", f"{start_ms / 1000:.3f}"]
    command += ["-i", str(source)]
    if duration_ms > 0:
        command += ["-t", f"{duration_ms / 1000:.3f}"]
    command += ["-ac", "2", "-ar", str(SAMPLE_RATE), "-acodec", "pcm_s16le", str(trimmed)]
    subprocess.run(command, check=True)
    return trimmed


def _device() -> str:
    """
    GPU when there is one, CPU when there is not.

    Hardcoding `cuda` turns a worker that landed without a visible GPU into a hard failure the
    app reports as "separation failed"; falling back makes it merely slow, which for a 30-second
    region is a couple of minutes rather than an error.
    """
    try:
        import torch
        if torch.cuda.is_available():
            return "cuda"
    except Exception as exc:  # noqa: BLE001 — torch import problems are worth seeing in the log
        print(f"torch unavailable for device probe: {exc}", flush=True)
    print("no CUDA device visible; separating on CPU", flush=True)
    return "cpu"


def _separate(source: Path, work_dir: Path) -> dict:
    out_dir = work_dir / "out"
    out_dir.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            sys.executable, "-m", "demucs.separate",
            "-n", MODEL,
            "-o", str(out_dir),
            "--filename", "{stem}.{ext}",
            "-d", _device(),
            str(source),
        ],
        check=True,
    )
    produced = {path.stem: path for path in out_dir.rglob("*.wav")}
    missing = [stem for stem in STEMS if stem not in produced]
    if missing:
        raise RuntimeError(f"model produced no {', '.join(missing)}")
    return produced


def _to_16bit(path: Path, work_dir: Path, name: str) -> Path:
    """Demucs writes float WAVs; the Android loader parses canonical 16-bit PCM only."""
    converted = work_dir / f"{name}_16.wav"
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(path),
         "-acodec", "pcm_s16le", "-ar", str(SAMPLE_RATE), "-ac", "2", str(converted)],
        check=True,
    )
    return converted


def _self_test(work_dir: Path) -> dict:
    """
    Run the whole chain on a generated one-second tone and report only sizes and timings.

    This exists because the real reply is four base64 WAVs — megabytes — which makes "does the
    endpoint work?" an expensive question to ask. A probe exercises exactly the same path (ffmpeg
    trim, Demucs on the GPU, ffmpeg convert) and answers in a few hundred bytes, so it can be run
    from anywhere, including the app's own diagnostics.
    """
    started = time.time()
    tone = work_dir / "probe.wav"
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi",
         "-i", "sine=frequency=220:duration=1:sample_rate=44100",
         "-ac", "2", "-acodec", "pcm_s16le", str(tone)],
        check=True,
    )
    device = _device()
    separated = time.time()
    produced = _separate(tone, work_dir)
    sizes = {stem: produced[stem].stat().st_size for stem in STEMS}
    return {
        "ok": True,
        "model": MODEL,
        "device": device,
        "stem_bytes": sizes,
        "separate_seconds": round(time.time() - separated, 2),
        "total_seconds": round(time.time() - started, 2),
    }


def handler(job):
    started = time.time()
    job_input = job.get("input") or {}
    work_dir = Path(tempfile.mkdtemp(prefix="stems_"))
    try:
        # A health check that does not haul four WAVs back across the wire.
        if job_input.get("probe"):
            return _self_test(work_dir)
        start_ms = max(0, int(job_input.get("start_ms", 0)))
        duration_ms = int(job_input.get("duration_ms", 30000))
        if duration_ms <= 0 or duration_ms > MAX_DURATION_MS:
            duration_ms = MAX_DURATION_MS

        source = _decode_input(job_input, work_dir)
        trimmed = _trim(source, work_dir, start_ms, duration_ms)
        produced = _separate(trimmed, work_dir)

        stems = {}
        for stem in STEMS:
            converted = _to_16bit(produced[stem], work_dir, stem)
            stems[stem] = base64.b64encode(converted.read_bytes()).decode("ascii")

        return {
            "stems": stems,
            "sample_rate": SAMPLE_RATE,
            "duration_ms": duration_ms,
            "model": MODEL,
            "seconds": round(time.time() - started, 2),
        }
    except subprocess.CalledProcessError as exc:
        return {"error": f"audio tool failed: {exc}"}
    except Exception as exc:  # noqa: BLE001 — surfaced to the app verbatim
        return {"error": str(exc)}
    finally:
        shutil.rmtree(work_dir, ignore_errors=True)


print(f"PixlAudio stem worker ready — model={MODEL}, max={MAX_DURATION_MS}ms", flush=True)
runpod.serverless.start({"handler": handler})
