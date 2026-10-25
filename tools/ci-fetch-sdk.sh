#!/usr/bin/env bash
# Fetch only the configured official Maven coordinate; fingerprint checked next.
set -euo pipefail
sdk_version="$(python3 -c 'import sys; sys.path.insert(0,"tools"); from release_pipeline import config; print(config()["sdkVersion"])')"
sdk_dir="${RUNNER_TEMP:-$PWD/.cache/sdk}"
mkdir -p "$sdk_dir"
sdk_path="$sdk_dir/Server-$sdk_version.jar"
curl --fail --silent --show-error --max-time 180 --retry 0 \
  "https://maven.hytale.com/release/com/hypixel/hytale/Server/$sdk_version/Server-$sdk_version.jar" \
  --output "$sdk_path"
printf '%s\n' "$sdk_path"
