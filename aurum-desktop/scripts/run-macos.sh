#!/usr/bin/env bash
set -Eeuo pipefail

if ! command -v node >/dev/null 2>&1; then
  echo "Node.js is required. Install Node.js 22+ and run this script again." >&2
  echo "Download: https://nodejs.org/ or install with your preferred package manager." >&2
  exit 1
fi

exec "$(dirname "$0")/run.sh"