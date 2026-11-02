import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Runs a Doubao web request inside the already-running Chromium page.
 *
 * <p>The request therefore uses the browser's real JavaScript runtime, document, TLS stack,
 * Canvas/WebGL/audio probes, client hints and cookie jar. The copied curl remains the source of
 * login cookies, custom headers and the JSON template; only the transport moves into Chromium.</p>
 */
final class DoubaoBrowser {
    private static final String BINDING_PREFIX = "__webtoapiDoubaoEmit_";
    private static final String ABORT_PREFIX = "__webtoapiDoubaoAbort_";
    private static final String HOOK_PREFIX = "__webtoapiDoubaoHook_";
    private static final String COMPLETION_PATH = "/chat/completion";
    private static final long UI_START_TIMEOUT_MS = 20_000;
    private static final MediaType EMPTY_MEDIA_TYPE = MediaType.get("text/plain; charset=utf-8");
    /** One tab cannot safely carry two concurrent bindings/fetches. */
    private static final ReentrantLock PAGE_LOCK = new ReentrantLock(true);

    private final OkHttpClient client;
    private final String host;
    private final int port;
    private final String pageUrl;
    private final int timeoutMs;

    DoubaoBrowser(OkHttpClient client, String host, int port, String pageUrl, int timeoutMs) {
        this.client = client;
        this.host = host;
        this.port = port;
        this.pageUrl = pageUrl == null || pageUrl.isBlank() ? "https://www.doubao.com/chat/" : pageUrl;
        this.timeoutMs = timeoutMs <= 0 ? 30_000 : timeoutMs;
    }

    /**
     * Starts a fetch and returns its response body as a live stream. Setup problems (CDP is
     * unavailable, the tab cannot be created/navigated, or the binding cannot be installed) are
     * reported as {@link UnavailableException}; HTTP and network failures during the fetch are
     * delivered while the returned stream is read.
     */
    InputStream open(HttpUrl url, Map<String, String> headers, String body) throws IOException {
        PAGE_LOCK.lock();
        CdpSession cdp = null;
        BrowserStream stream = null;
        try {
            String suffix = UUID.randomUUID().toString().replace("-", "");
            String binding = BINDING_PREFIX + suffix;
            String abortName = ABORT_PREFIX + suffix;
            cdp = new CdpSession(client, pageWebSocketUrl(), timeoutMs);
            setCookies(cdp, header(headers, "cookie"));
            ensureDoubaoDocument(cdp);
            cdp.call("Runtime.addBinding", Map.of("name", binding));

            stream = new BrowserStream(cdp, PAGE_LOCK, binding, abortName);
            cdp.setEventHandler(stream::onBindingEvent);
            cdp.setDisconnectHandler(stream::failTransport);
            Map<String, Object> evaluation = cdp.call("Runtime.evaluate", Map.of(
                    "expression", requestScript(binding, abortName, url, headers, body),
                    "awaitPromise", false,
                    "returnByValue", true));
            if (map(evaluation.get("exceptionDetails")) != null) {
                throw new UnavailableException("无法在豆包页面中启动浏览器请求："
                        + preview(string(map(evaluation.get("exceptionDetails")).get("text"))));
            }
            return stream;
        } catch (IOException | RuntimeException e) {
            if (stream != null) {
                stream.close();
            } else if (cdp != null) {
                cdp.close();
                PAGE_LOCK.unlock();
            } else {
                PAGE_LOCK.unlock();
            }
            if (e instanceof UnavailableException unavailable) {
                throw unavailable;
            }
            throw new UnavailableException("豆包浏览器环境不可用：" + message(e), e);
        }
    }

    /**
     * Sends {@code prompt} through the visible Doubao editor and lets the page itself issue the
     * completion request.
     *
     * <p>This is different from {@link #open}: the page generates its own {@code a_bogus},
     * {@code msToken}, CSRF headers and device context, exactly like a person pressing Send in
     * VNC. The page's XHR is observed in place; if the page uses {@code fetch}, CDP Fetch
     * interception takes over without blocking the page's JavaScript.</p>
     */
    InputStream openUi(String prompt) throws IOException {
        PAGE_LOCK.lock();
        CdpSession cdp = null;
        UiCaptureStream stream = null;
        try {
            String suffix = UUID.randomUUID().toString().replace("-", "");
            String binding = BINDING_PREFIX + suffix;
            String hookName = HOOK_PREFIX + suffix;
            cdp = new CdpSession(client, pageWebSocketUrl(), timeoutMs);
            ensureDoubaoDocument(cdp);
            ensureChatUi(cdp);
            cdp.call("Page.bringToFront", Map.of());
            cdp.call("Runtime.addBinding", Map.of("name", binding));

            stream = new UiCaptureStream(cdp, PAGE_LOCK, binding, hookName);
            cdp.setEventHandler(stream::onCdpEvent);
            cdp.setDisconnectHandler(stream::failTransport);
            cdp.call("Fetch.enable", Map.of("patterns", List.of(Map.of(
                    "urlPattern", "*" + COMPLETION_PATH + "*",
                    "requestStage", "Response"))));
            Map<String, Object> installed = cdp.call("Runtime.evaluate", Map.of(
                    "expression", uiHookScript(binding, hookName),
                    "returnByValue", true,
                    "awaitPromise", false));
            Map<?, ?> hookError = map(installed.get("exceptionDetails"));
            if (hookError != null) {
                throw new UnavailableException("无法在豆包页面中安装发送捕获器："
                        + preview(exceptionText(hookError)));
            }

            typePrompt(cdp, prompt);
            clickSend(cdp);
            if (!stream.awaitStarted(UI_START_TIMEOUT_MS)) {
                throw new IOException("已在豆包页面点击发送，但没有捕获到 /chat/completion 请求。"
                        + "请检查 VNC 中的豆包页面是否仍处于登录状态，或页面是否弹出了验证窗口。");
            }
            return stream;
        } catch (IOException | RuntimeException e) {
            if (stream != null) {
                stream.close();
            } else {
                if (cdp != null) {
                    cdp.close();
                }
                PAGE_LOCK.unlock();
            }
            if (e instanceof UnavailableException unavailable) {
                throw unavailable;
            }
            if (e instanceof IOException io) {
                throw io;
            }
            throw new UnavailableException("豆包页面发送失败：" + message(e), e);
        }
    }

