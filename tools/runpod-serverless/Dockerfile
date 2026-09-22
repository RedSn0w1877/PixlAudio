# PixlAudio 4-stem separation — Runpod serverless worker.
#
# Runpod builds this for you from GitHub, so no local Docker is needed.

# The official PyTorch runtime image, not runpod/pytorch. This is a *worker*: it runs one Python
# process and exits. It has no use for the JupyterLab, SSH daemon and dev tooling that make the
# Runpod dev image 10.5 GB, and every one of those gigabytes is paid for twice — once in build
# time exporting the OCI tarball, once in cold-start pull time on a fresh worker.
# 4.29 GB, ships CUDA 12.8 + cuDNN 9 + torch 2.8, which is the whole dependency list.
FROM pytorch/pytorch:2.8.0-cuda12.8-cudnn9-runtime

ENV PYTHONUNBUFFERED=1 \
    DEBIAN_FRONTEND=noninteractive \
    # Pin where the weights land so the bake below and the running worker agree. Without this
    # the cache defaults under $HOME, which is not the same user at build time and at run time.
    TORCH_HOME=/opt/models \
    XDG_CACHE_HOME=/opt/models

# The handler shells out to ffmpeg twice per job: once to trim and normalise the uploaded region,
# once to convert Demucs' float WAVs to the 16-bit PCM the Android loader parses.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# Pinned, because an unpinned `demucs` silently changing major version is a broken endpoint
# discovered at 2am. On Linux, demucs needs no torchaudio (that is a `train`-extra dependency),
# so pip has no reason to touch the image's CUDA torch — which is exactly what we want, since
# resolving torch again would pull a CPU-only wheel and every job would run ~10x slower.
RUN pip install --no-cache-dir demucs==4.1.0 runpod

# Bake the htdemucs weights into the image (~1 GB, a bag of four models). Without this, every
# cold start downloads them again *and bills you for those seconds* — on a worker that scales to
# zero, that is most of the cost of a short job.
RUN python -u -c "\
from demucs.pretrained import get_model; \
m = get_model('htdemucs'); \
print('baked htdemucs:', sum(1 for _ in m.models), 'models')"

# Path is relative to the repo root, not to this file: Runpod builds with the repository root as
# the build context, so `COPY handler.py` would not resolve.
COPY tools/runpod-serverless/handler.py /handler.py

CMD ["python", "-u", "/handler.py"]
