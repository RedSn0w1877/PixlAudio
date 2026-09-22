# PixlAudio 4-stem separation — Runpod serverless worker.
#
# Runpod builds this for you from GitHub, so no local Docker is needed.

FROM runpod/pytorch:1.0.2-cu1281-torch280-ubuntu2404

# The base image ships a PEP 668 "externally managed" python; without this every pip install
# fails with `error: externally-managed-environment`.
ENV PIP_BREAK_SYSTEM_PACKAGES=1
ENV PYTHONUNBUFFERED=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# demucs pins nothing that conflicts with the image's torch 2.8 + CUDA 12.8 build, so torch is
# left exactly as the base image has it. Installing torch again here would silently pull a
# CPU-only wheel and every job would run at about a tenth of the speed.
RUN pip install --no-cache-dir demucs runpod

# Bake the htdemucs weights into the image (~300 MB). Without this, every cold start downloads
# them again *and bills you for those seconds* — on a worker that scales to zero, that is most of
# the cost of a short job.
RUN python -c "from demucs.pretrained import get_model; get_model('htdemucs')"

# Path is relative to the repo root, not to this file: Runpod builds with the repository root as
# the build context, so `COPY handler.py` would not resolve.
COPY tools/runpod-serverless/handler.py /handler.py

CMD ["python", "-u", "/handler.py"]
