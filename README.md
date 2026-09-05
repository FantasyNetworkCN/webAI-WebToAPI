# webAI WebToAPI

这是一个单容器部署的 webAI Web API 网关。容器内包含：

- Java WebToAPI 服务
- 普通 Chromium（不是 headless）
- Xvfb 虚拟桌面
- VNC 和 noVNC
- supervisor 进程管理

Java 通过 Chromium 的本机 CDP 接口读取 Gemini Cookie 和 `SNlM0e`，然后直接请求 Gemini Web RPC。

## 前置条件

- Linux、macOS 或 Windows
- Docker Engine
- Docker Compose v2（命令为 `docker compose`）
- 能访问 Gemini 的网络环境；如果需要代理，准备一个 HTTP/SOCKS 代理

当前 Compose 使用独立的 bridge 网络，与 Snowluma 等其他容器隔离。
宿主机端口通过 Compose 映射，不会共享 Chrome、VNC 或 CDP 进程。

## 配置

### 1. 准备 `config.yml`

项目根目录需要有 `config.yml`。其中 `curl:` 必须粘贴一份从 Gemini 网页开发者工具复制的完整 `StreamGenerate` curl 请求。

配置文件包含登录 Cookie，不能提交到 Git 或公开分享。仓库已经通过 `.gitignore` 忽略根目录 `config.yml`。

### 2. 配置代理（可选）

也可以直接打开 API 首页的“代理设置”页修改代理，保存后立即生效并写入
`data/proxy-settings.properties`（Docker 中会持久化在 `webtoapi-data` volume）。
默认值为 `127.0.0.1:7890`。
保存后 Java 请求和 Chromium 都会自动使用新配置，浏览器会由 supervisor 自动重启。

Compose 默认使用 `127.0.0.1:7890`。在 bridge 网络中，这个地址指向容器自身；
如果代理运行在宿主机，请在网页中改为 `host.docker.internal`（或宿主机可从
Docker 访问的地址）。

没有代理时（Java 和 Chromium 都直连）：

```bash
PROXY_ENABLED=false docker compose up -d --build
```

使用其他代理时：

```bash
PROXY_TYPE=http PROXY_HOST=host.docker.internal PROXY_PORT=7890 \
  docker compose up -d --build
```

`PROXY_TYPE` 支持 `http` 和 `socks`。

启用宿主机代理时不要设置 `PROXY_ENABLED=false`。例如：

```bash
PROXY_ENABLED=true PROXY_HOST=host.docker.internal PROXY_PORT=7890 \
  docker compose up -d --build
```

该代理同时用于 Java 请求和 Chromium 浏览器。bridge 网络下，代理需要允许
来自 Docker 网关的连接；如果代理只能监听宿主机 `127.0.0.1`，请使用宿主机
转发端口，或继续使用 host 网络。

## 启动

在项目根目录执行：

```bash
VNC_PASSWD='修改成强密码' docker compose up -d --build
```

查看启动日志：

```bash
docker compose logs -f webtoapi
```

首次构建需要下载 Maven 依赖、Chromium 和桌面组件，可能需要几分钟。

## 首次登录 Gemini

浏览器打开后，通过 noVNC 访问：

```text
http://localhost:6082
```

在 Chromium 窗口中登录 Google/Gemini。登录状态保存在 Docker volume `webtoapi-data` 中，容器重启或升级后会继续使用。

如果不使用网页 noVNC，也可以使用 VNC 客户端连接：

```text
地址：localhost:5910
密码：启动时设置的 VNC_PASSWD
```

## API 使用

默认 API 地址：

```text
http://localhost:60000
```

检查模型列表：

```bash
curl http://localhost:60000/v1/models
```

发送 OpenAI 兼容聊天请求：

```bash
curl http://localhost:60000/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "gemini-3.6-Flash",
    "messages": [
      {"role": "user", "content": "你好，请介绍一下你自己。"}
    ],
    "stream": false
  }'
```

可以通过环境变量选择宿主机空闲端口，例如：

```bash
OPENAI_HOST_PORT=16000 NOVNC_PORT=16082 VNC_PORT=15910 \
  docker compose up -d --build
```

## 常用命令

```bash
# 查看服务状态
docker compose ps

# 查看实时日志
docker compose logs -f webtoapi

# 停止容器（保留登录状态和日志）
docker compose down

# 重新构建并启动
docker compose up -d --build
```

删除登录状态时才执行：

```bash
docker compose down -v
```

这会删除 `webtoapi-data` 和 `webtoapi-logs`，包括 Chromium 登录 profile 和保存的日志，无法通过 Compose 自动恢复。

## 端口

| 端口    | 用途 |
|---------| --- |
| `60000` | OpenAI 兼容 API |
| `6082`  | noVNC 网页 |
| `5910`  | VNC 服务 |
| `19222` | Chromium CDP，仅本项目使用，不对外暴露 |

不要把 `6082`、`5910` 暴露到不可信公网；登录完成后建议通过防火墙或反向代理限制访问。

## 故障排查

### noVNC 打不开

```bash
docker compose ps
docker compose logs webtoapi | tail -100
```

确认容器状态为 `Up`，并确认宿主机的 `6082` 端口没有被其他程序占用。

如果打开后仍显示文件列表，使用新镜像重新构建：

```bash
docker compose build --no-cache
docker compose up -d
```

### Gemini 请求失败或提示未登录

通过 noVNC 检查 Chromium 是否仍登录 Gemini。Cookie 失效时重新登录；不要只复制旧 Cookie 到公开环境。

如果 VNC 桌面为空，查看 Chromium 和桌面进程日志：

```bash
docker compose logs webtoapi | grep -i -E 'chrom|xvfb|supervisor'
```

容器启动脚本会自动修复数据 volume 权限，Chromium profile 保存在
`webtoapi-data` volume 中。镜像会自动探测 Chromium 的实际安装路径，避免
不同 Debian 架构下 `/usr/bin/chromium` 路径不一致。

### 代理连接失败

bridge 网络下，容器内的 `127.0.0.1` 是容器自身。宿主机代理应配置为：

```bash
PROXY_ENABLED=true PROXY_HOST=host.docker.internal PROXY_PORT=7890 docker compose up -d
```

### API 没有启动

```bash
docker compose logs webtoapi
```

重点检查 `config.yml` 是否存在，以及 `curl:` 后是否包含完整有效的 `StreamGenerate` 请求。
