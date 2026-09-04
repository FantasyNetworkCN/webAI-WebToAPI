#!/bin/sh
set -eu

# Openbox may daemonize and return immediately. Keep a supervisor-owned
# foreground process so it does not repeatedly spawn duplicate window managers.
/usr/bin/openbox --replace || true
exec /usr/bin/sleep infinity
