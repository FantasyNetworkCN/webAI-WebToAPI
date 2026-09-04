#!/bin/sh
set -eu

# Named volumes are mounted after image build and commonly start as root-owned.
# Chromium needs to write its profile and Java needs to write request logs.
mkdir -p /app/data /app/logs
chown -R webtoapi:webtoapi /app/data /app/logs

exec /usr/bin/supervisord -n -c /etc/supervisor/supervisord.conf
