#!/usr/bin/env bash
set -Eeuo pipefail

if ! command -v node >/dev/null 2>&1; then
  if command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update
    sudo apt-get install -y nodejs npm
  elif command -v dnf >/dev/null 2>&1; then
    sudo dnf install -y nodejs npm
  elif command -v pacman >/dev/null 2>&1; then
    sudo pacman -Sy --noconfirm nodejs npm
  else
    echo "Install Node.js 22+ with your Linux package manager, then run this script again." >&2
    exit 1
  fi
fi

exec "$(dirname "$0")/run.sh"