    private void ensureChatUi(CdpSession cdp) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        boolean navigated = false;
        String last = "";
        while (System.nanoTime() < deadline) {
            String href = evaluateString(cdp, "location.href");
            last = href;
            if (href.toLowerCase(Locale.ROOT).contains("login")) {
                throw new UnavailableException("豆包页面当前在登录页。请先在 VNC 浏览器中登录 www.doubao.com。");
            }
            boolean ready = "true".equals(evaluateString(cdp,
                    "String(!!document.querySelector('[data-testid=\"chat_input_input\"]'))"));
            if (ready) {
                return;
            }
            if (!navigated && !samePath(href, pageUrl)) {
                navigated = true;
                Map<String, Object> result = cdp.call("Page.navigate", Map.of("url", pageUrl));
                String error = string(result.get("errorText"));
                if (!error.isBlank()) {
                    throw new UnavailableException("Chromium 打开豆包聊天页失败：" + error);
                }
            }
            sleepQuietly(200);
        }
        throw new UnavailableException("没有找到豆包聊天输入框（当前页面：" + preview(last)
                + "）。请先在 VNC 浏览器中打开并登录 www.doubao.com/chat/。");
    }

    /**
     * Brings the Doubao tab to the front so a risk-control slider or security prompt is visible
     * in VNC. This does not attempt to solve the challenge.
     */
    void showVerification() {
        PAGE_LOCK.lock();
        try (CdpSession cdp = new CdpSession(client, pageWebSocketUrl(), timeoutMs)) {
            cdp.call("Page.bringToFront", Map.of());
            cdp.call("Runtime.evaluate", Map.of(
                    "expression", """
                            (() => {
                              try { window.scrollTo(0, document.body ? document.body.scrollHeight : 0); } catch (_) {}
                              const nodes = document.querySelectorAll(
                                '[class*="captcha"], [class*="verify"], [class*="slide"], iframe, [role="dialog"]');
                              for (const node of nodes) {
                                try {
                                  const text = (node.innerText || node.getAttribute('title') || '');
                                  if (node.tagName === 'IFRAME' || /验证|滑块|captcha|verify/i.test(text)) {
                                    node.scrollIntoView({block: 'center'});
                                    break;
                                  }
                                } catch (_) {}
                              }
                              return true;
                            })()
                            """,
                    "returnByValue", true));
        } catch (IOException | RuntimeException e) {
            System.err.println("无法把豆包验证页面切到前台：" + message(e));
        } finally {
            PAGE_LOCK.unlock();
        }
    }

    private String pageWebSocketUrl() throws IOException {
        String base = "http://" + host + ":" + port;
        String pageHost = hostOf(pageUrl);
        String ws = findPageWebSocket(base, pageHost);
        if (!ws.isBlank()) {
            return ws;
        }

        String encoded = URLEncoder.encode(pageUrl, StandardCharsets.UTF_8);
        Request put = new Request.Builder()
                .url(base + "/json/new?" + encoded)
                .put(RequestBody.create(new byte[0], EMPTY_MEDIA_TYPE))
                .build();
        try (Response response = client.newCall(put).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                ws = webSocketFromTarget(response.body().string());
                if (!ws.isBlank()) {
                    return ws;
                }
            }
        } catch (IOException ignored) {
            // Some Chromium builds/versions only accept the legacy GET endpoint.
        }
        try {
            Request get = new Request.Builder().url(base + "/json/new?" + encoded).get().build();
            try (Response response = client.newCall(get).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    ws = webSocketFromTarget(response.body().string());
                    if (!ws.isBlank()) {
                        return ws;
                    }
                }
            }
        } catch (IOException ignored) {
            // Report a stable setup error below.
        }
        throw new UnavailableException("无法在 Chromium 中创建豆包标签页（CDP " + host + ":" + port
                + "）。请确认容器内 Chromium 正在运行。");
    }

    private String findPageWebSocket(String base, String pageHost) throws IOException {
        Request request = new Request.Builder().url(base + "/json/list").get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new UnavailableException("Chromium CDP 不可用：http://" + host + ":" + port + "/json/list");
            }
            Object parsed = SimpleJson.parse(response.body().string());
            if (!(parsed instanceof List<?> targets)) {
                return "";
            }
            String best = "";
            int bestScore = Integer.MIN_VALUE;
            for (Object item : targets) {
                if (!(item instanceof Map<?, ?> target)) {
                    continue;
                }
                String type = string(target.get("type"));
                String targetUrl = string(target.get("url"));
                String ws = string(target.get("webSocketDebuggerUrl"));
                if (!"page".equals(type) || ws.isBlank() || !sameHost(targetUrl, pageHost)) {
                    continue;
                }
                int score = pageScore(targetUrl);
                if (score > bestScore) {
                    bestScore = score;
                    best = ws;
                }
            }
            return best;
        } catch (UnavailableException e) {
            throw e;
        } catch (IOException e) {
            throw new UnavailableException("连接 Chromium CDP 失败（" + host + ":" + port + "）："
                    + message(e), e);
        }
    }

    private int pageScore(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        int score = 1;
        if (lower.contains("/chat")) {
            score += 20;
        }
        if (lower.equalsIgnoreCase(pageUrl)) {
            score += 10;
        }
        if (lower.contains("login")) {
            score -= 30;
        }
        return score;
    }

    private static String webSocketFromTarget(String json) throws IOException {
        Object parsed = SimpleJson.parse(json);
        if (!(parsed instanceof Map<?, ?> map)) {
            return "";
        }
        return string(map.get("webSocketDebuggerUrl"));
    }

    private void ensureDoubaoDocument(CdpSession cdp) throws IOException {
        String pageHost = hostOf(pageUrl);
        String href = evaluateString(cdp, "location.href");
        boolean navigated = false;
        if (!sameHost(href, pageHost)) {
            Map<String, Object> result = cdp.call("Page.navigate", Map.of("url", pageUrl));
            String error = string(result.get("errorText"));
            if (!error.isBlank()) {
                throw new UnavailableException("Chromium 打开豆包页面失败：" + error);
            }
            navigated = true;
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        String last = "";
        while (System.nanoTime() < deadline) {
            String state = evaluateString(cdp,
                    "location.href + '\\n' + document.readyState + '\\n' + navigator.userAgent");
            String[] lines = state.split("\\n", 3);
            last = state;
            if (lines.length >= 2 && sameHost(lines[0], pageHost) && "complete".equals(lines[1])) {
                if (navigated) {
                    // Give lazily loaded ByteDance fetch/signature hooks a moment to install.
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("等待豆包页面脚本初始化被中断", e);
                    }
                }
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("等待豆包页面加载被中断", e);
            }
        }
        throw new UnavailableException("等待豆包页面加载超时：" + preview(last));
    }

    private void setCookies(CdpSession cdp, String cookieHeader) throws IOException {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return;
        }
        String origin = originOf(pageUrl);
        ArrayList<Map<String, Object>> cookies = new ArrayList<>();
        for (String part : cookieHeader.split(";")) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = part.substring(0, equals).trim();
            String value = part.substring(equals + 1).trim();
            if (name.isEmpty() || name.startsWith("$")) {
                continue;
            }
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            LinkedHashMap<String, Object> cookie = new LinkedHashMap<>();
            cookie.put("name", name);
            cookie.put("value", value);
            cookie.put("url", origin);
            cookies.add(cookie);
        }
        if (!cookies.isEmpty()) {
            cdp.call("Network.setCookies", Map.of("cookies", cookies));
        }
    }

    private static String requestScript(String binding, String abortName, HttpUrl url,
                                        Map<String, String> headers, String body) {
        String headerJson = json(browserHeaders(headers));
        return """
                (() => {
                  const emit = globalThis.%s;
                  if (typeof emit !== "function") throw new Error("Doubao browser binding is missing");
                  const requestUrl = %s;
                  const requestHeaders = %s;
                  const requestBody = %s;
                  const abortController = new AbortController();
                  globalThis["%s"] = abortController;
                  void (async () => {
                    try {
                      const response = await fetch(requestUrl, {
                        method: "POST",
                        headers: requestHeaders,
                        body: requestBody,
                        credentials: "include",
                        cache: "no-store",
                        redirect: "follow",
                        signal: abortController.signal
                      });
                      if (!response.ok) {
                        const text = await response.text();
                        emit(JSON.stringify({type: "error", status: response.status,
                          message: String(text || "").slice(0, 20000)}));
                        return;
                      }
                      if (!response.body) {
                        const text = await response.text();
                        if (text) emit(JSON.stringify({type: "chunk", data: text}));
                        emit(JSON.stringify({type: "end"}));
                        return;
                      }
                      const reader = response.body.getReader();
                      const decoder = new TextDecoder("utf-8");
                      while (true) {
                        const item = await reader.read();
                        if (item.done) break;
                        if (item.value && item.value.length) {
                          const text = decoder.decode(item.value, {stream: true});
                          if (text) emit(JSON.stringify({type: "chunk", data: text}));
                        }
                      }
                      const tail = decoder.decode();
                      if (tail) emit(JSON.stringify({type: "chunk", data: tail}));
                      emit(JSON.stringify({type: "end"}));
                    } catch (error) {
                      emit(JSON.stringify({type: "error",
                        message: String(error && (error.stack || error.message) || error)}));
                    } finally {
                      try { delete globalThis["%s"]; } catch (_) {}
                    }
                  })();
                })()
                """.formatted(binding, SimpleJson.quote(url.toString()), headerJson, SimpleJson.quote(body),
                abortName, abortName);
    }

    private static Map<String, String> browserHeaders(Map<String, String> headers) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String lower = entry.getKey().toLowerCase(Locale.ROOT);
            if (lower.equals("host") || lower.equals("content-length") || lower.equals("cookie")
                    || lower.equals("connection") || lower.equals("accept-encoding")
                    || lower.equals("user-agent") || lower.equals("origin") || lower.equals("referer")
                    || lower.equals("accept-language") || lower.equals("priority")
                    || lower.startsWith("sec-") || lower.startsWith("proxy-")) {
                continue;
            }
            out.put(entry.getKey(), entry.getValue());
        }
        out.put("content-type", "application/json");
        out.putIfAbsent("accept", "*/*");
        return out;
    }

    private static String uiHookScript(String binding, String hookName) {
        return """
                (() => {
                  const emit = globalThis.%s;
                  if (typeof emit !== "function") throw new Error("Doubao browser binding is missing");
                  const previous = globalThis[%s];
                  if (previous && typeof previous.uninstall === "function") {
                    try { previous.uninstall(); } catch (_) {}
                  }
                  const matches = (value) => {
                    try {
                      return new URL(String(value), location.href).pathname.indexOf("/chat/completion") >= 0;
                    } catch (_) {
                      return String(value).indexOf("/chat/completion") >= 0;
                    }
                  };
                  const send = (payload) => {
                    try { emit(JSON.stringify(payload)); } catch (_) {}
                  };
                  const XHR = globalThis.XMLHttpRequest;
                  if (!XHR || !XHR.prototype) throw new Error("XMLHttpRequest is unavailable");
                  const proto = XHR.prototype;
                  const originalOpen = proto.open;
                  const originalSend = proto.send;
                  const active = new Set();
                  let captured = false;
                  let ended = false;

                  proto.open = function(method, url) {
                    try {
                      this.__webtoapiDoubaoUrl = String(url);
                      this.__webtoapiDoubaoMethod = String(method || "GET");
                    } catch (_) {}
                    return originalOpen.apply(this, arguments);
                  };

                  proto.send = function(body) {
                    const url = this.__webtoapiDoubaoUrl || "";
                    if (captured || !matches(url)) return originalSend.apply(this, arguments);
                    const responseType = String(this.responseType || "");
                    if (responseType !== "" && responseType !== "text") {
                      return originalSend.apply(this, arguments);
                    }

                    captured = true;
                    const xhr = this;
                    active.add(xhr);
                    let offset = 0;
                    let failed = false;

                    const cleanup = () => {
                      try { xhr.removeEventListener("progress", onProgress); } catch (_) {}
                      try { xhr.removeEventListener("readystatechange", onState); } catch (_) {}
                      try { xhr.removeEventListener("error", onError); } catch (_) {}
                      try { xhr.removeEventListener("abort", onError); } catch (_) {}
                      try { xhr.removeEventListener("timeout", onError); } catch (_) {}
                    };
                    const readDelta = () => {
                      if (failed || xhr.readyState < 3) return;
                      let text = "";
                      try { text = xhr.responseText || ""; } catch (_) { return; }
                      if (text.length > offset) {
                        send({type: "chunk", data: text.slice(offset)});
                        offset = text.length;
                      }
                    };
                    const onProgress = () => readDelta();
                    const onError = () => {
                      if (ended) return;
                      ended = true;
                      failed = true;
                      active.delete(xhr);
                      send({type: "error", status: 0, message: "豆包页面 XHR 请求失败"});
                      cleanup();
                    };
                    const onState = () => {
                      readDelta();
                      if (xhr.readyState !== 4) return;
                      active.delete(xhr);
                      if (ended) return;
                      ended = true;
                      const status = Number(xhr.status) || 0;
                      let text = "";
                      try { text = xhr.responseText || ""; } catch (_) {}
                      if (status >= 400 || status === 0) {
                        failed = true;
                        send({type: "error", status: status,
                          message: text.slice(0, 20000) || ("HTTP " + status)});
                      } else {
                        send({type: "end"});
                      }
                      cleanup();
                    };

                    xhr.addEventListener("progress", onProgress);
                    xhr.addEventListener("readystatechange", onState);
                    xhr.addEventListener("error", onError);
                    xhr.addEventListener("abort", onError);
                    xhr.addEventListener("timeout", onError);
                    send({type: "start", transport: "xhr", url: url});
                    return originalSend.apply(this, arguments);
                  };

                  globalThis[%s] = {
                    uninstall: () => {
                      try { proto.open = originalOpen; } catch (_) {}
                      try { proto.send = originalSend; } catch (_) {}
                      for (const xhr of active) {
                        try { xhr.abort(); } catch (_) {}
                      }
                      active.clear();
                    }
                  };
                })()
                """.formatted(binding, hookName, hookName);
    }

    private static void typePrompt(CdpSession cdp, String prompt) throws IOException {
        Map<String, Object> input = evaluateObject(cdp, """
                (() => {
                  const root = document.querySelector('[data-testid="chat_input_input"]');
                  const el = root ? (root.matches('textarea,input,[contenteditable="true"],[contenteditable="plaintext-only"]')
                    ? root : root.querySelector('textarea,input,[contenteditable="true"],[contenteditable="plaintext-only"]')) : null;
                  if (!el) return {ok: false};
                  el.scrollIntoView({block: 'center', inline: 'center'});
                  const rect = el.getBoundingClientRect();
                  return {ok: true, x: rect.left + rect.width / 2, y: rect.top + rect.height / 2,
                          tag: el.tagName};
                })()
                """);
        if (input == null || !Boolean.TRUE.equals(input.get("ok"))) {
            throw new UnavailableException("没有找到豆包聊天输入框。请先在 VNC 中打开并登录豆包聊天页。");
        }
        double x = number(input.get("x"));
        double y = number(input.get("y"));
        if (x <= 0 || y <= 0) {
            throw new UnavailableException("豆包聊天输入框不可见，无法发送消息。");
        }

        clickAt(cdp, x, y);
        selectEditorContents(cdp);
        cdp.call("Input.insertText", Map.of("text", prompt == null ? "" : prompt));

        String expected = prompt == null ? "" : prompt.trim();
        String head = expected.length() <= 48 ? expected : expected.substring(0, 48);
        String current = editorText(cdp);
        if (!head.isEmpty() && !current.contains(head)) {
            setPromptByScript(cdp, prompt == null ? "" : prompt);
            current = editorText(cdp);
            if (!head.isEmpty() && !current.contains(head)) {
                throw new UnavailableException("无法把提示词写入豆包输入框（当前长度="
                        + current.length() + "）。请检查 VNC 中的豆包页面是否已登录。");
            }
        }
    }

    private static void selectEditorContents(CdpSession cdp) throws IOException {
        evaluateString(cdp, """
                (() => {
                  const root = document.querySelector('[data-testid="chat_input_input"]');
                  const el = root ? (root.matches('textarea,input,[contenteditable="true"],[contenteditable="plaintext-only"]')
                    ? root : root.querySelector('textarea,input,[contenteditable="true"],[contenteditable="plaintext-only"]')) : null;
                  if (!el) return '';
                  el.focus();
                  if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
                    el.select();
                  } else {
                    const range = document.createRange();
                    range.selectNodeContents(el);
                    const selection = getSelection();
                    selection.removeAllRanges();
                    selection.addRange(range);
                  }
                  return 'ok';
                })()
                """);
    }

    private static String editorText(CdpSession cdp) throws IOException {
        return evaluateString(cdp, """
                (() => {
                  const root = document.querySelector('[data-testid="chat_input_input"]');
                  const el = root ? (root.matches('textarea,input,[contenteditable="true"],[contenteditable="plaintext-only"]')
                    ? root : root.querySelector('textarea,input,[contenteditable="true"],[contenteditable="plaintext-only"]')) : null;
                  if (!el) return '';
                  return String(el.value !== undefined ? el.value : (el.innerText || el.textContent || ''));
                })()
                """);
    }

    private static void setPromptByScript(CdpSession cdp, String prompt) throws IOException {
        String expression = """
                (() => {
                  const root = document.querySelector('[data-testid="chat_input_input"]');
                  const el = root ? (root.matches('textarea,input,[contenteditable="true"],[contenteditable="plaintext-only"]')
                    ? root : root.querySelector('textarea,input,[contenteditable="true"],[contenteditable="plaintext-only"]')) : null;
                  if (!el) return false;
                  el.focus();
                  if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
                    const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                    const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                    setter.call(el, %s);
                  } else {
                    const range = document.createRange();
                    range.selectNodeContents(el);
                    const selection = getSelection();
                    selection.removeAllRanges();
                    selection.addRange(range);
                    document.execCommand('insertText', false, %s);
                  }
                  el.dispatchEvent(new InputEvent('input', {bubbles: true, inputType: 'insertText', data: %s}));
                  return true;
                })()
                """.formatted(SimpleJson.quote(prompt), SimpleJson.quote(prompt), SimpleJson.quote(prompt));
        Map<String, Object> response = cdp.call("Runtime.evaluate", Map.of(
                "expression", expression, "returnByValue", true, "awaitPromise", true));
        Map<?, ?> details = map(response.get("exceptionDetails"));
        if (details != null) {
            throw new IOException("豆包输入框脚本执行失败：" + preview(exceptionText(details)));
        }
    }

    private static void clickSend(CdpSession cdp) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Map<String, Object> button = evaluateObject(cdp, """
                    (() => {
                      const button = document.querySelector('[data-testid="chat_input_send_button"]');
                      if (!button) return {found: false};
                      button.scrollIntoView({block: 'nearest'});
                      const rect = button.getBoundingClientRect();
                      const classes = typeof button.className === 'string' ? button.className : '';
                      return {found: true, x: rect.left + rect.width / 2, y: rect.top + rect.height / 2,
                              disabled: !!button.disabled
                                || button.getAttribute('aria-disabled') === 'true'
                                || /disabled/i.test(classes),
                              label: button.getAttribute('aria-label') || ''};
                    })()
                    """);
            if (button != null && Boolean.TRUE.equals(button.get("found"))) {
                double x = number(button.get("x"));
                double y = number(button.get("y"));
                if (!Boolean.TRUE.equals(button.get("disabled")) && x > 0 && y > 0) {
                    clickAt(cdp, x, y);
                    return;
                }
            }
            sleepQuietly(120);
        }
        pressEnter(cdp);
    }

    private static void clickAt(CdpSession cdp, double x, double y) throws IOException {
        cdp.call("Input.dispatchMouseEvent", Map.of(
                "type", "mouseMoved", "x", x, "y", y));
        cdp.call("Input.dispatchMouseEvent", Map.of(
                "type", "mousePressed", "x", x, "y", y,
                "button", "left", "buttons", 1, "clickCount", 1));
        cdp.call("Input.dispatchMouseEvent", Map.of(
                "type", "mouseReleased", "x", x, "y", y,
                "button", "left", "buttons", 0, "clickCount", 1));
    }

    private static void pressEnter(CdpSession cdp) throws IOException {
        LinkedHashMap<String, Object> base = new LinkedHashMap<>();
        base.put("key", "Enter");
        base.put("code", "Enter");
        base.put("windowsVirtualKeyCode", 13);
        base.put("nativeVirtualKeyCode", 13);
        LinkedHashMap<String, Object> down = new LinkedHashMap<>(base);
        down.put("type", "keyDown");
        down.put("text", "\r");
        cdp.call("Input.dispatchKeyEvent", down);
        LinkedHashMap<String, Object> up = new LinkedHashMap<>(base);
        up.put("type", "keyUp");
        cdp.call("Input.dispatchKeyEvent", up);
    }

    private static Map<String, Object> evaluateObject(CdpSession cdp, String expression) throws IOException {
        Map<String, Object> response = cdp.call("Runtime.evaluate", Map.of(
                "expression", expression,
                "returnByValue", true,
                "awaitPromise", true));
        Map<?, ?> details = map(response.get("exceptionDetails"));
        if (details != null) {
            throw new IOException("Chromium 页面脚本执行失败：" + preview(exceptionText(details)));
        }
        Map<?, ?> result = map(response.get("result"));
        return stringMap(result == null ? null : result.get("value"));
    }

    private static String exceptionText(Map<?, ?> details) {
        if (details == null) {
            return "";
        }
        String text = string(details.get("text"));
        Map<?, ?> exception = map(details.get("exception"));
        if (exception != null) {
            String description = string(exception.get("description"));
            if (!description.isBlank()) {
                text = text.isBlank() ? description : text + "：" + description;
            }
        }
        return text;
    }

    private static Map<String, Object> stringMap(Object value) {
        Map<?, ?> source = map(value);
        if (source == null) {
            return null;
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0d;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean samePath(String url, String expected) {
        try {
            String path = URI.create(url).getPath();
            String expectedPath = URI.create(expected).getPath();
            if (path == null || expectedPath == null || expectedPath.isBlank()) {
                return false;
            }
            return path.equals(expectedPath) || path.startsWith(expectedPath);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String evaluateString(CdpSession cdp, String expression) throws IOException {
        Map<String, Object> response = cdp.call("Runtime.evaluate", Map.of(
                "expression", expression,
                "returnByValue", true,
                "awaitPromise", true));
        Map<?, ?> details = map(response.get("exceptionDetails"));
        if (details != null) {
            throw new IOException("Chromium 页面脚本执行失败：" + preview(exceptionText(details)));
        }
        Map<?, ?> result = map(response.get("result"));
        return result == null ? "" : string(result.get("value"));
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return "";
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "www.doubao.com" : host;
        } catch (RuntimeException ignored) {
            return "www.doubao.com";
        }
    }

    private static String originOf(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
            String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
            return scheme + "://" + uri.getHost() + port + "/";
        } catch (RuntimeException ignored) {
            return "https://www.doubao.com/";
        }
    }

    private static boolean sameHost(String url, String host) {
        if (url == null || url.isBlank() || host == null || host.isBlank()) {
            return false;
        }
        try {
            return host.equalsIgnoreCase(URI.create(url).getHost());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String json(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return SimpleJson.quote(text);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(SimpleJson.quote(String.valueOf(entry.getKey()))).append(':').append(json(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (Object item : iterable) {
                if (!first) out.append(',');
                first = false;
                out.append(json(item));
            }
            return out.append(']').toString();
        }
        return SimpleJson.quote(String.valueOf(value));
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String message(Throwable error) {
        String value = error == null ? "" : error.getMessage();
        return value == null || value.isBlank() ? String.valueOf(error) : value;
    }

    private static String preview(String value) {
        String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }

    static final class UnavailableException extends IOException {
        UnavailableException(String message) {
            super(message);
        }

        UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final class HttpException extends IOException {
        private final int status;
        private final String responseBody;

        HttpException(int status, String responseBody) {
            super("豆包浏览器请求失败，HTTP 状态=" + status + "，响应=" + preview(responseBody));
            this.status = status;
            this.responseBody = responseBody == null ? "" : responseBody;
        }

        int status() {
            return status;
        }

        String responseBody() {
            return responseBody;
        }
    }

    private static final class BrowserStream extends InputStream {
        private static final Object END = new Object();
        private static final long IDLE_TIMEOUT_MS = 300_000;
        private final CdpSession cdp;
        private final ReentrantLock pageLock;
        private final String binding;
        private final String abortName;
        private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private final AtomicBoolean finishQueued = new AtomicBoolean();
        private byte[] buffer;
        private int position;
        private volatile IOException failure;
        private volatile boolean ended;
        private volatile boolean closed;

        private BrowserStream(CdpSession cdp, ReentrantLock pageLock, String binding, String abortName) {
            this.cdp = cdp;
            this.pageLock = pageLock;
            this.binding = binding;
            this.abortName = abortName;
        }

        private void onBindingEvent(Map<String, Object> params) {
            if (params.containsKey("method") && !"Runtime.bindingCalled".equals(string(params.get("method")))) {
                return;
            }
            if (!binding.equals(string(params.get("name")))) {
                return;
            }
            try {
                Object parsed = SimpleJson.parse(string(params.get("payload")));
                if (!(parsed instanceof Map<?, ?> event)) {
                    return;
                }
                String type = string(event.get("type"));
                if ("chunk".equals(type)) {
                    queue.offer(string(event.get("data")));
                } else if ("end".equals(type)) {
                    finish();
                } else if ("error".equals(type)) {
                    int status = event.get("status") instanceof Number number ? number.intValue() : 0;
                    String text = string(event.get("message"));
                    fail(status > 0 ? new HttpException(status, text) : new IOException(
                            "豆包浏览器 fetch 失败：" + preview(text)));
                }
            } catch (RuntimeException e) {
                fail(new IOException("豆包浏览器回传数据无法解析：" + message(e), e));
            }
        }

        private void failTransport(Throwable error) {
            fail(new IOException("豆包浏览器 CDP 连接中断：" + message(error), error));
        }

        private void finish() {
            if (finishQueued.compareAndSet(false, true)) {
                queue.offer(END);
            }
        }

        private void fail(IOException error) {
            failure = error;
            finish();
        }

        @Override
        public int read() throws IOException {
            if (!fill()) {
                return -1;
            }
            return buffer[position++] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            if (!fill()) {
                return -1;
            }
            int count = Math.min(length, buffer.length - position);
            System.arraycopy(buffer, position, bytes, offset, count);
            position += count;
            return count;
        }

        private boolean fill() throws IOException {
            if (failure != null) {
                throw failure;
            }
            while (buffer == null || position >= buffer.length) {
                if (ended) {
                    return false;
                }
                Object item;
                try {
                    item = queue.poll(IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("读取豆包浏览器响应被中断", e);
                }
                if (item == null) {
                    throw new IOException("等待豆包浏览器响应超时（" + IDLE_TIMEOUT_MS / 1000 + " 秒）");
                }
                if (item == END) {
                    ended = true;
                    if (failure != null) {
                        throw failure;
                    }
                    return false;
                }
                buffer = ((String) item).getBytes(StandardCharsets.UTF_8);
                position = 0;
            }
            return true;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            finish();
            if (!ended) {
                cdp.post("Runtime.evaluate", Map.of(
                        "expression", "(() => { try { const c = globalThis[\"" + abortName
                                + "\"]; if (c) c.abort(); } catch (_) {} })()",
                        "returnByValue", true));
            }
            cdp.close();
            if (pageLock.isHeldByCurrentThread()) {
                pageLock.unlock();
            }
        }
    }

    private static final class UiCaptureStream extends InputStream {
        private static final Object END = new Object();
        private static final long IDLE_TIMEOUT_MS = 300_000;
        private static final int MAX_CAPTURE_BYTES = 32 * 1024 * 1024;
        private final CdpSession cdp;
        private final ReentrantLock pageLock;
        private final String binding;
        private final String hookName;
        private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private final LinkedBlockingQueue<Map<String, Object>> paused = new LinkedBlockingQueue<>();
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicBoolean hookStarted = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Thread worker;
        private byte[] buffer;
        private int position;
        private volatile IOException failure;
        private volatile boolean ended;

        private UiCaptureStream(CdpSession cdp, ReentrantLock pageLock, String binding, String hookName) {
            this.cdp = cdp;
            this.pageLock = pageLock;
            this.binding = binding;
            this.hookName = hookName;
            this.worker = new Thread(this::captureLoop, "doubao-ui-capture");
            this.worker.setDaemon(true);
            this.worker.start();
        }

        private void onCdpEvent(Map<String, Object> event) {
            String method = string(event.get("method"));
            if ("Runtime.bindingCalled".equals(method)) {
                if (!binding.equals(string(event.get("name")))) {
                    return;
                }
                onBindingPayload(string(event.get("payload")));
                return;
            }
            if ("Fetch.requestPaused".equals(method)) {
                paused.offer(event);
            }
        }

        private void onBindingPayload(String payload) {
            try {
                Map<String, Object> event = stringMap(SimpleJson.parse(payload));
                if (event == null) {
                    return;
                }
                String type = string(event.get("type"));
                if ("start".equals(type)) {
                    hookStarted.set(true);
                    started.countDown();
                } else if ("chunk".equals(type)) {
                    String data = string(event.get("data"));
                    if (!data.isEmpty()) {
                        hookStarted.set(true);
                        started.countDown();
                        queue.offer(data.getBytes(StandardCharsets.UTF_8));
                    }
                } else if ("end".equals(type)) {
                    hookStarted.set(true);
                    started.countDown();
                    finish();
                } else if ("error".equals(type)) {
                    hookStarted.set(true);
                    started.countDown();
                    int status = intValue(event.get("status"));
                    String text = string(event.get("message"));
                    fail(status > 0 ? new HttpException(status, text)
                            : new IOException("豆包页面请求失败：" + preview(text)));
                }
            } catch (RuntimeException e) {
                fail(new IOException("豆包页面回传数据无法解析：" + message(e), e));
            }
        }

        private void captureLoop() {
            boolean captured = false;
            while (!closed.get()) {
                Map<String, Object> event;
                try {
                    event = paused.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (event == null) {
                    continue;
                }
                String requestId = string(event.get("requestId"));
                Map<String, Object> request = stringMap(event.get("request"));
                String method = request == null ? "" : string(request.get("method"));
                if (requestId.isBlank() || (!method.isBlank() && !"POST".equalsIgnoreCase(method))) {
                    continueRequest(requestId);
                    continue;
                }
                if (captured || hookStarted.get()) {
                    continueRequest(requestId);
                    continue;
                }
                try {
                    if (started.await(250, TimeUnit.MILLISECONDS) && hookStarted.get()) {
                        continueRequest(requestId);
                        continue;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (hookStarted.get()) {
                    continueRequest(requestId);
                    continue;
                }
                captured = true;
                captureViaCdp(event);
            }
        }

        private void captureViaCdp(Map<String, Object> event) {
            String requestId = string(event.get("requestId"));
            int status = intValue(event.get("responseStatusCode"));
            List<Map<String, String>> headers = headerList(event.get("responseHeaders"));
            String handle = "";
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            try {
                Map<String, Object> taken = cdp.call("Fetch.takeResponseBodyAsStream",
                        Map.of("requestId", requestId));
                handle = string(taken.get("stream"));
                if (handle.isBlank()) {
                    throw new IOException("豆包页面响应没有可读取的流");
                }
                started.countDown();
                boolean errorResponse = status < 200 || status >= 300;
                while (!closed.get()) {
                    Map<String, Object> read = cdp.call("IO.read", Map.of(
                            "handle", handle, "size", 256));
                    String data = string(read.get("data"));
                    if (!data.isBlank()) {
                        byte[] bytes = Base64.getDecoder().decode(data);
                        if (all.size() + bytes.length > MAX_CAPTURE_BYTES) {
                            throw new IOException("豆包页面响应超过 "
                                    + (MAX_CAPTURE_BYTES / 1024 / 1024) + " MB");
                        }
                        all.write(bytes);
                        if (!errorResponse) {
                            queue.offer(bytes);
                        }
                    }
                    if (Boolean.TRUE.equals(read.get("eof"))) {
                        break;
                    }
                }
                if (closed.get()) {
                    failRequest(requestId);
                    return;
                }
                try {
                    cdp.call("IO.close", Map.of("handle", handle));
                } catch (IOException ignored) {
                }
                try {
                    fulfill(requestId, status, headers, all.toByteArray());
                } catch (IOException e) {
                    // The page may have cancelled its request while we were reading. Our copy is
                    // still complete, so do not turn that into a failed API response.
                    System.err.println("豆包页面响应回填失败（不影响 API 结果）：" + message(e));
                }
                if (errorResponse) {
                    fail(new HttpException(status, all.toString(StandardCharsets.UTF_8)));
                } else {
                    finish();
                }
            } catch (IOException | RuntimeException e) {
                if (!handle.isBlank()) {
                    try {
                        cdp.call("IO.close", Map.of("handle", handle));
                    } catch (IOException ignored) {
                    }
                }
                failRequest(requestId);
                if (!closed.get()) {
                    fail(new IOException("读取豆包页面响应失败：" + message(e), e));
                }
            }
        }

        private void continueRequest(String requestId) {
            if (requestId.isBlank()) {
                return;
            }
            try {
                cdp.call("Fetch.continueRequest", Map.of("requestId", requestId));
            } catch (IOException ignored) {
            }
        }

        private void failRequest(String requestId) {
            if (requestId.isBlank()) {
                return;
            }
            try {
                cdp.call("Fetch.failRequest", Map.of(
                        "requestId", requestId, "errorReason", "Failed"));
            } catch (IOException ignored) {
            }
        }

        private void fulfill(String requestId, int status, List<Map<String, String>> headers,
                             byte[] body) throws IOException {
            ArrayList<Map<String, String>> filtered = new ArrayList<>();
            for (Map<String, String> header : headers) {
                String name = header.get("name");
                if (name == null) {
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("content-length") || lower.equals("content-encoding")
                        || lower.equals("transfer-encoding") || lower.equals("connection")) {
                    continue;
                }
                filtered.add(header);
            }
            filtered.add(Map.of("name", "content-length", "value", String.valueOf(body.length)));
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("requestId", requestId);
            params.put("responseCode", status <= 0 ? 200 : status);
            params.put("responseHeaders", filtered);
            params.put("body", Base64.getEncoder().encodeToString(body));
            cdp.call("Fetch.fulfillRequest", params);
        }

        private static int intValue(Object value) {
            return value instanceof Number number ? number.intValue() : 0;
        }

        private static List<Map<String, String>> headerList(Object value) {
            ArrayList<Map<String, String>> out = new ArrayList<>();
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    Map<String, Object> header = stringMap(item);
                    if (header == null) {
                        continue;
                    }
                    String name = string(header.get("name"));
                    if (!name.isBlank()) {
                        out.add(Map.of("name", name, "value", string(header.get("value"))));
                    }
                }
            }
            return out;
        }

        private boolean awaitStarted(long timeoutMs) throws IOException {
            try {
                return started.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("等待豆包页面发送请求被中断", e);
            }
        }

        private void finish() {
            if (finished.compareAndSet(false, true)) {
                queue.offer(END);
            }
        }

        private void fail(IOException error) {
            failure = error;
            finish();
        }

        private void failTransport(Throwable error) {
            fail(new IOException("豆包浏览器 CDP 连接中断：" + message(error), error));
        }

        @Override
        public int read() throws IOException {
            if (!fill()) {
                return -1;
            }
            return buffer[position++] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            if (!fill()) {
                return -1;
            }
            int count = Math.min(length, buffer.length - position);
            System.arraycopy(buffer, position, bytes, offset, count);
            position += count;
            return count;
        }

        private boolean fill() throws IOException {
            if (failure != null) {
                throw failure;
            }
            while (buffer == null || position >= buffer.length) {
                if (ended) {
                    return false;
                }
                Object item;
                try {
                    item = queue.poll(IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("读取豆包页面响应被中断", e);
                }
                if (item == null) {
                    throw new IOException("等待豆包页面响应超时（"
                            + IDLE_TIMEOUT_MS / 1000 + " 秒）");
                }
                if (item == END) {
                    ended = true;
                    if (failure != null) {
                        throw failure;
                    }
                    return false;
                }
                buffer = (byte[]) item;
                position = 0;
            }
            return true;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            finished.set(true);
            queue.offer(END);
            worker.interrupt();
            try {
                cdp.post("Runtime.evaluate", Map.of(
                        "expression", "(() => { try { const hook = globalThis["
                                + SimpleJson.quote(hookName)
                                + "]; if (hook && typeof hook.uninstall === 'function') hook.uninstall(); }"
                                + " catch (_) {} })()",
                        "returnByValue", true));
            } catch (RuntimeException ignored) {
            }
            cdp.post("Fetch.disable", Map.of());
            cdp.close();
            if (pageLock.isHeldByCurrentThread()) {
                pageLock.unlock();
            }
        }
    }

    private static final class CdpSession implements AutoCloseable {
        private final int timeoutMs;
        private final AtomicLong nextId = new AtomicLong();
        private final Map<Long, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
        private final CountDownLatch opened = new CountDownLatch(1);
        private final AtomicReference<Throwable> openFailure = new AtomicReference<>();
        private final WebSocket socket;
        private volatile Consumer<Map<String, Object>> eventHandler = ignored -> { };
        private volatile Consumer<Throwable> disconnectHandler = ignored -> { };
        private volatile boolean closing;

        private CdpSession(OkHttpClient client, String url, int timeoutMs) throws IOException {
            this.timeoutMs = timeoutMs;
            Request request = new Request.Builder().url(url).build();
            socket = client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    opened.countDown();
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    receive(text);
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    openFailure.compareAndSet(null, t);
                    opened.countDown();
                    disconnect(t);
                }

                @Override
                public void onClosing(WebSocket webSocket, int code, String reason) {
                    webSocket.close(code, reason);
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    disconnect(new IOException("CDP WebSocket 已关闭：" + code + " " + reason));
                }
            });
            try {
                if (!opened.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    socket.cancel();
                    throw new IOException("连接 Chromium CDP WebSocket 超时");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                socket.cancel();
                throw new IOException("连接 Chromium CDP WebSocket 被中断", e);
            }
            if (openFailure.get() != null) {
                throw new IOException("连接 Chromium CDP WebSocket 失败：" + message(openFailure.get()), openFailure.get());
            }
        }

        private void setEventHandler(Consumer<Map<String, Object>> handler) {
            eventHandler = handler == null ? ignored -> { } : handler;
        }

        private void setDisconnectHandler(Consumer<Throwable> handler) {
            disconnectHandler = handler == null ? ignored -> { } : handler;
        }

        private Map<String, Object> call(String method, Map<String, Object> params) throws IOException {
            long id = nextId.incrementAndGet();
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            pending.put(id, future);
            LinkedHashMap<String, Object> command = new LinkedHashMap<>();
            command.put("id", id);
            command.put("method", method);
            command.put("params", params == null ? Map.of() : params);
            if (!socket.send(json(command))) {
                pending.remove(id);
                throw new IOException("向 Chromium CDP 发送命令失败：" + method);
            }
            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("等待 Chromium CDP 响应被中断：" + method, e);
            } catch (java.util.concurrent.TimeoutException e) {
                throw new IOException("等待 Chromium CDP 响应超时：" + method, e);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw new IOException("Chromium CDP 命令失败：" + method + "：" + message(cause), cause);
            } finally {
                pending.remove(id);
            }
        }

        private void post(String method, Map<String, Object> params) {
            long id = nextId.incrementAndGet();
            LinkedHashMap<String, Object> command = new LinkedHashMap<>();
            command.put("id", id);
            command.put("method", method);
            command.put("params", params == null ? Map.of() : params);
            socket.send(json(command));
        }

        @SuppressWarnings("unchecked")
        private void receive(String text) {
            Object parsed;
            try {
                parsed = SimpleJson.parse(text);
            } catch (RuntimeException ignored) {
                return;
            }
            if (!(parsed instanceof Map<?, ?> raw)) {
                return;
            }
            Map<String, Object> map = (Map<String, Object>) raw;
            Object idValue = map.get("id");
            if (idValue instanceof Number number) {
                CompletableFuture<Map<String, Object>> future = pending.remove(number.longValue());
                if (future != null) {
                    Map<?, ?> error = map(map.get("error"));
                    if (error != null) {
                        future.completeExceptionally(new IOException(string(error.get("message"))));
                    } else {
                        Map<?, ?> result = map(map.get("result"));
                        future.complete(result == null ? Map.of() : (Map<String, Object>) result);
                    }
                }
                return;
            }
            String method = string(map.get("method"));
            if (!method.isBlank()) {
                LinkedHashMap<String, Object> event = new LinkedHashMap<>(map);
                event.put("method", method);
                Map<?, ?> params = map(map.get("params"));
                if (params != null) {
                    for (Map.Entry<?, ?> entry : params.entrySet()) {
                        event.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                try {
                    eventHandler.accept(event);
                } catch (RuntimeException ignored) {
                    // A malformed event must not break the CDP reader thread.
                }
            }
        }

        private void disconnect(Throwable error) {
            if (closing) {
                return;
            }
            IOException failure = new IOException("Chromium CDP 连接已断开：" + message(error), error);
            for (CompletableFuture<Map<String, Object>> future : pending.values()) {
                future.completeExceptionally(failure);
            }
            disconnectHandler.accept(error);
        }

        @Override
        public void close() {
            if (closing) {
                return;
            }
            closing = true;
            socket.close(1000, "done");
            IOException failure = new IOException("Chromium CDP 会话已关闭");
            for (CompletableFuture<Map<String, Object>> future : pending.values()) {
                future.completeExceptionally(failure);
            }
        }
    }
}
