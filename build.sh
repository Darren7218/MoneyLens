#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [ ! -f build/moneylens-preview.jks ]; then
  echo 'Restore your private signing key to build/moneylens-preview.jks before building a compatible update.' >&2
  exit 1
fi
exec ./gradlew :app:assembleRelease "$@"
