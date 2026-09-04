# All-in-one Docker desktop

The root Dockerfile builds the Java application and packages it together with
Chromium, Xvfb, VNC, noVNC and supervisor in one image. It uses the same
virtual-desktop pattern as Snowluma, but does not depend on Snowluma or its
base image. Supervisor starts the regular (non-headless) Chromium window and
the Java API in the same container. Java reads Gemini cookies and `SNlM0e`
through Chromium's loopback CDP endpoint (`127.0.0.1:9222`).

Before starting, make sure the existing root `config.yml` contains a valid
Gemini `StreamGenerate` curl. Do not commit that file: it contains session
credentials.

```bash
VNC_PASSWD='a-strong-password' docker compose up -d --build
```

The Compose file assumes the HTTP proxy from `config.yml` is reachable on the
Docker host at port `7890`. Override it when needed, or disable it with
`PROXY_ENABLED=false`.

Open `http://localhost:6082` and complete the Google/Gemini login in the
visible Chromium window. The profile is persisted in the `webtoapi-data`
volume, so subsequent restarts are automatic. The API is
available at
`http://localhost:60000` (or the host port selected with `OPENAI_HOST_PORT`).

Port 9222 is not exposed; CDP is loopback-only. The noVNC and VNC ports are
exposed for initial login and maintenance, so put them behind an authenticated
reverse proxy if this host is not private.
