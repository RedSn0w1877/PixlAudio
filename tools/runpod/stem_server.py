"""
PixlAudio 4-stem separation server (Demucs htdemucs) for a Runpod pod.

Protocol deliberately mirrors Runpod's serverless job shape so the Android client
(RemixStemJobClient) polls with short connections instead of holding one long POST open
— the ~5m40s NAT idle death documented in DirectPostStemApiClient.kt:25-33.

  POST /run            multipart field "file" -> {"id", "status":"IN_QUEUE"}
  GET  /status/{id}                           -> {"status","progress","stems":{name:url},"error"}
  GET  /download/{id}/{stem}                  -> audio/wav (16-bit, 44.1k, stereo)
  POST /cancel/{id}                           -> {"status":"CANCELLED"}
  GET  /health                                -> {"ok":true,"device","model","queue"}

Every route except /health requires:  Authorization: Bearer <STEM_API_TOKEN>
The pod's proxy URL is public, so without this anyone could spend the GPU.
"""

import os
import queue
import shutil
import subprocess
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Optional

from fastapi import Depends, FastAPI, File, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse, JSONResponse
import uvicorn

TOKEN = os.environ.get("STEM_API_TOKEN", "").strip()
MODEL = os.environ.get("STEM_MODEL", "htdemucs")
WORK_ROOT = Path(os.environ.get("STEM_WORK_DIR", "/workspace/stem_jobs"))
JOB_TTL_SECONDS = int(os.environ.get("STEM_JOB_TTL", "7200"))
MAX_UPLOAD_BYTES = int(os.environ.get("STEM_MAX_UPLOAD", str(200 * 1024 * 1024)))
STEMS = ("vocals", "drums", "bass", "other")

WORK_ROOT.mkdir(parents=True, exist_ok=True)


@dataclass
class Job:
    id: str
    status: str = "IN_QUEUE"          # IN_QUEUE | IN_PROGRESS | COMPLETED | FAILED | CANCELLED
    progress: float = 0.0
    error: Optional[str] = None
    created_at: float = field(default_factory=time.time)
    source: Optional[Path] = None
    out_dir: Optional[Path] = None
    stems: Dict[str, Path] = field(default_factory=dict)
    cancelled: bool = False


JOBS: Dict[str, Job] = {}
JOBS_LOCK = threading.Lock()
WORK_QUEUE: "queue.Queue[str]" = queue.Queue()

app = FastAPI(title="PixlAudio stem separation")


def require_token(authorization: str = Header(default="")) -> None:
    if not TOKEN:
        raise HTTPException(500, "Server has no STEM_API_TOKEN configured")
    expected = f"Bearer {TOKEN}"
    # Constant-time-ish compare; these are short strings but no reason to leak length/prefix.
    if len(authorization) != len(expected) or not all(
        a == b for a, b in zip(authorization, expected)
    ):
        raise HTTPException(401, "Unauthorized")


def _device() -> str:
    try:
        import torch

        return "cuda" if torch.cuda.is_available() else "cpu"
    except Exception:
        return "cpu"


def _to_wav_16bit(src: Path, dst: Path) -> None:
    """Demucs writes float or 24-bit by default; the Android engine parses canonical
    16-bit PCM WAV (see TaisStemSeparator.writeStereoWavHeaderAndData)."""
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(src),
         "-acodec", "pcm_s16le", "-ar", "44100", "-ac", "2", str(dst)],
        check=True,
    )


def _separate(job: Job) -> None:
    out_dir = WORK_ROOT / job.id / "out"
    out_dir.mkdir(parents=True, exist_ok=True)
    job.out_dir = out_dir

    # sys.executable, not "python": the server runs from a venv that has demucs, while the
    # image's system python does not. Resolving by PATH silently picks the wrong interpreter.
    cmd = [
        sys.executable, "-m", "demucs.separate",
        "-n", MODEL,
        "-o", str(out_dir),
        "--filename", "{stem}.{ext}",
        "-d", _device(),
        str(job.source),
    ]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    tail: list[str] = []
    for line in proc.stdout:  # demucs prints a percentage bar to stdout
        if job.cancelled:
            proc.kill()
            raise RuntimeError("cancelled")
        tail.append(line.rstrip())
        del tail[:-12]  # keep only the last lines, for the failure message
        token = line.strip().split("%")[0].split()[-1] if "%" in line else ""
        try:
            job.progress = max(job.progress, min(float(token) / 100.0, 0.99))
        except ValueError:
            pass
    if proc.wait() != 0:
        detail = " | ".join(t for t in tail if t.strip())[-500:]
        raise RuntimeError(f"demucs exited {proc.returncode}: {detail}")

    produced = {p.stem: p for p in out_dir.rglob("*.wav")}
    missing = [s for s in STEMS if s not in produced]
    if missing:
        raise RuntimeError(f"model produced no {', '.join(missing)}")

    final: Dict[str, Path] = {}
    for stem in STEMS:
        dst = out_dir / f"{stem}_16.wav"
        _to_wav_16bit(produced[stem], dst)
        final[stem] = dst
    job.stems = final
    job.progress = 1.0


