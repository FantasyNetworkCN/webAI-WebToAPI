#!/bin/sh
set -eu

password="${VNC_PASSWD:-change-me}"
mkdir -p /app/data
if command -v x11vnc >/dev/null 2>&1; then
  x11vnc -storepasswd "$password" /app/data/.vncpasswd >/dev/null
  chmod 600 /app/data/.vncpasswd
fi
exec /usr/bin/x11vnc -display :1 -rfbauth /app/data/.vncpasswd -rfbport 5910 \
  -forever -shared -noxdamage -repeat -xkb -listen 0.0.0.0
