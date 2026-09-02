#!/usr/bin/env bash
set -Eeuo pipefail

PORT="${PORT:-8787}"

pids="$(lsof -ti "tcp:${PORT}" 2>/dev/null || true)"
if [[ -z "$pids" ]]; then
  echo "Goldline is not running on port ${PORT}."
  exit 0
fi

echo "Stopping Goldline on port ${PORT} (pid: ${pids//$'\n'/ })..."
# SIGTERM lets src/app/server.js flush state and clear its refresh timers.
kill $pids 2>/dev/null || true

for _ in {1..20}; do
  sleep 0.5
  [[ -z "$(lsof -ti "tcp:${PORT}" 2>/dev/null || true)" ]] && { echo "Goldline stopped."; exit 0; }
done

echo "Graceful shutdown timed out; forcing."
kill -9 $(lsof -ti "tcp:${PORT}" 2>/dev/null || true) 2>/dev/null || true
echo "Goldline stopped."
