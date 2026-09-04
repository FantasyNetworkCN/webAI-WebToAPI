#!/bin/sh
set -eu

if [ "${PROXY_ENABLED:-true}" = "true" ]; then
  proxy_host="${PROXY_HOST:-host.docker.internal}"
  proxy_port="${PROXY_PORT:-7890}"
  case "${PROXY_TYPE:-http}" in
    socks|socks5)
      proxy_url="socks5://${proxy_host}:${proxy_port}"
      ;;
    *)
      proxy_url="http://${proxy_host}:${proxy_port}"
      ;;
  esac
  set -- "--proxy-server=${proxy_url}" "$@"
  echo "Chromium proxy: ${proxy_url}" >&2
fi

# Keep this container's CDP endpoint separate when host networking is used.
set -- "--remote-debugging-port=${GEMINI_COOKIE_DEBUG_PORT:-19222}" "$@"

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
