#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$APP_DIR"

export PORT="${PORT:-8787}"
if [[ ! "$PORT" =~ ^[0-9]+$ ]] || (( 10#$PORT < 1 || 10#$PORT > 65535 )); then
  echo "PORT must be an integer between 1 and 65535; found '${PORT}'." >&2
  exit 1
fi

APP_URL="http://localhost:${PORT}"

echo "Using default network configuration"

if ! command -v node >/dev/null 2>&1; then
  echo "Node.js 22 or newer is required. Install Node.js, then run this script again." >&2
  exit 1
fi

node_major="$(node -p "process.versions.node.split('.')[0]")"
if (( node_major < 22 )); then
  echo "Node.js 22 or newer is required; found $(node --version)." >&2
  exit 1
fi

if ! command -v npm >/dev/null 2>&1; then
  echo "npm is required because it installs Playwright. It normally ships with Node.js." >&2
  exit 1
fi

export NPM_CONFIG_PROGRESS=true
export NPM_CONFIG_AUDIT=false
export NPM_CONFIG_FUND=false
export NPM_CONFIG_FETCH_TIMEOUT="${NPM_CONFIG_FETCH_TIMEOUT:-30000}"
export NPM_CONFIG_FETCH_RETRIES="${NPM_CONFIG_FETCH_RETRIES:-1}"

if [[ ! -d node_modules/playwright ]]; then
  echo "Installing project dependencies..."
  npm install --no-audit --no-fund
fi

if [[ ! -f node_modules/playwright/cli.js ]]; then
  echo "Playwright package is unavailable after installation." >&2
  exit 1
fi

if [[ "${SKIP_BROWSER_INSTALL:-0}" != "1" ]]; then
  echo "Checking Playwright Firefox..."
  npx --no-install playwright install firefox
fi

if curl --silent --fail --max-time 3 "${APP_URL}/api/state" >/dev/null 2>&1; then
  echo "Goldline is already running at ${APP_URL}"
  exit 0
fi

echo "Starting Goldline at ${APP_URL}"
exec node src/app/server.js