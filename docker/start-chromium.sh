#!/bin/sh
set -eu

for binary in \
  /usr/bin/chromium \
  /usr/lib/chromium/chromium \
  /usr/lib/chromium/chromium-browser; do
  if [ -x "$binary" ]; then
    echo "Starting Chromium from $binary" >&2
    exec "$binary" "$@"
  fi
done

echo "Chromium executable was not found in the image" >&2
exit 127
