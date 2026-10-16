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

源码、Java 类或前端页面更新后，必须重新构建镜像；单独执行 `docker compose up -d`
会复用旧镜像，不会自动编译最新源码：

```bash
sudo docker compose build --no-cache webtoapi
sudo docker compose up -d --force-recreate webtoapi
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

### ChatGPT 网页端模型

项目也实现了文章 `111.html` 中描述的 ChatGPT 网页端链路。打开首页的
“ChatGPT Curl”页，粘贴 DevTools 复制的完整
`https://chatgpt.com/backend-api/f/conversation` curl，服务端会提取并保存 URL、Header、Cookie 和 JSON body 到 SQLite。模型会以
`chatgpt-web` 出现在 `/v1/models`。请求时使用 `"model": "chatgpt-web"`。

保存的 curl 只作为登录上下文和请求模板。每一轮请求都会先访问首页并生成新的
requirements/PoW/Turnstile（如需要）和 conduit token；保存 curl 中旧的
`openai-sentinel-*`、`x-conduit-token` 不会被重放。若浏览器会话本身过期或 ChatGPT
仍返回 401/403，请在同一浏览器和网络中重新复制最新 conversation curl 后保存。

#### 文本完整性与失败判定

ChatGPT 网页流按增量下发正文（`{"p":"/message/content/parts/0","o":"append","v":"..."}`
以及省略路径的 `{"v":"..."}`），结束时给出 `[DONE]` 与 `message_stream_complete`
（通常伴随 `/message/status = finished_successfully`）。服务端据此处理：

- 裸 `{"v":"..."}` 增量按正文追加，不再被当成控制帧丢弃。
- 空白增量（Markdown 空行、代码缩进）会原样转发，避免流式输出比实际回答少内容。
- `append` 按增量语义直接拼接，不做“尾部重复即去重”的猜测，否则模型连续输出相同
  片段（`好的好的`、代码里连续的 `}` 行、Markdown 表格重复行）会被静默吃掉。
- 有正文但未收到结束标记就断流：按失败上报，不再把半截回答当成功返回。需要临时
  恢复旧的宽松行为时加 `-Dchatgpt.strictTermination=false`。
- 加 `-DdumpRaw=/tmp/gpt-raw.txt` 可把上游原始 SSE 落盘，用于核对真实事件序列。

注意：Pro / 长思考模式的回复可能通过 `stream_handoff` 把后半段交给 WebSocket，
当前实现只读 HTTP SSE；这种情况会明确报“提前结束”，而不是返回半截回答。

可以用本地刚复制的 curl 做一次 live smoke test（不会把 curl 写入仓库）：

```bash
rm -rf /tmp/webtoapi-classes && mkdir -p /tmp/webtoapi-classes
javac -cp "$(find ~/.m2/repository -name '*.jar' -printf '%p:' 2>/dev/null)" \
  -d /tmp/webtoapi-classes src/main/java/*.java test/*.java
java -cp "/tmp/webtoapi-classes:$(find ~/.m2/repository -name '*.jar' -printf '%p:' 2>/dev/null)" \
  ChatGptCurlLiveTest /path/to/fresh-conversation-curl.txt
```

测试只输出响应字符数；不要把 curl 提交到 Git 或日志。`ChatGptTokenTest` 是不联网的
dx VM 回归测试。

可以通过环境变量选择宿主机空闲端口，例如：

```bash
OPENAI_HOST_PORT=16000 NOVNC_PORT=16082 VNC_PORT=15910 \
  docker compose up -d --build
```

### DeepSeek 网页端模型

项目也实现了 chat.deepseek.com 网页端链路。打开首页的“DeepSeek Curl”页，粘贴
DevTools 复制的 `https://chat.deepseek.com/api/v0/chat/completion` 完整 curl（含
`authorization: Bearer …` 与 `cookie`），服务端会提取 URL、Header 和 body 并存入
`data/deepseek-curls.sqlite`。模型会以 `deepseek-web` 与 `deepseek-reasoner` 出现在
`/v1/models`。

- `deepseek-web`：`model_type=default`，关闭思考，等价于网页快速模式。
- `deepseek-reasoner`：`model_type=expert`，开启 `thinking_enabled`。思维链（THINK
  片段）会作为 `reasoning_content` 返回：`/v1/chat/completions` 的流式 chunk 与
  非流式 message 都带该字段；`/v1/responses` 会输出
  `response.reasoning_text.delta` / `.done` 事件，并在 `output` 里附带一个
  `reasoning` 条目。支持 `reasoning_content` 的客户端（OpenWebUI、LobeChat、
  Cherry Studio、Chatbox 等）会自动折叠显示思考过程。

图片（`image_url` / `input_image`）也支持：服务端会走 DeepSeek 的
`/api/v0/file/upload_file`（每次上传单独解一次 PoW），拿到文件 id 后放进 completion
的 `ref_file_ids`。单张图片上传失败会被跳过并提示模型不要臆测，不影响文字回答。

每次业务请求前，服务端都会先调用 `/api/v0/chat/create_pow_challenge` 取回一次性
挑战，在本地用 DeepSeekHashV1 求解后把答案放进 `X-DS-PoW-Response` 头。挑战带随机
salt 与有效期且一次性消费，无法复用，因此不需要（也不应该）重放旧的
`x-ds-pow-response`；保存的 curl 只作为登录上下文和请求模板。多轮对话会复用同一个
DeepSeek 会话，并按 `response_message_id` 串接 `parent_message_id`。

`DeepSeekHashV1` 为纯 Java 实现（`src/main/java/DeepSeekHash.java`）：Keccak-f[1600]
海绵，rate=136 字节、输出 256 bit，填充与 SHA3-256 相同（0x06 + 0x80），但置换只跑
23 轮并使用 `RC[1..23]`（跳过 `RC[0]`），等价于“SHA3-256 少跑一轮”。自检向量：

```text
H("")    = e594808bc5b7151ac160c6d39a02e0a8e261ed588578403099e3561dc40c26b3
H("abc") = f841106c601ce9be9bc38525e90d4178d47f21dd8eb9f238fc55ffaa4ca94506
```

若 DeepSeek 返回 401/403 或 `biz_data` 为空（登录过期），请在同一浏览器/网络中重新
复制最新 completion curl 后保存。

#### 失败判定与排查

上游生成结束时会下发 `response/status`（`FINISHED` / `INCOMPLETE`）或
`quasi_status`，发完随即断开连接。服务端以它作为“回答结束”的判据，不再把流正常
关闭当成生成完成：

- `INCOMPLETE`、`CONTENT_FILTER` 等终态：按失败上报，并附上上游 toast 提示。
- 有正文但未收到终态就断流（网络中断、风控、读取超时）：按失败上报，不再把半截
  回答当作成功返回；已输出的部分仍会保留在流里。
- 上游正常结束但正文为空：报错并附带 `status` 与原始响应，便于定位风控静默。
- 已经输出过内容时不再轮换到下一份 curl 重试，避免重复/错乱输出掩盖真实原因；
  失败时创建的会话也会回滚，避免换账号后复用到其他账号的会话。

前端 DeepSeek Curl 页的 `last_error` 会显示最后一次失败原因。需要看上游原始事件
序列时，加 `-DdumpRaw=/tmp/ds-raw.txt` 把响应落到文件；需要临时恢复旧的宽松行为时
加 `-Ddeepseek.strictTermination=false`（断流按已收到内容返回）。

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
