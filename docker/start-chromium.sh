#!/bin/sh
set -eu

proxy_enabled="${PROXY_ENABLED:-true}"
proxy_type="${PROXY_TYPE:-http}"
proxy_host="${PROXY_HOST:-127.0.0.1}"
proxy_port="${PROXY_PORT:-7890}"
settings_file="/app/data/proxy-settings.properties"
if [ -f "$settings_file" ]; then
  read_setting() {
    awk -F= -v key="$1" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$settings_file"
  }
  value="$(read_setting enabled)"
  [ -n "$value" ] && proxy_enabled="$value"
  value="$(read_setting type)"
  [ -n "$value" ] && proxy_type="$value"
  value="$(read_setting host)"
  [ -n "$value" ] && proxy_host="$value"
  value="$(read_setting port)"
  [ -n "$value" ] && proxy_port="$value"
fi

if [ "$proxy_enabled" = "true" ]; then
  case "$proxy_type" in
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
