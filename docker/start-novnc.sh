#!/bin/sh
set -eu

web_port="${NOVNC_PORT:-6082}"
vnc_port="${VNC_PORT:-5910}"
echo "noVNC listening on :${web_port}, proxying to VNC :${vnc_port}" >&2
exec /usr/bin/websockify --web=/usr/share/novnc "${web_port}" "127.0.0.1:${vnc_port}"
