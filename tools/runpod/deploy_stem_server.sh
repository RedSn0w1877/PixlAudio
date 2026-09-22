#!/usr/bin/env bash
# Deploy the 4-stem separation server to a Runpod GPU pod.
#
# Why this script exists: the pod has no network volume, and a pod's container disk is wiped on
# restart. Everything installed below therefore disappears whenever the pod stops. This script is
# the whole setup, so redeploying is one command rather than an afternoon of remembering.
#
#   ./deploy_stem_server.sh root@<ip> -p <port> <api-token>
#
# Example (direct SSH from `list-pods` → ssh.direct.command):
#   ./deploy_stem_server.sh root@194.26.196.172 -p 21945 "$(cat ~/.ssh/pixlaudio_stem_token)"
#
# Afterwards the API is reachable at https://<podId>-8000.proxy.runpod.net (expose 8000/http on
# the pod), and every route except /health needs:  Authorization: Bearer <api-token>

set -euo pipefail

HOST="${1:?usage: deploy_stem_server.sh root@HOST -p PORT TOKEN}"
shift
PORT_FLAG="${1:?missing -p}"; PORT="${2:?missing port}"; shift 2
TOKEN="${1:?missing api token}"

SSH_KEY="${SSH_KEY:-$HOME/.ssh/pixlaudio_pod}"
SSH_OPTS=(-i "$SSH_KEY" -o BatchMode=yes -o StrictHostKeyChecking=accept-new "$PORT_FLAG" "$PORT")
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> uploading server"
scp "${SSH_OPTS[@]/-p/-P}" "$HERE/stem_server.py" "$HOST:/workspace/stem_server.py"

echo "==> installing demucs into a venv that inherits the image's torch"
# --system-site-packages keeps the image's torch+CUDA build; a plain venv would pull a second
# 2.5 GB torch and, worse, possibly a CPU-only one.
ssh "${SSH_OPTS[@]}" "$HOST" "set -e
  python -m venv --system-site-packages /workspace/venv
  /workspace/venv/bin/pip install -q --upgrade pip
  /workspace/venv/bin/pip install -q demucs fastapi 'uvicorn[standard]' python-multipart
  /workspace/venv/bin/python -c 'import demucs; print(\"demucs\", demucs.__version__)'
" < /dev/null

echo "==> warming the model (downloads htdemucs weights, ~1 min)"
ssh "${SSH_OPTS[@]}" "$HOST" "set -e
  ffmpeg -y -loglevel error -f lavfi -i anullsrc=r=44100:cl=stereo -t 8 /workspace/warmup.wav
  /workspace/venv/bin/python -m demucs.separate -n htdemucs -o /workspace/warmup_out \
    --filename '{stem}.{ext}' -d cuda /workspace/warmup.wav > /dev/null 2>&1
  ls /workspace/warmup_out/htdemucs/
" < /dev/null

echo "==> writing launcher"
ssh "${SSH_OPTS[@]}" "$HOST" "cat > /workspace/run_server.sh <<'EOF'
#!/usr/bin/env bash
export STEM_API_TOKEN='$TOKEN'
export STEM_WORK_DIR=/workspace/stem_jobs
export PORT=8000
cd /workspace
exec /workspace/venv/bin/python /workspace/stem_server.py
EOF
chmod 700 /workspace/run_server.sh" < /dev/null

echo "==> starting"
# NOTE: the pkill pattern is bracketed so it cannot match this very SSH command line — an
# unbracketed 'stem_server' kills the shell running it, which looks exactly like "the server
# refuses to start".
ssh "${SSH_OPTS[@]}" "$HOST" "pkill -f '[s]tem_server' || true
  sleep 1
  nohup /workspace/run_server.sh > /workspace/server.log 2>&1 &
  sleep 7
  curl -s --max-time 10 localhost:8000/health" < /dev/null

echo
echo "==> done. Verify publicly (note: Runpod's proxy 403s python-urllib's default user-agent):"
echo "    curl -s https://<podId>-8000.proxy.runpod.net/health"