def _worker() -> None:
    while True:
        job_id = WORK_QUEUE.get()
        with JOBS_LOCK:
            job = JOBS.get(job_id)
        if job is None or job.cancelled:
            WORK_QUEUE.task_done()
            continue
        job.status = "IN_PROGRESS"
        try:
            _separate(job)
            job.status = "CANCELLED" if job.cancelled else "COMPLETED"
        except Exception as exc:  # noqa: BLE001 - surfaced to the client verbatim
            job.status = "CANCELLED" if job.cancelled else "FAILED"
            job.error = str(exc)
        finally:
            if job.source and job.source.exists():
                job.source.unlink(missing_ok=True)
            WORK_QUEUE.task_done()


def _reaper() -> None:
    while True:
        time.sleep(300)
        cutoff = time.time() - JOB_TTL_SECONDS
        with JOBS_LOCK:
            stale = [j for j in JOBS.values() if j.created_at < cutoff]
            for job in stale:
                JOBS.pop(job.id, None)
        for job in stale:
            shutil.rmtree(WORK_ROOT / job.id, ignore_errors=True)


@app.get("/health")
def health() -> JSONResponse:
    return JSONResponse(
        {"ok": True, "device": _device(), "model": MODEL, "queue": WORK_QUEUE.qsize()}
    )


@app.post("/run", dependencies=[Depends(require_token)])
async def run(file: UploadFile = File(...)) -> JSONResponse:
    job_id = uuid.uuid4().hex
    job_dir = WORK_ROOT / job_id
    job_dir.mkdir(parents=True, exist_ok=True)
    suffix = Path(file.filename or "input").suffix or ".audio"
    source = job_dir / f"input{suffix}"

    written = 0
    with source.open("wb") as handle:
        while chunk := await file.read(1024 * 1024):
            written += len(chunk)
            if written > MAX_UPLOAD_BYTES:
                handle.close()
                shutil.rmtree(job_dir, ignore_errors=True)
                raise HTTPException(413, "File too large")
            handle.write(chunk)

    job = Job(id=job_id, source=source)
    with JOBS_LOCK:
        JOBS[job_id] = job
    WORK_QUEUE.put(job_id)
    return JSONResponse({"id": job_id, "status": job.status})


@app.get("/status/{job_id}", dependencies=[Depends(require_token)])
def status(job_id: str) -> JSONResponse:
    with JOBS_LOCK:
        job = JOBS.get(job_id)
    if job is None:
        raise HTTPException(404, "No such job")
    body = {"id": job.id, "status": job.status, "progress": round(job.progress, 3)}
    if job.error:
        body["error"] = job.error
    if job.status == "COMPLETED":
        body["stems"] = {name: f"/download/{job.id}/{name}" for name in job.stems}
    return JSONResponse(body)


@app.get("/download/{job_id}/{stem}", dependencies=[Depends(require_token)])
def download(job_id: str, stem: str) -> FileResponse:
    with JOBS_LOCK:
        job = JOBS.get(job_id)
    if job is None or stem not in job.stems:
        raise HTTPException(404, "No such stem")
    return FileResponse(job.stems[stem], media_type="audio/wav", filename=f"{stem}.wav")


@app.post("/cancel/{job_id}", dependencies=[Depends(require_token)])
def cancel(job_id: str) -> JSONResponse:
    with JOBS_LOCK:
        job = JOBS.get(job_id)
    if job is None:
        raise HTTPException(404, "No such job")
    job.cancelled = True
    if job.status in ("IN_QUEUE", "IN_PROGRESS"):
        job.status = "CANCELLED"
    return JSONResponse({"id": job.id, "status": job.status})


threading.Thread(target=_worker, daemon=True).start()
threading.Thread(target=_reaper, daemon=True).start()

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("PORT", "8000")), log_level="info")
