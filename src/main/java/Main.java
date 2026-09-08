import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicReference;

public class Main {

    private static final MediaType FORM_MEDIA_TYPE =
            MediaType.get("application/x-www-form-urlencoded;charset=UTF-8");
    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.get("application/json;charset=UTF-8");
    private static final String DEFAULT_MODEL = "gemini-3.6-Flash";
    private static final String CLAUDE_MODEL = "claude-sonnet-4-6";
    private static final String CHATGPT_MODEL = "chatgpt-web";
    private static final int GEMINI_RPC_MAX_ATTEMPTS = 3;
    private static final Pattern CLAUDE_RESETS_AT_PATTERN = Pattern.compile("\\\"resetsAt\\\"\\s*:\\s*(\\d+)");
    private static final Pattern BARD_ERROR_PATTERN = Pattern.compile("BardErrorInfo\"\\s*,\\s*\\[(\\d+)]");
    private static final Pattern WINDOWS_ABSOLUTE_PATH_PATTERN = Pattern.compile("^[a-z]:[\\\\/].*");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}");
    private static final Map<String, ModelProfile> MODEL_PROFILES = modelProfiles();

    public static void main(String[] args) {
        try {
            AppConfig config = AppConfig.load(Path.of("config.yml"));
            AtomicReference<OkHttpClient> clientRef = new AtomicReference<>();
            ProxySettingsManager proxySettings = new ProxySettingsManager(config, clientRef);
            OkHttpClient client = buildHttpClient(config);
            clientRef.set(client);
            config.geminiCookieProvider = GeminiCookieProvider.create(config);
            ClaudeCookieStore claudeCookieStore = new ClaudeCookieStore(Path.of("data", "claude-cookies.sqlite"));
            config.claudeCookieStore = claudeCookieStore;
            config.chatGptCurlStore = new ChatGptCurlStore(Path.of("data", "chatgpt-curls.sqlite"));

            if (args.length > 1 && "--parse-file".equals(args[0])) {
                GeminiResult result = parseGeminiResultOrThrow(Files.readString(Path.of(args[1])));
                System.out.println("text: " + result.text());
                System.out.println("conversation: " + result.conversationId());
                System.out.println("response: " + result.responseId());
                System.out.println("choice: " + result.choiceId());
                return;
            }

            if (args.length > 0 && "--no-send".equals(args[0])) {
                CurlRequest curl = CurlRequest.parse(config.curl);
                config.refreshGeminiCookie(curl);
                curl.prepareForConversation(new ConversationState());
                curl.applyModel(modelProfile(DEFAULT_MODEL));
                String prompt = joinArgs(args, 1);
                if (!prompt.isBlank() && !prompt.equals(curl.originalPrompt())) {
                    curl.replacePrompt(prompt);
                }
                printStatus(config, curl);
                return;
            }

            if (args.length > 0) {
                sendOnce(config, client, DEFAULT_MODEL, String.join(" ", args), new ConversationState());
                return;
            }

            OpenAiApiServer server = startApiServer(config, clientRef, proxySettings, claudeCookieStore);
            try {
                runLoop(config, client);
            } finally {
                if (server != null) {
                    server.stop();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static OpenAiApiServer startApiServer(AppConfig config, AtomicReference<OkHttpClient> clientRef,
                                                  OpenAiApiServer.ProxySettingsBackend proxySettings,
                                                  ClaudeCookieStore claudeCookieStore) throws IOException {
        if (!config.openAiEnabled) {
            System.out.println("OpenAI API 未启用。");
            return null;
        }

        OpenAiApiServer server = new OpenAiApiServer(
                config.openAiHost,
                config.openAiPort,
                modelNames(),
                DEFAULT_MODEL,
                new ApiChatBackend(config, clientRef),
                claudeCookieStore,
                config.chatGptCurlStore,
                proxySettings);
        server.start();
        return server;
    }

    private static void runLoop(AppConfig config, OkHttpClient client) {
        System.out.println("模型：" + DEFAULT_MODEL
                + "。输入 /new 开启新对话，/models 查看模型，/model 名称切换模型，/stop 退出。");
        ConversationState conversation = new ConversationState();
        String currentModel = DEFAULT_MODEL;
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            if (!scanner.hasNextLine()) {
                break;
            }

            String prompt = scanner.nextLine().trim();
            if ("/stop".equalsIgnoreCase(prompt)) {
                break;
            }
            if ("/new".equalsIgnoreCase(prompt)) {
                conversation.clear();
                System.out.println("已开启新对话。");
                continue;
            }
            if ("/models".equalsIgnoreCase(prompt)) {
                System.out.println("可用模型：" + String.join(", ", modelNames()));
                System.out.println("当前模型：" + currentModel);
                continue;
            }
            if (prompt.startsWith("/model ")) {
                String requestedModel = prompt.substring("/model ".length()).trim();
                try {
                    currentModel = modelProfile(requestedModel).name();
                    conversation.clear();
                    System.out.println("已切换模型：" + currentModel + "。已开启新对话。");
                } catch (IllegalArgumentException e) {
                    System.err.println(e.getMessage());
                }
                continue;
            }
            if (prompt.isBlank()) {
                continue;
            }

            try {
                sendOnce(config, client, currentModel, prompt, conversation);
            } catch (Exception e) {
                System.err.println("请求失败：" + e.getMessage());
            }
        }
        System.out.println("已退出。");
    }

    private static void sendOnce(AppConfig config, OkHttpClient client, String model, String prompt,
                                 ConversationState conversation) throws IOException {
        sendInternal(config, client, model, prompt, java.util.List.of(), conversation, System.out::print);
        System.out.println();
    }

    private static GeminiResult sendInternal(AppConfig config, OkHttpClient client, String model, String prompt,
                                             java.util.List<OpenAiApiServer.ImageInput> images,
                                             ConversationState conversation, Consumer<String> deltaSink) throws IOException {
        ModelProfile profile = modelProfile(model);
        if (profile.provider() == ModelProvider.CLAUDE) {
            return sendClaudeInternal(config, client, profile, prompt, images, deltaSink);
        }
        if (profile.provider() == ModelProvider.CHATGPT) {
            return sendChatGptInternal(config, client, profile, prompt, deltaSink, conversation.chatGptConversation());
        }

        BardRpcException lastRetryableError = null;
        for (int attempt = 1; attempt <= GEMINI_RPC_MAX_ATTEMPTS; attempt++) {
            try {
                return sendInternalOnce(config, client, model, prompt, images, conversation, deltaSink);
            } catch (BardRpcException e) {
                if (!isRetryableBardError(e) || attempt == GEMINI_RPC_MAX_ATTEMPTS) {
                    throw e;
                }
                lastRetryableError = e;
                System.err.println("Gemini RPC 返回 BardErrorInfo，错误码=" + e.code()
                        + "，自动重试 " + (attempt + 1) + "/" + GEMINI_RPC_MAX_ATTEMPTS);
                sleepBeforeRetry(attempt);
            }
        }
        throw lastRetryableError == null ? new IOException("Gemini 请求重试失败") : lastRetryableError;
    }

    private static GeminiResult sendInternalOnce(AppConfig config, OkHttpClient client, String model, String prompt,
                                                 java.util.List<OpenAiApiServer.ImageInput> images,
                                                 ConversationState conversation, Consumer<String> deltaSink) throws IOException {
        ModelProfile profile = modelProfile(model);
        CurlRequest curl = CurlRequest.parse(config.curl);
        config.refreshGeminiCookie(curl);
        curl.prepareForConversation(conversation);
        curl.applyModel(profile);
        ImageUploadResult imageUpload = images == null || images.isEmpty()
                ? ImageUploadResult.empty()
                : uploadImages(client, curl, images);
        String promptToSend = promptWithImageUploadResult(prompt, imageUpload);
        if (!promptToSend.equals(curl.originalPrompt())) {
            curl.replacePrompt(promptToSend);
        }
        if (images != null && !images.isEmpty()) {
            curl.replaceImages(imageUpload.attachments());
        }

        try (Response response = client.newCall(curl.toRequest()).execute()) {
            if (response.body() == null) {
                throw new IOException("Gemini 返回了空响应");
            }
            if (!response.isSuccessful()) {
                String body = response.body().string();
                String code = bardErrorCode(body);
                if (code != null) {
                    throw new BardRpcException(code, body);
                }
                throw new IOException("Gemini 请求失败，HTTP 状态=" + response.code()
                        + "，响应=" + preview(body));
            }
            GeminiResult result = streamGeminiText(response, deltaSink);
            conversation.completeTurn(result);
            return result;
        }
    }

    private static GeminiResult sendClaudeInternal(AppConfig config, OkHttpClient client, ModelProfile profile,
                                                   String prompt,
                                                   java.util.List<OpenAiApiServer.ImageInput> images,
                                                   Consumer<String> deltaSink) throws IOException {
        if (config.claudeCookieStore == null) {
            throw new IOException("Claude cookie 存储未初始化");
        }

        java.util.List<ClaudeCookieStore.CookieRecord> cookies = config.claudeCookieStore.activeShuffled();
        if (cookies.isEmpty()) {
            throw new IOException("没有可用 Claude cookie。请在前端粘贴 Claude curl 添加账号 cookie。");
        }

        IOException lastError = null;
        for (ClaudeCookieStore.CookieRecord cookie : cookies) {
            try {
                ClaudeCurlRequest curl = ClaudeCurlRequest.create(cookie.cookie(), cookie.orgId());
                ClaudeUploadResult uploadResult = uploadClaudeImages(client, curl, images);
                String promptToSend = promptWithClaudeUploadResult(prompt, uploadResult);
                curl.prepare(profile.name(), promptToSend, uploadResult.fileUuids());

                try (Response response = client.newCall(curl.toRequest()).execute()) {
                    if (response.body() == null) {
                        throw new IOException("Claude 返回了空响应");
                    }
                    if (!response.isSuccessful()) {
                        String body = response.body().string();
                        if (response.code() == 429) {
                            Instant resetAt = claudeRateLimitResetAt(body);
                            if (resetAt != null && resetAt.isAfter(Instant.now())) {
                                String error = "Claude 请求限流，HTTP 状态=429，恢复时间=" + resetAt + "，响应=" + preview(body);
                                config.claudeCookieStore.disableUntil(cookie.id(), resetAt, error);
                                lastError = new IOException(error);
                                System.err.println("Claude cookie 触发 429，临时禁用到 " + resetAt + "：" + cookie.label());
                                continue;
                            }
                        }
                        throw new IOException("Claude 请求失败，HTTP 状态=" + response.code()
                                + "，响应=" + preview(body));
                    }
                    GeminiResult result = streamClaudeText(response, deltaSink);
                    config.claudeCookieStore.markSuccess(cookie.id());
                    return result;
                }
            } catch (IOException e) {
                lastError = e;
                config.claudeCookieStore.markFailure(cookie.id(), e.getMessage());
                System.err.println("Claude cookie 调用失败，切换下一个：" + cookie.label() + "：" + e.getMessage());
            }
        }
        throw lastError == null ? new IOException("Claude cookie 调用失败") : lastError;
    }

    private static GeminiResult sendChatGptInternal(AppConfig config, OkHttpClient client, ModelProfile profile,
                                                    String prompt, Consumer<String> deltaSink,
                                                    ChatGptConversation conversation) throws IOException {
        if (config.chatGptCurlStore == null) {
            throw new IOException("ChatGPT curl 存储未初始化");
        }
        return new ChatGptCurlClient(config.chatGptCurlStore, client).complete(prompt, deltaSink, conversation);
    }

    private static Instant claudeRateLimitResetAt(String body) {
        Object parsed = MiniJson.parse(body);
        String resetsAt = findNestedString(parsed, "resetsAt");
        if (resetsAt.isBlank()) {
            Matcher matcher = CLAUDE_RESETS_AT_PATTERN.matcher(body == null ? "" : body);
            if (matcher.find()) {
                resetsAt = matcher.group(1);
            }
        }
        if (resetsAt.isBlank()) {
            return null;
        }
        try {
            long epochSeconds = Long.parseLong(resetsAt.trim());
            return Instant.ofEpochSecond(epochSeconds);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static ClaudeUploadResult uploadClaudeImages(OkHttpClient client, ClaudeCurlRequest completionCurl,
                                                         java.util.List<OpenAiApiServer.ImageInput> images) {
        if (images == null || images.isEmpty()) {
            return ClaudeUploadResult.empty();
        }

        java.util.ArrayList<String> fileUuids = new java.util.ArrayList<>();
        java.util.ArrayList<String> skipped = new java.util.ArrayList<>();
        for (OpenAiApiServer.ImageInput image : images) {
            try {
                byte[] bytes = imageBytes(client, image);
                String fileUuid = uploadClaudeImage(client, completionCurl, bytes, image.mimeType(), image.filename());
                fileUuids.add(fileUuid);
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                skipped.add(image.filename() + "：" + reason);
                System.err.println("跳过不可用 Claude 图片 " + image.filename() + "：" + reason);
            }
        }
        return new ClaudeUploadResult(java.util.List.copyOf(fileUuids), java.util.List.copyOf(skipped));
    }

    private static String uploadClaudeImage(OkHttpClient client, ClaudeCurlRequest completionCurl,
                                            byte[] bytes, String mimeType, String filename) throws IOException {
        try (Response response = client.newCall(completionCurl.toUploadRequest(bytes, mimeType, filename)).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Claude 图片上传失败，HTTP 状态=" + response.code()
                        + "，响应=" + preview(response.body() == null ? "" : response.body().string()));
            }
            String body = response.body().string();
            String fileUuid = claudeFileUuid(body);
            if (fileUuid.isBlank()) {
                throw new IOException("Claude 图片上传响应缺少 file_uuid：" + preview(body));
            }
            return fileUuid;
        }
    }

    private static String promptWithClaudeUploadResult(String prompt, ClaudeUploadResult uploadResult) {
        String base = prompt == null ? "" : prompt;
        if (uploadResult == null || uploadResult.skippedCount() == 0) {
            return base.isBlank() ? "请根据用户输入回答。" : base;
        }
        String notice = "[系统提示：用户提供了 "
                + uploadResult.skippedCount()
                + " 张图片未能上传；请仅基于已成功上传的图片和可见文字回答。]";
        if (base.isBlank()) {
            return notice;
        }
        return base + "\n" + notice;
    }

    private static String claudeFileUuid(String body) {
        Object parsed = MiniJson.parse(body);
        String found = findNestedString(parsed, "file_uuid");
        if (!found.isBlank()) {
            return found;
        }
        found = findNestedString(parsed, "uuid");
        if (!found.isBlank() && UUID_PATTERN.matcher(found).matches()) {
            return found;
        }
        Matcher matcher = UUID_PATTERN.matcher(body == null ? "" : body);
        return matcher.find() ? matcher.group() : "";
    }

    private static GeminiResult streamClaudeText(Response response, Consumer<String> deltaSink) throws IOException {
        StringBuilder all = new StringBuilder();
        StringBuilder text = new StringBuilder();

        while (true) {
            String line;
            try {
                line = response.body().source().readUtf8Line();
            } catch (IOException e) {
                if (!text.isEmpty()) {
                    System.err.println("Claude 流读取中断，已收到 "
                            + text.length() + " 字符，按已完成响应处理：" + e.getMessage());
                    break;
                }
                throw e;
            }
            if (line == null) {
                break;
            }

            all.append(line).append('\n');
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }

            String data = trimmed.substring("data:".length()).trim();
            if (data.isBlank() || "[DONE]".equals(data)) {
                continue;
            }

            String delta = claudeDeltaText(data);
            if (delta.isBlank()) {
                continue;
            }
            text.append(delta);
            if (deltaSink != null) {
                deltaSink.accept(delta);
            }
        }

        dumpRawResponseIfRequested(all.toString());
        if (text.isEmpty()) {
            String fallback = claudeDeltaText(all.toString());
            if (!fallback.isBlank()) {
                text.append(fallback);
                if (deltaSink != null) {
                    deltaSink.accept(fallback);
                }
            }
        }
        if (text.isEmpty()) {
            throw new IOException("无法从 Claude 响应里解析文本：" + preview(all.toString()));
        }
        return new GeminiResult(text.toString(), "", "", "");
    }

    @SuppressWarnings("unchecked")
    private static String claudeDeltaText(String json) {
        Object parsed = MiniJson.parse(json);
        if (!(parsed instanceof Map<?, ?> root)) {
            return "";
        }

        String direct = firstNonBlank(
                stringValue(root.get("completion")),
                stringValue(root.get("text")),
                stringValue(root.get("delta")));
        if (!direct.isBlank()) {
            return direct;
        }

        Object delta = root.get("delta");
        if (delta instanceof Map<?, ?> deltaMap) {
            direct = firstNonBlank(
                    stringValue(deltaMap.get("text")),
                    stringValue(deltaMap.get("completion")));
            if (!direct.isBlank()) {
                return direct;
            }
        }

        Object message = root.get("message");
        if (message instanceof Map<?, ?> messageMap) {
            direct = claudeContentText(messageMap.get("content"));
            if (!direct.isBlank()) {
                return direct;
            }
        }

        Object contentBlock = root.get("content_block");
        return claudeContentText(contentBlock);
    }

    private static String claudeContentText(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            return firstNonBlank(stringValue(map.get("text")), stringValue(map.get("completion")));
        }
        if (value instanceof java.util.List<?> list) {
            StringBuilder out = new StringBuilder();
            for (Object item : list) {
                String text = claudeContentText(item);
                if (!text.isBlank()) {
                    out.append(text);
                }
            }
            return out.toString();
        }
        return "";
    }

    private static boolean isRetryableBardError(BardRpcException e) {
        return e != null && e.code() != null && !e.code().isBlank();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String findNestedString(Object value, String key) {
        return findNestedString(value, key, 0);
    }

    private static String findNestedString(Object value, String key, int depth) {
        if (value == null || key == null || depth > 8) {
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (key.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                    Object entryValue = entry.getValue();
                    if (entryValue instanceof String text) {
                        return text;
                    }
                    if (entryValue instanceof Number || entryValue instanceof Boolean) {
                        return String.valueOf(entryValue);
                    }
                }
            }
            for (Object child : map.values()) {
                String found = findNestedString(child, key, depth + 1);
                if (!found.isBlank()) {
                    return found;
                }
            }
            return "";
        }
        if (value instanceof java.util.List<?> list) {
            for (Object child : list) {
                String found = findNestedString(child, key, depth + 1);
                if (!found.isBlank()) {
                    return found;
                }
            }
        }
        return "";
    }

    private static void sleepBeforeRetry(int failedAttempt) throws IOException {
        try {
            Thread.sleep(Math.min(2_000L, 500L * failedAttempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Gemini 请求重试被中断", e);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, ModelProfile> modelProfiles() {
        Map<String, ModelProfile> profiles = new LinkedHashMap<>();
        profiles.put(DEFAULT_MODEL, new ModelProfile(DEFAULT_MODEL, ModelProvider.GEMINI, null, null, null, null));
        profiles.put("gemini-3.1-Pro", new ModelProfile(
                "gemini-3.1-Pro",
                ModelProvider.GEMINI,
                "9d8ca3786ebdfbea",
                3,
                "2cc5278d23ac6305f37b1e606442fb1e",
                3));
        profiles.put("gemini-3.1-Flash-Lite", new ModelProfile(
                "gemini-3.1-Flash-Lite",
                ModelProvider.GEMINI,
                "cf41b0e0dd7d53e5",
                6,
                "afe4fdb4dcc64484a436c7456c034885",
                6));
        profiles.put(CLAUDE_MODEL, new ModelProfile(CLAUDE_MODEL, ModelProvider.CLAUDE, null, null, null, null));
        profiles.put(CHATGPT_MODEL, new ModelProfile(CHATGPT_MODEL, ModelProvider.CHATGPT, null, null, null, null));
        return java.util.Collections.unmodifiableMap(profiles);
    }

    private static ModelProfile modelProfile(String model) {
        String requested = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        ModelProfile exact = MODEL_PROFILES.get(requested);
        if (exact != null) {
            return exact;
        }
        for (ModelProfile profile : MODEL_PROFILES.values()) {
            if (profile.name().equalsIgnoreCase(requested)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("未知模型：" + requested
                + "。可用模型：" + String.join(", ", modelNames()));
    }

    private static java.util.List<String> modelNames() {
        return new java.util.ArrayList<>(MODEL_PROFILES.keySet());
    }

    private static GeminiResult streamGeminiText(Response response, Consumer<String> deltaSink) throws IOException {
        StringBuilder all = new StringBuilder();
        String printed = "";
        GeminiResult latest = GeminiResult.empty();

        while (true) {
            String line = response.body().source().readUtf8Line();
            if (line == null) {
                break;
            }

            all.append(line).append('\n');
            GeminiResult currentResult = parseGeminiResult(all.toString());
            latest = latest.merge(currentResult);
            String current = currentResult.text();
            if (current == null || current.length() <= printed.length()) {
                continue;
            }
            if (!printed.isEmpty() && !current.startsWith(printed)) {
                continue;
            }

            String delta = current.substring(printed.length());
            deltaSink.accept(delta);
            printed = current;
        }

        if (!printed.isEmpty()) {
            dumpRawResponseIfRequested(all.toString());
            return latest.merge(parseGeminiResult(all.toString()));
        }

        dumpRawResponseIfRequested(all.toString());
        GeminiResult result = parseGeminiResultOrThrow(all.toString());
        deltaSink.accept(result.text());
        return result;
    }

    private static void dumpRawResponseIfRequested(String body) throws IOException {
        String path = System.getProperty("dumpRaw");
        if (path == null || path.isBlank()) {
            return;
        }
        Files.writeString(Path.of(path), body, StandardCharsets.UTF_8);
    }

    private static OkHttpClient buildHttpClient(AppConfig config) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(300))
                .writeTimeout(Duration.ofSeconds(30))
                .retryOnConnectionFailure(true);

        if (config.proxyEnabled) {
            Proxy.Type type = "socks".equalsIgnoreCase(config.proxyType)
                    ? Proxy.Type.SOCKS
                    : Proxy.Type.HTTP;
            builder.proxy(new Proxy(type, new InetSocketAddress(config.proxyHost, config.proxyPort)));
        }

        return builder.build();
    }

    private static ImageUploadResult uploadImages(OkHttpClient client,
                                                 CurlRequest curl,
                                                 java.util.List<OpenAiApiServer.ImageInput> images) {
        java.util.ArrayList<GeminiAttachment> attachments = new java.util.ArrayList<>();
        java.util.ArrayList<String> skipped = new java.util.ArrayList<>();
        for (OpenAiApiServer.ImageInput image : images) {
            try {
                byte[] bytes = imageBytes(client, image);
                String path = uploadGeminiImage(client, curl, bytes, image.filename());
                attachments.add(new GeminiAttachment(path, image.mimeType(), image.filename()));
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                skipped.add(image.filename() + "：" + reason);
                System.err.println("跳过不可用图片 " + image.filename() + "：" + reason);
            }
        }
        return new ImageUploadResult(java.util.List.copyOf(attachments), java.util.List.copyOf(skipped));
    }

    private static String promptWithImageUploadResult(String prompt, ImageUploadResult imageUpload) {
        String base = prompt == null ? "" : prompt;
        if (imageUpload == null || imageUpload.skippedCount() == 0) {
            return base.isBlank() ? "请根据用户输入回答。" : base;
        }
        String notice = "[系统提示：用户提供了 "
                + imageUpload.skippedCount()
                + " 张图片，但服务端无法读取这些图片内容；请不要声称已经看到了图片，直接基于可见文字回答。]";
        if (base.isBlank()) {
            return notice;
        }
        return base + "\n" + notice;
    }

    private static byte[] imageBytes(OkHttpClient client, OpenAiApiServer.ImageInput image) throws IOException {
        java.util.List<String> sources = image.sources();
        if (sources == null || sources.isEmpty()) {
            sources = java.util.List.of(image.url());
        }

        java.util.ArrayList<String> failures = new java.util.ArrayList<>();
        IllegalArgumentException invalidFailure = null;
        IOException ioFailure = null;
        for (String source : sources) {
            String value = source == null ? "" : source.trim();
            if (value.isBlank()) {
                continue;
            }
            try {
                return imageBytesFromSource(client, value);
            } catch (IllegalArgumentException e) {
                invalidFailure = e;
                failures.add(imageSourceLabel(value) + "：" + e.getMessage());
            } catch (IOException e) {
                ioFailure = e;
                failures.add(imageSourceLabel(value) + "：" + e.getMessage());
            }
        }

        String message = failures.isEmpty()
                ? "图片来源为空"
                : "无法读取图片，已尝试 " + failures.size() + " 个来源：" + String.join("；", failures)
                + "。如果图片不在服务端本机，请传入 data URL、base64 或 http(s) URL";
        if (invalidFailure != null || ioFailure != null) {
            IllegalArgumentException error = new IllegalArgumentException(
                    message,
                    invalidFailure == null ? ioFailure : invalidFailure);
            if (invalidFailure != null && ioFailure != null) {
                error.addSuppressed(ioFailure);
            }
            throw error;
        }
        throw new IllegalArgumentException(message);
    }

    private static byte[] imageBytesFromSource(OkHttpClient client, String value) throws IOException {
        if (value.startsWith("data:")) {
            int comma = value.indexOf(',');
            if (comma < 0) {
                throw new IOException("图片 data URL 缺少 base64 内容");
            }
            return decodeImageBase64(value.substring(comma + 1), "图片 data URL base64 内容");
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            Request request = new Request.Builder()
                    .url(value)
                    .header("User-Agent", "Gemini-WebToAPI/1.0")
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("下载图片失败，HTTP 状态=" + response.code());
                }
                return response.body().bytes();
            }
        }
        if (value.startsWith("file:")) {
            Path fileUriPath = pathFromFileUri(value);
            if (Files.exists(fileUriPath) && Files.isRegularFile(fileUriPath)) {
                return Files.readAllBytes(fileUriPath);
            }
            throw new IllegalArgumentException("图片文件不存在或不可访问：" + fileUriPath);
        }
        Path path = Path.of(value);
        if (Files.exists(path) && Files.isRegularFile(path)) {
            return Files.readAllBytes(path);
        }
        if (looksLikeLocalImagePath(value)) {
            throw new IllegalArgumentException("图片文件不存在或不可访问：" + value);
        }
        return decodeImageBase64(value, "图片 base64 内容");
    }

    private static String imageSourceLabel(String value) {
        if (value.startsWith("data:")) {
            return "data URL";
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return "远程 URL";
        }
        if (value.startsWith("file:")) {
            return "file URI";
        }
        if (looksLikeLocalImagePath(value)) {
            return "本地路径 " + value;
        }
        return "base64 内容";
    }

    private static String mimeFromFilename(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "application/octet-stream";
    }

    private static Path pathFromFileUri(String value) {
        try {
            return Path.of(URI.create(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("图片 file URI 无效：" + value, e);
        }
    }

    private static byte[] decodeImageBase64(String raw, String description) {
        String normalized = raw == null ? "" : raw.trim().replaceAll("\\s+", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(description + "为空");
        }
        int remainder = normalized.length() % 4;
        if (remainder == 1) {
            throw new IllegalArgumentException(description + "长度无效");
        }
        if (remainder > 1) {
            normalized += "=".repeat(4 - remainder);
        }

        try {
            if (normalized.indexOf('-') >= 0 || normalized.indexOf('_') >= 0) {
                return Base64.getUrlDecoder().decode(normalized);
            }
            return Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException firstError) {
            try {
                return Base64.getUrlDecoder().decode(normalized);
            } catch (IllegalArgumentException secondError) {
                IllegalArgumentException error = new IllegalArgumentException(description + "不是有效的 base64/base64url");
                error.addSuppressed(firstError);
                error.addSuppressed(secondError);
                throw error;
            }
        }
    }

    private static boolean looksLikeLocalImagePath(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("/")
                || lower.startsWith("\\")
                || lower.startsWith("./")
                || lower.startsWith("../")
                || WINDOWS_ABSOLUTE_PATH_PATTERN.matcher(lower).matches()
                || lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".webp")
                || lower.endsWith(".gif");
    }

    private static String uploadGeminiImage(OkHttpClient client, CurlRequest curl, byte[] bytes, String filename) throws IOException {
        Request start = new Request.Builder()
                .url("https://push.clients6.google.com/upload/?upload_protocol=resumable")
                .headers(curl.uploadStartHeaders(bytes.length))
                .post(RequestBody.create("File name: " + filename, FORM_MEDIA_TYPE))
                .build();
        String uploadUrl;
        try (Response response = client.newCall(start).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("创建图片上传会话失败，HTTP 状态=" + response.code()
                        + "，响应=" + preview(response.body() == null ? "" : response.body().string()));
            }
            uploadUrl = response.header("X-Goog-Upload-Url");
        }
        if (uploadUrl == null || uploadUrl.isBlank()) {
            throw new IOException("图片上传响应缺少 X-Goog-Upload-Url");
        }

        Request upload = new Request.Builder()
                .url(uploadUrl)
                .headers(curl.uploadFinalizeHeaders())
                .post(RequestBody.create(bytes, MediaType.get("application/x-www-form-urlencoded;charset=utf-8")))
                .build();
        try (Response response = client.newCall(upload).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("上传图片失败，HTTP 状态=" + response.code());
            }
            String path = response.body().string().trim();
            if (path.isBlank()) {
                throw new IOException("图片上传返回了空路径");
            }
            return path;
        }
    }

    private static void printStatus(AppConfig config, CurlRequest curl) {
        System.out.println("proxy: " + (config.proxyEnabled
                ? config.proxyType + "://" + config.proxyHost + ":" + config.proxyPort
                : "disabled"));
        System.out.println("model: " + DEFAULT_MODEL);
        System.out.println("models: " + String.join(", ", modelNames()));
        System.out.println("curl: set len=" + config.curl.length());
        System.out.println("url: " + curl.url.redact());
        System.out.println("cookie: " + (curl.cookie.isBlank() ? "blank" : "set len=" + curl.cookie.length()));
        System.out.println("form: " + (curl.form.isBlank() ? "blank" : "set len=" + curl.form.length()));
        System.out.println("headers: " + curl.headers.size());
        System.out.println("prompt: " + (curl.originalPrompt().isBlank() ? "blank" : curl.originalPrompt()));
    }

    private static String parseGeminiText(String body) throws IOException {
        if (body == null || body.isBlank()) {
            throw new IOException("Gemini 返回了空响应");
        }

        String trimmed = body.stripLeading().toLowerCase();
        if (trimmed.startsWith("<!doctype") || trimmed.startsWith("<html") || trimmed.contains("<title>")) {
            throw new IOException("Gemini 返回了 HTML，不是 RPC 数据：" + preview(body));
        }

        GeminiResult result = parseGeminiResult(body);
        if (!result.text().isBlank()) {
            return result.text();
        }
        String code = bardErrorCode(body);
        if (code != null) {
            throw new BardRpcException(code, body);
        }
        throw new IOException("无法从 Gemini 响应里解析文本：" + preview(body));
    }

    private static String bardErrorCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        Matcher bardError = BARD_ERROR_PATTERN.matcher(body);
        return bardError.find() ? bardError.group(1) : null;
    }

    private static String tryParseGeminiText(String body) {
        String text = parseGeminiResult(body).text();
        return text.isBlank() ? null : text;
    }

    private static GeminiResult parseGeminiResultOrThrow(String body) throws IOException {
        String text = parseGeminiText(body);
        GeminiResult result = parseGeminiResult(body);
        return new GeminiResult(text, result.conversationId(), result.responseId(), result.choiceId());
    }

    private static GeminiResult parseGeminiResult(String body) {
        GeminiResult result = GeminiResult.empty();
        int searchFrom = 0;
        while (true) {
            int marker = body.indexOf("\"wrb.fr\"", searchFrom);
            if (marker < 0) {
                break;
            }
            searchFrom = marker + 8;

            String payload = wrbPayloadAt(body, marker);
            if (payload == null || payload.isBlank()) {
                continue;
            }

            result = result.merge(parseGeminiPayload(payload));
        }

        return result;
    }

    private static GeminiResult parseGeminiPayload(String payload) {
        Object parsed = MiniJson.parse(payload);
        if (!(parsed instanceof java.util.List<?> root)) {
            return GeminiResult.empty();
        }

        GeminiResult result = GeminiResult.empty();
        ConversationIds ids = conversationIdsFromPayload(root);
        if (ids != null) {
            result = result.merge(new GeminiResult("", ids.conversationId(), ids.responseId(), ""));
        }

        Object choices = listValue(root, 4);
        if (choices instanceof java.util.List<?> choiceList) {
            for (Object choiceObject : choiceList) {
                if (!(choiceObject instanceof java.util.List<?> choice)) {
                    continue;
                }
                String choiceId = stringValue(listValue(choice, 0));
                String text = textFromChoice(choice);
                if (!text.isBlank()) {
                    result = result.merge(new GeminiResult(text, "", "", choiceId));
                } else if (isChoiceId(choiceId)) {
                    result = result.merge(new GeminiResult("", "", "", choiceId));
                }
            }
        }

        return result;
    }

    private static ConversationIds conversationIdsFromPayload(java.util.List<?> root) {
        Object ids = listValue(root, 1);
        if (!(ids instanceof java.util.List<?> list)) {
            return null;
        }

        String conversationId = stringValue(listValue(list, 0));
        String responseId = stringValue(listValue(list, 1));
        if (conversationId.startsWith("c_") && responseId.startsWith("r_")) {
            return new ConversationIds(conversationId, responseId);
        }
        return null;
    }

    private static String textFromChoice(java.util.List<?> choice) {
        String imageUrl = generatedImageUrl(choice);
        if (!imageUrl.isBlank()) {
            return imageUrl;
        }

        String direct = joinedStringArray(listValue(choice, 1));
        if (isAnswerText(direct)) {
            return direct;
        }

        String nested = deepestUsefulText(listValue(choice, 43));
        if (isAnswerText(nested)) {
            return nested;
        }

        return "";
    }

    private static String generatedImageUrl(Object value) {
        String best = generatedImageUrlValue(value);
        return best == null ? "" : best;
    }

    private static String generatedImageUrlValue(Object value) {
        if (value instanceof String text) {
            String normalized = normalizeCandidateText(text);
            return isRealGeneratedImageUrl(normalized) ? normalized : null;
        }
        if (!(value instanceof java.util.List<?> list)) {
            return null;
        }
        String fallback = null;
        for (Object item : list) {
            String candidate = generatedImageUrlValue(item);
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            if (candidate.contains("lh3.googleusercontent.com/gg-dl/")) {
                return candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    private static boolean isRealGeneratedImageUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("image_generation_content")) {
            return false;
        }
        return lower.startsWith("https://lh3.googleusercontent.com/gg-dl/")
                || lower.startsWith("http://lh3.googleusercontent.com/gg-dl/");
    }

    private static String joinedStringArray(Object value) {
        if (!(value instanceof java.util.List<?> list)) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Object item : list) {
            if (item instanceof String text) {
                out.append(text);
            }
        }
        return normalizeCandidateText(out.toString());
    }

    private static String deepestUsefulText(Object value) {
        String best = "";
        if (value instanceof String text) {
            text = normalizeCandidateText(text);
            return isAnswerText(text) ? text : "";
        }
        if (value instanceof java.util.List<?> list) {
            for (Object item : list) {
                String candidate = deepestUsefulText(item);
                if (candidate.length() > best.length()) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static Object listValue(java.util.List<?> list, int index) {
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }

    private static boolean isChoiceId(String value) {
        return value != null && value.startsWith("rc_");
    }

    private static boolean isAnswerText(String value) {
        return value != null && !value.isBlank() && isUsefulText(value);
    }

    private static String bestTextInJsonPayload(String payload) {
        String best = null;
        int index = 0;
        while (index < payload.length()) {
            int quote = payload.indexOf('"', index);
            if (quote < 0) {
                break;
            }

            JsonString jsonString = readJsonString(payload, quote);
            if (jsonString == null) {
                break;
            }
            index = jsonString.endIndex();

            if (nextNonWhitespace(payload, jsonString.endIndex()) == ':') {
                continue;
            }

            String value = normalizeCandidateText(jsonString.value());
            if (!isUsefulText(value)) {
                continue;
            }
            if (best == null || scoreText(value) > scoreText(best)) {
                best = value;
            }
        }
        return best;
    }

    private static char nextNonWhitespace(String value, int index) {
        while (index < value.length()) {
            char c = value.charAt(index);
            if (!Character.isWhitespace(c)) {
                return c;
            }
            index++;
        }
        return '\0';
    }

    private static String normalizeCandidateText(String value) {
        String text = value == null ? "" : value.trim();
        text = text.replace("\u0000", "");
        text = text.replaceFirst("(?i)^\\d*person_cancel[，,：:\\s-]*", "");
        text = text.replaceFirst("(?i)^\\d*rson_cancel[，,：:\\s-]*", "");
        text = text.replaceFirst("^\\d+[A-Za-z_]{4,}[，,：:\\s-]+", "");
        return text.trim();
    }

    private static String wrbPayloadAt(String body, int marker) {
        int index = marker + "\"wrb.fr\"".length();
        int commaCount = 0;
        while (index < body.length()) {
            char c = body.charAt(index);
            if (c == ',') {
                commaCount++;
                index++;
                if (commaCount == 2) {
                    break;
                }
                continue;
            }
            if (c == '"') {
                JsonString ignored = readJsonString(body, index);
                if (ignored == null) {
                    return null;
                }
                index = ignored.endIndex();
                continue;
            }
            index++;
        }

        while (index < body.length() && Character.isWhitespace(body.charAt(index))) {
            index++;
        }
        if (index >= body.length() || body.charAt(index) != '"') {
            return null;
        }

        JsonString payload = readJsonString(body, index);
        return payload == null ? null : payload.value();
    }

    private static boolean isUsefulText(String value) {
        if (value.isBlank()) {
            return false;
        }
        if (value.matches("\\d+")) {
            return false;
        }
        if (value.toLowerCase(Locale.ROOT).contains("person_cancel")
                || value.toLowerCase(Locale.ROOT).contains("rson_cancel")) {
            return false;
        }
        if (value.startsWith("c_") || value.startsWith("r_") || value.startsWith("rc_")) {
            return false;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return false;
        }
        return !(value.length() > 24 && value.matches("[A-Za-z0-9_./:=-]+"));
    }

    private static int scoreText(String value) {
        int score = value.length();
        if (value.matches(".*[\\p{IsHan}\\p{Punct}\\s].*")) {
            score += 40;
        }
        return score;
    }

    private static String preview(String value) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= 240 ? text : text.substring(0, 240) + "...";
    }

    private static String joinArgs(String[] args, int fromIndex) {
        StringBuilder out = new StringBuilder();
        for (int i = fromIndex; i < args.length; i++) {
            if (i > fromIndex) {
                out.append(' ');
            }
            out.append(args[i]);
        }
        return out.toString();
    }

    private static String jsonUnquote(String quoted) {
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < quoted.length() - 1; i++) {
            char c = quoted.charAt(i);
            if (c != '\\' || i + 1 >= quoted.length() - 1) {
                out.append(c);
                continue;
            }
            char next = quoted.charAt(++i);
            switch (next) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 < quoted.length()) {
                        out.append((char) Integer.parseInt(quoted.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    private static JsonString readJsonString(String value, int quoteIndex) {
        if (quoteIndex < 0 || quoteIndex >= value.length() || value.charAt(quoteIndex) != '"') {
            return null;
        }

        StringBuilder out = new StringBuilder();
        for (int i = quoteIndex + 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                return new JsonString(out.toString(), i + 1);
            }
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }

            char escaped = value.charAt(++i);
            switch (escaped) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 < value.length()) {
                        out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> out.append(escaped);
            }
        }
        return null;
    }

    private record JsonString(String value, int endIndex) {
    }

    private static String jsonQuote(String value) {
        StringBuilder out = new StringBuilder();
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        out.append('"');
        return out.toString();
    }

    private static String formDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return jsonQuote(text);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ > 0) {
                    out.append(',');
                }
                out.append(jsonQuote(String.valueOf(entry.getKey())))
                        .append(':')
                        .append(toJson(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof java.util.List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(toJson(list.get(i)));
            }
            return out.append(']').toString();
        }
        return jsonQuote(String.valueOf(value));
    }

    private static String shellUnescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }

            char next = value.charAt(++i);
            switch (next) {
                case '\\' -> out.append('\\');
                case '\'' -> out.append('\'');
                case '"' -> out.append('"');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                default -> {
                    out.append('\\');
                    out.append(next);
                }
            }
        }
        return out.toString();
    }

    private static final class ProxySettingsManager implements OpenAiApiServer.ProxySettingsBackend {
        private static final Path SETTINGS_PATH = Path.of("data", "proxy-settings.properties");
        private final AppConfig config;
        private final AtomicReference<OkHttpClient> clientRef;

        private ProxySettingsManager(AppConfig config, AtomicReference<OkHttpClient> clientRef) throws IOException {
            this.config = config;
            this.clientRef = clientRef;
            loadPersisted();
        }

        @Override
        public synchronized String getJson() {
            return json();
        }

        @Override
        public synchronized String update(String body) throws IOException {
            Object parsed = SimpleJson.parse(body == null ? "" : body);
            if (!(parsed instanceof Map<?, ?> values)) {
                throw new IllegalArgumentException("请求体必须是 JSON object");
            }

            boolean enabled = objectBooleanValue(values.get("enabled"), config.proxyEnabled);
            String type = objectStringValue(values.get("type"), config.proxyType).trim().toLowerCase(Locale.ROOT);
            String host = objectStringValue(values.get("host"), config.proxyHost).trim();
            int port = objectIntValue(values.get("port"), config.proxyPort);
            if (!type.equals("http") && !type.equals("socks")) {
                throw new IllegalArgumentException("代理类型只能是 http 或 socks");
            }
            if (host.isBlank()) {
                throw new IllegalArgumentException("代理地址不能为空");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("代理端口必须在 1-65535 之间");
            }

            config.proxyEnabled = enabled;
            config.proxyType = type;
            config.proxyHost = host;
            config.proxyPort = port;
            savePersisted();
            clientRef.set(buildHttpClient(config));
            restartChromium();
            return json();
        }

        private void restartChromium() {
            try {
                Process process = new ProcessBuilder(
                        "/usr/bin/supervisorctl", "-c", "/etc/supervisor/supervisord.conf",
                        "restart", "gemini-chrome")
                        .redirectErrorStream(true)
                        .start();
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    System.err.println("代理已更新，但重启 Chromium 超时");
                    return;
                }
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (process.exitValue() != 0) {
                    System.err.println("代理已更新，但重启 Chromium 失败：" + output);
                }
            } catch (Exception e) {
                System.err.println("代理已更新，但无法自动重启 Chromium：" + e.getMessage());
            }
        }

        private void loadPersisted() throws IOException {
            if (!Files.exists(SETTINGS_PATH)) {
                return;
            }
            Properties properties = new Properties();
            try (java.io.Reader reader = Files.newBufferedReader(SETTINGS_PATH, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            config.proxyEnabled = AppConfig.booleanValue(properties.getProperty("enabled"), config.proxyEnabled);
            String type = properties.getProperty("type", config.proxyType).trim().toLowerCase(Locale.ROOT);
            if (type.equals("http") || type.equals("socks")) {
                config.proxyType = type;
            }
            String host = properties.getProperty("host", config.proxyHost).trim();
            if (!host.isBlank()) {
                config.proxyHost = host;
            }
            int port = AppConfig.intValue(properties.getProperty("port"), config.proxyPort);
            if (port >= 1 && port <= 65535) {
                config.proxyPort = port;
            }
        }

        private void savePersisted() throws IOException {
            Files.createDirectories(SETTINGS_PATH.getParent());
            Properties properties = new Properties();
            properties.setProperty("enabled", Boolean.toString(config.proxyEnabled));
            properties.setProperty("type", config.proxyType);
            properties.setProperty("host", config.proxyHost);
            properties.setProperty("port", Integer.toString(config.proxyPort));
            try (java.io.Writer writer = Files.newBufferedWriter(SETTINGS_PATH, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(writer, "WebToAPI proxy settings");
            }
        }

        private String json() {
            return "{\"enabled\":" + config.proxyEnabled
                    + ",\"type\":" + SimpleJson.quote(config.proxyType)
                    + ",\"host\":" + SimpleJson.quote(config.proxyHost)
                    + ",\"port\":" + config.proxyPort + "}";
        }

        private static boolean objectBooleanValue(Object value, boolean fallback) {
            if (value instanceof Boolean bool) {
                return bool;
            }
            if (value instanceof String text) {
                return AppConfig.booleanValue(text, fallback);
            }
            return fallback;
        }

        private static String objectStringValue(Object value, String fallback) {
            return value instanceof String text ? text : fallback;
        }

        private static int objectIntValue(Object value, int fallback) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String text) {
                return AppConfig.intValue(text, fallback);
            }
            return fallback;
        }
    }

    private static final class AppConfig {
        private boolean proxyEnabled;
        private String proxyType = "http";
        private String proxyHost = "127.0.0.1";
        private int proxyPort = 7890;
        private boolean openAiEnabled = true;
        private String openAiHost = "127.0.0.1";
        private int openAiPort = 8080;
        private String curl;
        private ClaudeCookieStore claudeCookieStore;
        private GeminiCookieProvider geminiCookieProvider;
        private boolean geminiCookieEnabled = true;
        private boolean geminiCookieLaunchChrome = true;
        private String geminiCookieChromeBinary = "google-chrome";
        private String geminiCookieUserDataDir =
                Path.of(System.getProperty("user.home"), ".config", "google-chrome-debug").toString();
        private String geminiCookieHost = "127.0.0.1";
        private int geminiCookiePort = 9222;
        private String geminiCookiePageUrl = "https://gemini.google.com/app";
        private String geminiCookieUrls = "https://gemini.google.com,https://gemini.google.com/app,https://google.com";
        private String chatGptAccessToken = "";
        private String chatGptModel = "auto";
        private ChatGptCurlStore chatGptCurlStore;

        private static AppConfig load(Path path) throws IOException {
            if (!Files.exists(path)) {
                Path resource = Path.of("src/main/resources/config.yml");
                if (Files.exists(resource)) {
                    Files.copy(resource, path);
                }
                throw new IOException("已生成 config.yml。把 StreamGenerate 的完整 curl 粘到 curl: 后再运行。");
            }

            String text = Files.readString(path);
            AppConfig config = new AppConfig();
            config.proxyEnabled = booleanValue(sectionValue(text, "proxy", "enabled"), false);
            config.proxyType = stringValue(sectionValue(text, "proxy", "type"), config.proxyType);
            config.proxyHost = stringValue(sectionValue(text, "proxy", "host"), config.proxyHost);
            config.proxyPort = intValue(sectionValue(text, "proxy", "port"), config.proxyPort);
            config.openAiEnabled = booleanValue(sectionValue(text, "openai", "enabled"), config.openAiEnabled);
            config.openAiHost = stringValue(sectionValue(text, "openai", "host"), config.openAiHost);
            config.openAiPort = intValue(sectionValue(text, "openai", "port"), config.openAiPort);
            config.chatGptAccessToken = stringValue(sectionValue(text, "chatgpt", "access_token"), "");
            config.chatGptModel = stringValue(sectionValue(text, "chatgpt", "model"), config.chatGptModel);
            config.geminiCookieEnabled = booleanValue(
                    sectionValue(text, "gemini_cookie", "enabled"),
                    config.geminiCookieEnabled);
            config.geminiCookieLaunchChrome = booleanValue(
                    sectionValue(text, "gemini_cookie", "launch_chrome"),
                    config.geminiCookieLaunchChrome);
            config.geminiCookieChromeBinary = stringValue(
                    sectionValue(text, "gemini_cookie", "chrome_binary"),
                    config.geminiCookieChromeBinary);
            config.geminiCookieUserDataDir = expandHome(stringValue(
                    sectionValue(text, "gemini_cookie", "user_data_dir"),
                    config.geminiCookieUserDataDir));
            config.geminiCookieHost = stringValue(
                    sectionValue(text, "gemini_cookie", "debug_host"),
                    config.geminiCookieHost);
            config.geminiCookiePort = intValue(
                    sectionValue(text, "gemini_cookie", "debug_port"),
                    config.geminiCookiePort);
            config.geminiCookiePageUrl = stringValue(
                    sectionValue(text, "gemini_cookie", "page_url"),
                    config.geminiCookiePageUrl);
            config.geminiCookieUrls = stringValue(
                    sectionValue(text, "gemini_cookie", "cookie_urls"),
                    config.geminiCookieUrls);
            // Environment overrides make the browser/CDP sidecar configurable without
            // editing the mounted curl configuration inside a container.
            config.geminiCookieEnabled = booleanEnv("GEMINI_COOKIE_ENABLED", config.geminiCookieEnabled);
            config.geminiCookieLaunchChrome = booleanEnv(
                    "GEMINI_COOKIE_LAUNCH_CHROME", config.geminiCookieLaunchChrome);
            config.geminiCookieChromeBinary = stringEnv(
                    "GEMINI_COOKIE_CHROME_BINARY", config.geminiCookieChromeBinary);
            config.geminiCookieHost = stringEnv("GEMINI_COOKIE_DEBUG_HOST", config.geminiCookieHost);
            config.geminiCookiePort = intEnv("GEMINI_COOKIE_DEBUG_PORT", config.geminiCookiePort);
            config.geminiCookiePageUrl = stringEnv("GEMINI_COOKIE_PAGE_URL", config.geminiCookiePageUrl);
            config.proxyEnabled = booleanEnv("PROXY_ENABLED", config.proxyEnabled);
            config.proxyType = stringEnv("PROXY_TYPE", config.proxyType);
            config.proxyHost = stringEnv("PROXY_HOST", config.proxyHost);
            config.proxyPort = intEnv("PROXY_PORT", config.proxyPort);
            config.openAiEnabled = booleanEnv("OPENAI_ENABLED", config.openAiEnabled);
            config.openAiHost = stringEnv("OPENAI_HOST", config.openAiHost);
            config.openAiPort = intEnv("OPENAI_PORT", config.openAiPort);
            config.chatGptAccessToken = stringEnv("CHATGPT_ACCESS_TOKEN", config.chatGptAccessToken);
            config.chatGptModel = stringEnv("CHATGPT_MODEL", config.chatGptModel);
            config.curl = extractCurl(text);
            if (!hasCurlText(config.curl)) {
                throw new IOException("config.yml 里没有 curl 内容。把完整 StreamGenerate curl 粘到 curl: 后面。");
            }
            return config;
        }

        private void refreshGeminiCookie(CurlRequest curl) throws IOException {
            if (geminiCookieProvider == null) {
                return;
            }
            geminiCookieProvider.applyTo(curl);
        }

        private static String expandHome(String value) {
            if (value == null || value.isBlank()) {
                return value;
            }
            if (value.equals("~")) {
                return System.getProperty("user.home");
            }
            if (value.startsWith("~/")) {
                return System.getProperty("user.home") + value.substring(1);
            }
            return value;
        }

        private static String extractCurl(String text) {
            return extractNamedCurl(text, "curl");
        }

        private static String extractNamedCurl(String text, String key) {
            String[] lines = text.split("\\R", -1);
            int curlLine = -1;
            for (int i = 0; i < lines.length; i++) {
                String trimmed = lines[i].trim();
                if (trimmed.startsWith(key + ":")) {
                    curlLine = i;
                    break;
                }
            }
            if (curlLine < 0) {
                return "";
            }

            String line = lines[curlLine];
            String first = line.substring(line.indexOf(':') + 1).trim();
            StringBuilder rest = new StringBuilder();
            for (int i = curlLine + 1; i < lines.length; i++) {
                if (isTopLevelConfigKey(lines[i])) {
                    break;
                }
                rest.append(lines[i]).append('\n');
            }

            if (first.equals("|") || first.equals("|-") || first.equals(">")) {
                return stripIndent(rest.toString()).trim();
            }
            return trimConfigCurlText(first + "\n" + rest);
        }

        private static boolean isTopLevelConfigKey(String line) {
            String trimmed = line.trim();
            return !trimmed.isEmpty()
                    && !trimmed.startsWith("#")
                    && !line.startsWith(" ")
                    && Pattern.compile("^[A-Za-z0-9_-]+:\\s*.*$").matcher(trimmed).matches();
        }

        private static boolean hasCurlText(String value) {
            if (value == null || value.isBlank()) {
                return false;
            }
            String trimmed = trimConfigCurlText(value);
            return trimmed.startsWith("curl ")
                    || trimmed.startsWith("url ")
                    || trimmed.startsWith("http://")
                    || trimmed.startsWith("https://");
        }

        private static String trimConfigCurlText(String value) {
            String[] lines = value.split("\\R", -1);
            int start = 0;
            int end = lines.length;
            while (start < end && isIgnorableConfigLine(lines[start])) {
                start++;
            }
            while (end > start && isIgnorableConfigLine(lines[end - 1])) {
                end--;
            }

            StringBuilder out = new StringBuilder();
            for (int i = start; i < end; i++) {
                out.append(lines[i]);
                if (i + 1 < end) {
                    out.append('\n');
                }
            }
            return out.toString().trim();
        }

        private static boolean isIgnorableConfigLine(String line) {
            String trimmed = line.trim();
            return trimmed.isEmpty() || trimmed.startsWith("#");
        }

        private static String stripIndent(String value) {
            String[] lines = value.split("\\R", -1);
            StringBuilder out = new StringBuilder();
            for (String line : lines) {
                out.append(line.startsWith("  ") ? line.substring(2) : line).append('\n');
            }
            return out.toString();
        }

        private static boolean booleanValue(String value, boolean fallback) {
            return value == null ? fallback : Boolean.parseBoolean(value);
        }

        private static String stringValue(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }

        private static int intValue(String value, int fallback) {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
        }

        private static String stringEnv(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        private static boolean booleanEnv(String name, boolean fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
        }

        private static int intEnv(String name, int fallback) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static String sectionValue(String text, String section, String key) {
            boolean inSection = false;
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (!line.startsWith(" ") && trimmed.endsWith(":")) {
                    inSection = trimmed.equals(section + ":");
                    continue;
                }
                if (!inSection || !trimmed.startsWith(key + ":")) {
                    continue;
                }
                String value = trimmed.substring(key.length() + 1).trim();
                int comment = value.indexOf('#');
                if (comment >= 0) {
                    value = value.substring(0, comment).trim();
                }
                return value;
            }
            return null;
        }
    }

    private static final class GeminiCookieProvider {
        private static final Duration CHROME_START_TIMEOUT = Duration.ofSeconds(12);
        private static final Duration WEBSOCKET_TIMEOUT = Duration.ofSeconds(10);

        private final OkHttpClient client;
        private final boolean launchChrome;
        private final String chromeBinary;
        private final String userDataDir;
        private final String host;
        private final int port;
        private final String pageUrl;
        private final java.util.List<String> cookieUrls;
        private boolean launchAttempted;

        private GeminiCookieProvider(AppConfig config) {
            this.client = new OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .readTimeout(Duration.ofSeconds(10))
                    .writeTimeout(Duration.ofSeconds(10))
                    .proxy(Proxy.NO_PROXY)
                    .build();
            this.launchChrome = config.geminiCookieLaunchChrome;
            this.chromeBinary = config.geminiCookieChromeBinary;
            this.userDataDir = config.geminiCookieUserDataDir;
            this.host = config.geminiCookieHost;
            this.port = config.geminiCookiePort;
            this.pageUrl = config.geminiCookiePageUrl;
            this.cookieUrls = parseCookieUrls(config.geminiCookieUrls);
        }

        private static GeminiCookieProvider create(AppConfig config) {
            return config.geminiCookieEnabled ? new GeminiCookieProvider(config) : null;
        }

        private void applyTo(CurlRequest curl) throws IOException {
            GeminiCredentials credentials = fetchCredentials();
            curl.setCookie(credentials.cookie());
            curl.setAtToken(credentials.snLm0e());
        }

        private GeminiCredentials fetchCredentials() throws IOException {
            ensureChrome();
            String wsUrl = geminiWebSocketUrl();
            String response = sendWebSocketCommand(wsUrl, cookieCommandJson(), 1);
            String tokenResponse = sendWebSocketCommand(wsUrl, tokenCommandJson(), 2);
            String token = snLm0eToken(tokenResponse);
            if (token.isBlank() || "not_found".equals(token)) {
                throw new IOException("Cookie 已获取，但页面里没有 SNlM0e。请确认 Chrome 的 Gemini 标签页处于已登录状态。");
            }
            Object parsed = SimpleJson.parse(response);
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IOException("Chrome DevTools 返回了无法解析的 Cookie 响应");
            }
            Object result = map.get("result");
            if (!(result instanceof Map<?, ?> resultMap)) {
                throw new IOException("Chrome DevTools Cookie 响应缺少 result：" + preview(response));
            }
            Object cookies = resultMap.get("cookies");
            if (!(cookies instanceof java.util.List<?> cookieList)) {
                throw new IOException("Chrome DevTools Cookie 响应缺少 cookies：" + preview(response));
            }

            LinkedHashMap<String, String> dedup = new LinkedHashMap<>();
            for (Object item : cookieList) {
                if (!(item instanceof Map<?, ?> cookieMap)) {
                    continue;
                }
                String name = stringObject(cookieMap.get("name"));
                String value = stringObject(cookieMap.get("value"));
                if (!name.isBlank()) {
                    dedup.put(name, value);
                }
            }

            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, String> entry : dedup.entrySet()) {
                if (out.length() > 0) {
                    out.append("; ");
                }
                out.append(entry.getKey()).append('=').append(entry.getValue());
            }
            String cookie = out.toString();
            if (cookie.isBlank()) {
                throw new IOException("已连接 Chrome " + port + "，但 Gemini/Google Cookie 为空。请在拉起的 Chrome 里登录 Gemini。");
            }
            return new GeminiCredentials(cookie, token);
        }

        private void ensureChrome() throws IOException {
            if (debugEndpointReady()) {
                return;
            }
            if (!launchChrome) {
                throw new IOException("Chrome DevTools 端口不可用：http://" + host + ":" + port + "/json");
            }

            launchChrome();
            long deadline = System.nanoTime() + CHROME_START_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (debugEndpointReady()) {
                    return;
                }
                sleepQuietly(250);
            }
            throw new IOException("已尝试启动 Chrome，但 DevTools 端口仍不可用：http://" + host + ":" + port + "/json");
        }

        private boolean debugEndpointReady() {
            Request request = new Request.Builder()
                    .url(debugJsonUrl())
                    .get()
                    .build();
            try (Response response = client.newCall(request).execute()) {
                return response.isSuccessful();
            } catch (IOException ignored) {
                return false;
            }
        }

        private void launchChrome() throws IOException {
            if (launchAttempted) {
                return;
            }
            launchAttempted = true;
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add(chromeBinary);
            command.add("--remote-debugging-port=" + port);
            command.add("--user-data-dir=" + userDataDir);
            command.add("--remote-allow-origins=*");
            command.add("--disable-vulkan");
            if (pageUrl != null && !pageUrl.isBlank()) {
                command.add(pageUrl);
            }
            new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        }

        private String geminiWebSocketUrl() throws IOException {
            IOException firstError = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    String wsUrl = findGeminiWebSocketUrl();
                    if (!wsUrl.isBlank()) {
                        return wsUrl;
                    }
                } catch (IOException e) {
                    firstError = e;
                }
                if (launchChrome && attempt == 0) {
                    launchAttempted = false;
                    launchChrome();
                    sleepQuietly(1000);
                }
            }
            if (firstError != null) {
                throw firstError;
            }
            throw new IOException("Chrome " + port + " 中没有找到 Gemini 页面。请在自动拉起的 Chrome 里打开并登录 "
                    + pageUrl);
        }

        private String findGeminiWebSocketUrl() throws IOException {
            Request request = new Request.Builder()
                    .url(debugJsonUrl())
                    .get()
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("读取 Chrome 页面列表失败，HTTP 状态=" + response.code());
                }
                Object parsed = SimpleJson.parse(response.body().string());
                if (!(parsed instanceof java.util.List<?> pages)) {
                    throw new IOException("Chrome 页面列表格式异常");
                }
                for (Object page : pages) {
                    if (!(page instanceof Map<?, ?> map)) {
                        continue;
                    }
                    String type = stringObject(map.get("type"));
                    String title = stringObject(map.get("title"));
                    String url = stringObject(map.get("url"));
                    String wsUrl = stringObject(map.get("webSocketDebuggerUrl"));
                    if ("page".equals(type)
                            && !wsUrl.isBlank()
                            && (url.contains("gemini.google.com") || title.contains("Gemini"))) {
                        return wsUrl;
                    }
                }
            }
            return "";
        }

        private String sendWebSocketCommand(String wsUrl, String command, long expectedId) throws IOException {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> message = new AtomicReference<>("");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Request request = new Request.Builder().url(wsUrl).build();
            WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    webSocket.send(command);
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    Object parsed = SimpleJson.parse(text);
                    if (parsed instanceof Map<?, ?> map && Objects.equals(map.get("id"), expectedId)) {
                        message.set(text);
                        webSocket.close(1000, "done");
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    failure.set(t);
                    latch.countDown();
                }
            });

            try {
                if (!latch.await(WEBSOCKET_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    ws.cancel();
                    throw new IOException("等待 Chrome DevTools Cookie 响应超时");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ws.cancel();
                throw new IOException("等待 Chrome DevTools Cookie 响应被中断", e);
            }
            if (failure.get() != null) {
                throw new IOException("连接 Chrome DevTools WebSocket 失败：" + failure.get().getMessage(), failure.get());
            }
            if (message.get().isBlank()) {
                throw new IOException("Chrome DevTools 没有返回 Cookie 响应");
            }
            return message.get();
        }

        private String cookieCommandJson() {
            StringBuilder out = new StringBuilder();
            out.append("{\"id\":1,\"method\":\"Network.getCookies\",\"params\":{\"urls\":[");
            for (int i = 0; i < cookieUrls.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(SimpleJson.quote(cookieUrls.get(i)));
            }
            return out.append("]}}").toString();
        }

        private String tokenCommandJson() {
            return "{\"id\":2,\"method\":\"Runtime.evaluate\",\"params\":{\"expression\":"
                    + SimpleJson.quote("window.WIZ_global_data && window.WIZ_global_data.SNlM0e ? window.WIZ_global_data.SNlM0e : \"not_found\"")
                    + "}}";
        }

        private String snLm0eToken(String response) throws IOException {
            Object parsed = SimpleJson.parse(response);
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IOException("Chrome DevTools 返回了无法解析的 SNlM0e 响应");
            }
            Object result = map.get("result");
            if (!(result instanceof Map<?, ?> resultMap)) {
                throw new IOException("Chrome DevTools SNlM0e 响应缺少 result：" + preview(response));
            }
            Object nested = resultMap.get("result");
            if (!(nested instanceof Map<?, ?> nestedMap)) {
                throw new IOException("Chrome DevTools SNlM0e 响应缺少 result.value：" + preview(response));
            }
            return stringObject(nestedMap.get("value"));
        }

        private String debugJsonUrl() {
            return "http://" + host + ":" + port + "/json";
        }

        private static java.util.List<String> parseCookieUrls(String value) {
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            for (String part : (value == null ? "" : value).split(",")) {
                String url = part.trim();
                if (!url.isBlank()) {
                    out.add(url);
                }
            }
            if (out.isEmpty()) {
                out.add("https://gemini.google.com");
                out.add("https://gemini.google.com/app");
                out.add("https://google.com");
            }
            return java.util.List.copyOf(out);
        }

        private static String stringObject(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        private record GeminiCredentials(String cookie, String snLm0e) {
        }
    }

    private static final class CurlRequest {
        private HttpUrl url;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String cookie = "";
        private String form = "";
        private String originalPrompt = "";

        private static CurlRequest parse(String curl) throws IOException {
            java.util.List<String> tokens = shellTokens(curl);
            CurlRequest request = new CurlRequest();
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                if ("curl".equals(token) || "url".equals(token)) {
                    continue;
                }
                if (request.url == null && (token.startsWith("https://") || token.startsWith("http://"))) {
                    request.url = HttpUrl.get(token);
                    continue;
                }
                if (("-H".equals(token) || "--header".equals(token)) && i + 1 < tokens.size()) {
                    addHeader(request.headers, tokens.get(++i));
                    continue;
                }
                if (("-b".equals(token) || "--cookie".equals(token)) && i + 1 < tokens.size()) {
                    request.cookie = tokens.get(++i);
                    continue;
                }
                if (("-d".equals(token) || "--data".equals(token) || "--data-raw".equals(token)
                        || "--data-binary".equals(token) || "--data-ascii".equals(token)) && i + 1 < tokens.size()) {
                    request.form = tokens.get(++i);
                    continue;
                }
                if (token.startsWith("--data-raw=") || token.startsWith("--data=")
                        || token.startsWith("--data-binary=") || token.startsWith("--data-ascii=")) {
                    request.form = token.substring(token.indexOf('=') + 1);
                }
            }

            if (request.url == null) {
                throw new IOException("curl 里没有解析到 URL");
            }
            request.form = required(request.form, "form body");
            request.originalPrompt = readPromptFromForm(request.form);
            if (!request.cookie.isBlank()) {
                request.headers.put("Cookie", request.cookie);
            }
            return request;
        }

        private void setCookie(String cookie) {
            this.cookie = cookie == null ? "" : cookie.trim();
            if (this.cookie.isBlank()) {
                headers.remove("Cookie");
            } else {
                headers.put("Cookie", this.cookie);
            }
        }

        private void setAtToken(String token) {
            String value = token == null ? "" : token.trim();
            if (value.isBlank()) {
                return;
            }
            form = setFormValue(form, "at", value);
        }

        private Request toRequest() {
            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(form, FORM_MEDIA_TYPE));

            headers.forEach((name, value) -> {
                String lower = name.toLowerCase();
                if (!"host".equals(lower) && !"content-length".equals(lower)) {
                    builder.header(name, value);
                }
            });
            return builder.build();
        }

        private void replacePrompt(String prompt) {
            String fReq = queryValue(form, "f.req");
            if (fReq == null || fReq.isBlank()) {
                return;
            }

            String updated = replaceFirstPromptInFReq(fReq, prompt);
            form = replaceFormValue(form, "f.req", updated);
            originalPrompt = prompt;
        }

        private void replaceImages(java.util.List<GeminiAttachment> attachments) {
            String fReq = queryValue(form, "f.req");
            if (fReq == null || fReq.isBlank()) {
                return;
            }

            form = replaceFormValue(form, "f.req", replaceImagesInFReq(fReq, attachments));
        }

        private okhttp3.Headers uploadStartHeaders(int size) {
            okhttp3.Headers.Builder out = baseUploadHeaders(size)
                    .add("x-goog-upload-command", "start");
            return out.build();
        }

        private okhttp3.Headers uploadFinalizeHeaders() {
            okhttp3.Headers.Builder out = baseUploadHeaders(-1)
                    .add("x-goog-upload-command", "upload, finalize")
                    .add("x-goog-upload-offset", "0");
            return out.build();
        }

        private okhttp3.Headers.Builder baseUploadHeaders(int size) {
            okhttp3.Headers.Builder out = new okhttp3.Headers.Builder()
                    .add("accept", firstNonBlank(headers.get("accept"), "*/*"))
                    .add("accept-language", firstNonBlank(headers.get("accept-language"), "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7"))
                    .add("cache-control", "no-cache")
                    .add("content-type", "application/x-www-form-urlencoded;charset=utf-8")
                    .add("cookie", cookie)
                    .add("origin", "https://gemini.google.com")
                    .add("pragma", "no-cache")
                    .add("push-id", "feeds/mcudyrk2a4khkz")
                    .add("referer", "https://gemini.google.com/")
                    .add("sec-fetch-dest", "empty")
                    .add("sec-fetch-mode", "cors")
                    .add("sec-fetch-site", "same-site")
                    .add("user-agent", firstNonBlank(headers.get("user-agent"), "Mozilla/5.0"))
                    .add("x-tenant-id", "bard-storage");
            copyHeader(out, "x-browser-channel");
            copyHeader(out, "x-browser-copyright");
            copyHeader(out, "x-browser-validation");
            copyHeader(out, "x-browser-year");
            copyHeader(out, "x-client-data");
            copyHeader(out, "x-client-pctx");
            if (size >= 0) {
                out.add("x-goog-upload-header-content-length", String.valueOf(size));
                out.add("x-goog-upload-protocol", "resumable");
            }
            return out;
        }

        private void copyHeader(okhttp3.Headers.Builder out, String name) {
            String value = headers.get(name);
            if (value != null && !value.isBlank()) {
                out.add(name, value);
            }
        }

        private static String firstNonBlank(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }

        private void applyModel(ModelProfile profile) {
            if (profile == null) {
                return;
            }
            applyHeaderModelPatch(profile);
            applyFormModelPatch(profile);
        }

        private void applyHeaderModelPatch(ModelProfile profile) {
            String value = headers.get("x-goog-ext-525001261-jspb");
            if (value == null || value.isBlank()) {
                return;
            }

            String updated = value;
            if (profile.headerToken() != null) {
                updated = replaceTopLevelElement(updated, 4, jsonQuote(profile.headerToken()));
            }
            if (profile.headerMode() != null) {
                updated = replaceTopLevelElement(updated, 14, String.valueOf(profile.headerMode()));
            }
            headers.put("x-goog-ext-525001261-jspb", updated);
        }

        private void applyFormModelPatch(ModelProfile profile) {
            String fReq = queryValue(form, "f.req");
            if (fReq == null || fReq.isBlank()) {
                return;
            }

            String updated = applyModelToFReq(fReq, profile);
            form = replaceFormValue(form, "f.req", updated);
            originalPrompt = readPromptFromForm(form);
        }

        private void prepareForConversation(ConversationState conversation) {
            url = url.newBuilder()
                    .setQueryParameter("_reqid", String.valueOf(ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999)))
                    .build();

            refreshPerRequestHeaders();

            String fReq = queryValue(form, "f.req");
            if (fReq == null || fReq.isBlank()) {
                return;
            }

            String updated = applyConversationToFReq(fReq, conversation);
            form = replaceFormValue(form, "f.req", updated);
            originalPrompt = readPromptFromForm(form);
        }

        private String originalPrompt() {
            return originalPrompt == null ? "" : originalPrompt;
        }

        private static String readPromptFromForm(String form) {
            String fReq = queryValue(form, "f.req");
            if (fReq == null || fReq.isBlank()) {
                return "";
            }
            String prompt = firstPrompt(fReq);
            return prompt == null ? "" : prompt;
        }

        private static String firstPrompt(String fReq) {
            int outerQuote = fReq.indexOf('"');
            if (outerQuote < 0) {
                return null;
            }
            JsonString innerJson = readJsonString(fReq, outerQuote);
            if (innerJson == null) {
                return null;
            }

            String inner = innerJson.value();
            int promptQuote = inner.indexOf('"');
            if (promptQuote < 0) {
                return null;
            }

            JsonString prompt = readJsonString(inner, promptQuote);
            return prompt == null ? null : prompt.value();
        }

        private static String replaceFirstPromptInFReq(String fReq, String prompt) {
            int outerQuote = fReq.indexOf('"');
            if (outerQuote < 0) {
                return fReq;
            }
            JsonString innerJson = readJsonString(fReq, outerQuote);
            if (innerJson == null) {
                return fReq;
            }

            String inner = innerJson.value();
            int promptQuote = inner.indexOf('"');
            if (promptQuote < 0) {
                return fReq;
            }
            JsonString oldPrompt = readJsonString(inner, promptQuote);
            if (oldPrompt == null) {
                return fReq;
            }

            String updatedInner = inner.substring(0, promptQuote)
                    + jsonQuote(prompt)
                    + inner.substring(oldPrompt.endIndex());
            return fReq.substring(0, outerQuote)
                    + jsonQuote(updatedInner)
                    + fReq.substring(innerJson.endIndex());
        }

        private static String replaceImagesInFReq(String fReq, java.util.List<GeminiAttachment> attachments) {
            int outerQuote = fReq.indexOf('"');
            if (outerQuote < 0) {
                return fReq;
            }
            JsonString innerJson = readJsonString(fReq, outerQuote);
            if (innerJson == null) {
                return fReq;
            }

            String inner = innerJson.value();
            Range message = topLevelElementBounds(inner, 0);
            if (message == null) {
                return fReq;
            }
            String updatedMessage = replaceTopLevelElement(
                    inner.substring(message.start(), message.end()),
                    3,
                    attachmentsJson(attachments));
            String updatedInner = inner.substring(0, message.start())
                    + updatedMessage
                    + inner.substring(message.end());
            return fReq.substring(0, outerQuote)
                    + jsonQuote(updatedInner)
                    + fReq.substring(innerJson.endIndex());
        }

        private static String attachmentsJson(java.util.List<GeminiAttachment> attachments) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < attachments.size(); i++) {
                GeminiAttachment attachment = attachments.get(i);
                if (i > 0) {
                    out.append(',');
                }
                out.append("[[")
                        .append(jsonQuote(attachment.path()))
                        .append(",1,null,")
                        .append(jsonQuote(attachment.mimeType()))
                        .append("],")
                        .append(jsonQuote(attachment.filename()))
                        .append("]");
            }
            return out.append(']').toString();
        }

        private static String applyConversationToFReq(String fReq, ConversationState conversation) {
            int outerQuote = fReq.indexOf('"');
            if (outerQuote < 0) {
                return fReq;
            }
            JsonString innerJson = readJsonString(fReq, outerQuote);
            if (innerJson == null) {
                return fReq;
            }

            String inner = innerJson.value();
            String stateArray = conversation.isActive()
                    ? jsonConversationArray(conversation.conversationId(), conversation.responseId(), conversation.choiceId())
                    : "[\"\",\"\",\"\",null,null,null,null,null,null,\"\"]";
            String updatedInner = replaceTopLevelElement(inner, 2, stateArray);
            if (conversation.isStateless()) {
                updatedInner = replaceTopLevelElement(updatedInner, 3, "\"\"");
                updatedInner = replaceTopLevelElement(updatedInner, 4, "\"\"");
            }
            updatedInner = replaceTopLevelElement(updatedInner, 17, "[[" + conversation.nextTurnIndex() + "]]");
            return fReq.substring(0, outerQuote)
                    + jsonQuote(updatedInner)
                    + fReq.substring(innerJson.endIndex());
        }

        private static String applyModelToFReq(String fReq, ModelProfile profile) {
            int outerQuote = fReq.indexOf('"');
            if (outerQuote < 0) {
                return fReq;
            }
            JsonString innerJson = readJsonString(fReq, outerQuote);
            if (innerJson == null) {
                return fReq;
            }

            String inner = innerJson.value();
            String updatedInner = inner;
            if (profile.requestHash() != null) {
                updatedInner = replaceTopLevelElement(updatedInner, 4, jsonQuote(profile.requestHash()));
            }
            if (profile.tailMode() != null) {
                updatedInner = replaceTopLevelElement(updatedInner, 79, String.valueOf(profile.tailMode()));
            }
            return fReq.substring(0, outerQuote)
                    + jsonQuote(updatedInner)
                    + fReq.substring(innerJson.endIndex());
        }

        private static String jsonConversationArray(String conversationId, String responseId, String choiceId) {
            return "[" + jsonQuote(conversationId) + ","
                    + jsonQuote(responseId) + ","
                    + jsonQuote(choiceId) + ",null,null,null,null,null,null,\"\"]";
        }

        private void refreshPerRequestHeaders() {
            replaceUuidHeader("x-goog-ext-525001261-jspb", "last");
            replaceUuidHeader("x-goog-ext-525005358-jspb", "first");
        }

        private void replaceUuidHeader(String name, String position) {
            String value = headers.get(name);
            if (value == null || value.isBlank()) {
                return;
            }

            Matcher matcher = Pattern.compile("\"[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\"").matcher(value);
            java.util.List<Range> ranges = new java.util.ArrayList<>();
            while (matcher.find()) {
                ranges.add(new Range(matcher.start(), matcher.end()));
            }
            if (ranges.isEmpty()) {
                return;
            }

            Range target = "first".equals(position) ? ranges.get(0) : ranges.get(ranges.size() - 1);
            String uuid = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
            headers.put(name, value.substring(0, target.start())
                    + jsonQuote(uuid)
                    + value.substring(target.end()));
        }

        private static String replaceTopLevelElement(String jsonArray, int targetIndex, String replacement) {
            Range bounds = topLevelElementBounds(jsonArray, targetIndex);
            if (bounds == null) {
                return jsonArray;
            }
            return jsonArray.substring(0, bounds.start())
                    + replacement
                    + jsonArray.substring(bounds.end());
        }

        private static Range topLevelElementBounds(String jsonArray, int targetIndex) {
            int arrayStart = 0;
            while (arrayStart < jsonArray.length() && Character.isWhitespace(jsonArray.charAt(arrayStart))) {
                arrayStart++;
            }
            if (arrayStart >= jsonArray.length() || jsonArray.charAt(arrayStart) != '[') {
                return null;
            }

            int level = 0;
            int elementIndex = 0;
            int elementStart = arrayStart + 1;
            for (int i = arrayStart; i < jsonArray.length(); i++) {
                char c = jsonArray.charAt(i);
                if (c == '"') {
                    JsonString skipped = readJsonString(jsonArray, i);
                    if (skipped == null) {
                        return null;
                    }
                    i = skipped.endIndex() - 1;
                    continue;
                }
                if (c == '[' || c == '{') {
                    level++;
                    continue;
                }
                if (c == ',' && level == 1) {
                    if (elementIndex == targetIndex) {
                        return trimmedRange(jsonArray, elementStart, i);
                    }
                    elementIndex++;
                    elementStart = i + 1;
                    continue;
                }
                if (c == ']' || c == '}') {
                    if (level == 1 && c == ']') {
                        return elementIndex == targetIndex
                                ? trimmedRange(jsonArray, elementStart, i)
                                : null;
                    }
                    level--;
                }
            }
            return null;
        }

        private static Range trimmedRange(String value, int start, int end) {
            while (start < end && Character.isWhitespace(value.charAt(start))) {
                start++;
            }
            while (end > start && Character.isWhitespace(value.charAt(end - 1))) {
                end--;
            }
            return new Range(start, end);
        }

        private record Range(int start, int end) {
        }

        private static String queryValue(String form, String key) {
            for (String part : form.split("&", -1)) {
                int index = part.indexOf('=');
                if (index <= 0) {
                    continue;
                }
                if (key.equals(formDecode(part.substring(0, index)))) {
                    return formDecode(part.substring(index + 1));
                }
            }
            return null;
        }

        private static String replaceFormValue(String form, String key, String value) {
            StringBuilder out = new StringBuilder();
            for (String part : form.split("&", -1)) {
                if (!out.isEmpty()) {
                    out.append('&');
                }

                int index = part.indexOf('=');
                if (index > 0 && key.equals(formDecode(part.substring(0, index)))) {
                    out.append(formEncode(key)).append('=').append(formEncode(value));
                } else {
                    out.append(part);
                }
            }
            return out.toString();
        }

        private static String setFormValue(String form, String key, String value) {
            boolean found = false;
            StringBuilder out = new StringBuilder();
            for (String part : form.split("&", -1)) {
                if (!out.isEmpty()) {
                    out.append('&');
                }

                int index = part.indexOf('=');
                if (index > 0 && key.equals(formDecode(part.substring(0, index)))) {
                    out.append(formEncode(key)).append('=').append(formEncode(value));
                    found = true;
                } else {
                    out.append(part);
                }
            }
            if (!found) {
                if (!out.isEmpty() && out.charAt(out.length() - 1) != '&') {
                    out.append('&');
                }
                out.append(formEncode(key)).append('=').append(formEncode(value));
            }
            return out.toString();
        }

        private static void addHeader(Map<String, String> headers, String header) {
            int colon = header.indexOf(':');
            if (colon > 0) {
                headers.put(header.substring(0, colon).trim(), header.substring(colon + 1).trim());
            }
        }

        private static String required(String value, String name) throws IOException {
            if (value == null || value.isBlank()) {
                throw new IOException("curl 里缺少 " + name);
            }
            return value;
        }

        private static java.util.List<String> shellTokens(String curl) {
            String normalized = curl.replaceAll("\\\\\\R", " ");
            java.util.List<String> tokens = new java.util.ArrayList<>();
            StringBuilder current = new StringBuilder();
            int i = 0;
            while (i < normalized.length()) {
                char c = normalized.charAt(i);
                if (Character.isWhitespace(c)) {
                    if (!current.isEmpty()) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                    i++;
                    continue;
                }
                if (c == '$' && i + 1 < normalized.length() && normalized.charAt(i + 1) == '\'') {
                    int end = findClosingAnsiCQuote(normalized, i + 2);
                    current.append(ansiCUnquote(normalized.substring(i + 2, end)));
                    i = end + 1;
                    continue;
                }
                if (c == '\'') {
                    int end = findClosingQuote(normalized, i + 1, '\'');
                    current.append(normalized, i + 1, end);
                    i = end + 1;
                    continue;
                }
                if (c == '"') {
                    int end = findClosingDoubleQuote(normalized, i + 1);
                    current.append(doubleQuoteUnescape(normalized.substring(i + 1, end)));
                    i = end + 1;
                    continue;
                }
                if (c == '\\' && i + 1 < normalized.length()) {
                    current.append(normalized.charAt(i + 1));
                    i += 2;
                    continue;
                }
                current.append(c);
                i++;
            }
            if (!current.isEmpty()) {
                tokens.add(current.toString());
            }
            return tokens;
        }

        private static int findClosingQuote(String text, int start, char quote) {
            for (int i = start; i < text.length(); i++) {
                if (text.charAt(i) == quote) {
                    return i;
                }
            }
            return text.length();
        }

        private static int findClosingAnsiCQuote(String text, int start) {
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\\' && i + 1 < text.length()) {
                    i++;
                    continue;
                }
                if (c == '\'') {
                    return i;
                }
            }
            return text.length();
        }

        private static int findClosingDoubleQuote(String text, int start) {
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\\' && i + 1 < text.length()) {
                    i++;
                    continue;
                }
                if (c == '"') {
                    return i;
                }
            }
            return text.length();
        }

        private static String doubleQuoteUnescape(String value) {
            StringBuilder out = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\\' && i + 1 < value.length()) {
                    char next = value.charAt(++i);
                    if (next == '"' || next == '\\' || next == '$' || next == '`') {
                        out.append(next);
                    } else {
                        out.append('\\').append(next);
                    }
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }

        private static String ansiCUnquote(String value) {
            StringBuilder out = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c != '\\' || i + 1 >= value.length()) {
                    out.append(c);
                    continue;
                }
                char next = value.charAt(++i);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case '\\' -> out.append('\\');
                    case '\'' -> out.append('\'');
                    case '"' -> out.append('"');
                    case 'u' -> {
                        if (i + 4 < value.length()) {
                            out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                            i += 4;
                        } else {
                            out.append("\\u");
                        }
                    }
                    default -> out.append(next);
                }
            }
            return out.toString();
        }
    }

    private static final class ClaudeCurlRequest {
        private HttpUrl url;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String cookie = "";
        private String body = "";

        private static ClaudeCurlRequest create(String cookie, String orgId) throws IOException {
            ClaudeCurlRequest request = new ClaudeCurlRequest();
            String organizationId = orgId == null || orgId.isBlank()
                    ? cookieValue(cookie, "lastActiveOrg")
                    : orgId;
            if (organizationId.isBlank()) {
                throw new IOException("Claude cookie 缺少 lastActiveOrg，无法构造 organization URL");
            }
            request.url = new HttpUrl.Builder()
                    .scheme("https")
                    .host("claude.ai")
                    .addPathSegment("api")
                    .addPathSegment("organizations")
                    .addPathSegment(organizationId)
                    .addPathSegment("chat_conversations")
                    .addPathSegment(UUID.randomUUID().toString())
                    .addPathSegment("completion")
                    .build();
            request.cookie = CurlRequest.required(cookie, "Claude cookie");
            request.headers.put("accept", "text/event-stream");
            request.headers.put("accept-language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
            request.headers.put("anthropic-client-platform", "web_claude_ai");
            String deviceId = cookieValue(cookie, "anthropic-device-id");
            if (!deviceId.isBlank()) {
                request.headers.put("anthropic-device-id", deviceId);
            }
            request.headers.put("cache-control", "no-cache");
            request.headers.put("content-type", "application/json");
            request.headers.put("origin", "https://claude.ai");
            request.headers.put("pragma", "no-cache");
            request.headers.put("referer", "https://claude.ai/new");
            request.headers.put("sec-fetch-dest", "empty");
            request.headers.put("sec-fetch-mode", "cors");
            request.headers.put("sec-fetch-site", "same-origin");
            request.headers.put("user-agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36");
            request.headers.put("Cookie", request.cookie);
            return request;
        }

        private static String cookieValue(String cookie, String name) {
            Pattern pattern = Pattern.compile("(?:^|;\\s*)" + Pattern.quote(name) + "=([^;]+)");
            Matcher matcher = pattern.matcher(cookie == null ? "" : cookie);
            return matcher.find() ? matcher.group(1) : "";
        }

        private String conversationId() {
            java.util.List<String> segments = url.pathSegments();
            for (int i = 0; i + 1 < segments.size(); i++) {
                if ("chat_conversations".equals(segments.get(i)) || "conversations".equals(segments.get(i))) {
                    return segments.get(i + 1);
                }
            }
            return "";
        }

        private HttpUrl uploadUrl() throws IOException {
            String organizationId = "";
            String conversationId = "";
            java.util.List<String> segments = url.pathSegments();
            for (int i = 0; i + 1 < segments.size(); i++) {
                String segment = segments.get(i);
                if ("organizations".equals(segment)) {
                    organizationId = segments.get(i + 1);
                } else if ("chat_conversations".equals(segment) || "conversations".equals(segment)) {
                    conversationId = segments.get(i + 1);
                }
            }
            if (organizationId.isBlank() || conversationId.isBlank()) {
                throw new IOException("Claude completion URL 缺少 organization 或 conversation id，无法构造图片上传 URL");
            }
            return url.newBuilder()
                    .encodedPath("/api/organizations/" + organizationId
                            + "/conversations/" + conversationId + "/wiggle/upload-file")
                    .query(null)
                    .build();
        }

        private void prepare(String model, String prompt, java.util.List<String> fileUuids) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("prompt", prompt == null ? "" : prompt);
            root.put("timezone", "Asia/Shanghai");
            root.put("locale", "en-US");
            root.put("model", model);
            root.put("effort", "max");
            root.put("thinking_mode", "off");
            root.put("tools", java.util.List.of());
            Map<String, Object> turnUuidMap = new LinkedHashMap<>();
            turnUuidMap.put("human_message_uuid", UUID.randomUUID().toString());
            turnUuidMap.put("assistant_message_uuid", UUID.randomUUID().toString());
            root.put("turn_message_uuids", turnUuidMap);
            root.put("attachments", java.util.List.of());
            root.put("files", fileUuids == null ? java.util.List.of() : java.util.List.copyOf(fileUuids));
            root.put("sync_sources", java.util.List.of());
            root.put("rendering_mode", "messages");
            Map<String, Object> createParamsMap = new LinkedHashMap<>();
            createParamsMap.put("name", "");
            createParamsMap.put("model", model);
            createParamsMap.put("include_conversation_preferences", true);
            createParamsMap.put("paprika_mode", null);
            createParamsMap.put("compass_mode", null);
            createParamsMap.put("tool_search_mode", "auto");
            createParamsMap.put("is_temporary", false);
            createParamsMap.put("enabled_imagine", true);
            root.put("create_conversation_params", createParamsMap);
            body = toJson(root);
        }

        private Request toRequest() {
            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body, JSON_MEDIA_TYPE));

            headers.forEach((name, value) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!"host".equals(lower) && !"content-length".equals(lower)) {
                    builder.header(name, value);
                }
            });
            return builder.build();
        }

        private Request toUploadRequest(byte[] bytes, String mimeType, String filename) throws IOException {
            String safeFilename = filename == null || filename.isBlank()
                    ? System.currentTimeMillis() + "_image.png"
                    : filename;
            String contentType = firstNonBlank(mimeType, mimeFromFilename(safeFilename), "application/octet-stream");
            RequestBody fileBody = RequestBody.create(bytes, MediaType.get(contentType));
            MultipartBody multipart = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", safeFilename, fileBody)
                    .build();

            Request.Builder builder = new Request.Builder()
                    .url(uploadUrl())
                    .post(multipart);

            headers.forEach((name, value) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!"host".equals(lower)
                        && !"content-length".equals(lower)
                        && !"content-type".equals(lower)
                        && !"accept".equals(lower)) {
                    builder.header(name, value);
                }
            });
            builder.header("accept", "*/*");
            return builder.build();
        }
    }

    private static final class ChatGptConversation {
        private String conversationId = "";
        private String parentMessageId = "";

        private void clear() {
            conversationId = "";
            parentMessageId = "";
        }
    }

    private static final class ChatGptCurlClient {
        private final ChatGptCurlStore store;
        private final OkHttpClient client;

        private ChatGptCurlClient(ChatGptCurlStore store, OkHttpClient client) {
            this.store = store;
            this.client = client;
        }

        private GeminiResult complete(String prompt, Consumer<String> deltaSink, ChatGptConversation conversation) throws IOException {
            List<ChatGptCurlStore.CurlRecord> records = store.active();
            if (records.isEmpty()) {
                throw new IOException("没有可用 ChatGPT curl。请在前端粘贴 chatgpt.com conversation curl。");
            }
            IOException last = null;
            for (ChatGptCurlStore.CurlRecord record : records) {
                try {
                    GeminiResult result = request(record, prompt, deltaSink, conversation);
                    store.markSuccess(record.id());
                    return result;
                } catch (IOException e) {
                    last = e;
                    store.markFailure(record.id(), e.getMessage());
                }
            }
            throw last == null ? new IOException("ChatGPT curl 请求失败") : last;
        }

        private GeminiResult request(ChatGptCurlStore.CurlRecord record, String prompt,
                                     Consumer<String> deltaSink, ChatGptConversation conversation) throws IOException {
            ParsedChatGptCurl curl = ParsedChatGptCurl.parse(record.curl());
            Object parsed = SimpleJson.parse(curl.body());
            if (!(parsed instanceof Map<?, ?> rawMap)) {
                throw new IOException("ChatGPT curl body 不是 JSON");
            }
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) rawMap;
            String parentMessageId = conversation.parentMessageId.isBlank()
                    ? "client-created-root" : conversation.parentMessageId;
            Map<String, Object> message = chatMessage(prompt);
            body.put("messages", java.util.List.of(message));
            if (!conversation.conversationId.isBlank()) body.put("conversation_id", conversation.conversationId);
            else body.remove("conversation_id");
            body.put("parent_message_id", parentMessageId);
            String path = curl.url().encodedPath();
            if (path.isBlank()) path = "/backend-api/f/conversation";
            body.put("client_prepare_state", "success");

            RequestContext context = new RequestContext(curl.url(), new LinkedHashMap<>(curl.headers()));
            Bootstrap bootstrap = bootstrap(context);
            Sentinel sentinel = sentinel(context, bootstrap);
            String freshConduit = refreshConduit(context, body, message, parentMessageId);

            Request.Builder builder = new Request.Builder().url(curl.url())
                    .post(RequestBody.create(jsonStringify(body), JSON_MEDIA_TYPE));
            applyBaseHeaders(builder, context, path);
            builder.header("x-conduit-token", freshConduit)
                    .header("openai-sentinel-chat-requirements-token", sentinel.token())
                    .header("content-type", "application/json")
                    .header("accept", "text/event-stream")
                    .header("x-openai-target-path", path)
                    .header("x-openai-target-route", path);
            builder.header("x-oai-turn-trace-id", UUID.randomUUID().toString());
            if (!sentinel.proofToken().isBlank()) builder.header("openai-sentinel-proof-token", sentinel.proofToken());
            if (!sentinel.turnstileToken().isBlank()) builder.header("openai-sentinel-turnstile-token", sentinel.turnstileToken());
            if (!sentinel.soToken().isBlank()) builder.header("openai-sentinel-so-token", sentinel.soToken());
            try (Response response = client.newCall(builder.build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    String error = response.body() == null ? "" : response.body().string();
                    if (response.code() == 401 || response.code() == 403) {
                        throw new IOException("ChatGPT curl 凭据已过期或触发设备风控（HTTP " + response.code()
                                + "）。请在同一浏览器/网络中重新复制最新 conversation curl 后保存；不要复用旧的 conduit/sentinel token。响应=" + preview(error));
                    }
                    throw new IOException("ChatGPT curl 请求失败，HTTP 状态=" + response.code() + "，响应=" + preview(error));
                }
                String text = "";
                String conversationId = conversation.conversationId;
                while (true) {
                    String line = response.body().source().readUtf8Line();
                    if (line == null) break;
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isBlank() || "[DONE]".equals(data)) continue;
                    Object event = SimpleJson.parse(data);
                    if (!(event instanceof Map<?, ?> map)) continue;
                    String cid = stringValue(map.get("conversation_id"));
                    if (cid.isBlank()) cid = nestedString(map, "conversation_id");
                    if (!cid.isBlank()) conversationId = cid;
                    String messageId = assistantMessageId(map);
                    String next = extractChatGptText(map, text);
                    if (!next.equals(text)) {
                        String delta = next.startsWith(text) ? next.substring(text.length()) : next;
                        if (!delta.isBlank() && deltaSink != null) deltaSink.accept(delta);
                        text = next;
                    }
                    if (!messageId.isBlank() && messageId.length() > 20) conversation.parentMessageId = messageId;
                }
                if (text.isBlank()) throw new IOException("ChatGPT SSE 未返回文本");
                conversation.conversationId = conversationId;
                conversation.parentMessageId = conversation.parentMessageId.isBlank()
                        ? parentMessageId : conversation.parentMessageId;
                return new GeminiResult(text, conversationId, "", conversation.parentMessageId);
            }
        }

        private Bootstrap bootstrap(RequestContext context) throws IOException {
            String path = "/";
            Request.Builder builder = new Request.Builder().url(context.url.newBuilder().encodedPath(path).build())
                    .get();
            applyBaseHeaders(builder, context, path);
            builder.header("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            builder.header("sec-fetch-dest", "document")
                    .header("sec-fetch-mode", "navigate")
                    .header("sec-fetch-site", "none")
                    .header("sec-fetch-user", "?1")
                    .header("upgrade-insecure-requests", "1");
            try (Response response = client.newCall(builder.build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) throw httpError("ChatGPT 首页", response);
                String html = response.body().string();
                java.util.ArrayList<String> scripts = new java.util.ArrayList<>();
                Matcher matcher = Pattern.compile("<script[^>]+src=[\\\"']([^\\\"']+)", Pattern.CASE_INSENSITIVE).matcher(html);
                while (matcher.find()) {
                    String source = matcher.group(1);
                    if (source.startsWith("/")) source = context.url.scheme() + "://" + context.url.host() + source;
                    scripts.add(source);
                }
                Matcher build = Pattern.compile("(?:data-build|buildId)=[\\\"']([^\\\"']+)", Pattern.CASE_INSENSITIVE).matcher(html);
                String dataBuild = build.find() ? build.group(1) : "";
                if (dataBuild.isBlank()) {
                    Matcher scriptBuild = Pattern.compile("/c/([^/]+)/_", Pattern.CASE_INSENSITIVE).matcher(html);
                    if (scriptBuild.find()) dataBuild = scriptBuild.group(1);
                }
                if (dataBuild.isBlank()) dataBuild = header(context.headers, "oai-client-version");
                return new Bootstrap(scripts, dataBuild);
            }
        }

        private Sentinel sentinel(RequestContext context, Bootstrap bootstrap) throws IOException {
            String userAgent = header(context.headers, "user-agent");
            String p = requirementsToken(userAgent, bootstrap);
            String preparePath = "/backend-api/sentinel/chat-requirements/prepare";
            String prepareBody = postJson(context, preparePath, "{\"p\":" + SimpleJson.quote(p) + "}", "application/json");
            Object parsed = SimpleJson.parse(prepareBody);
            if (!(parsed instanceof Map<?, ?> map)) throw new IOException("ChatGPT requirements 响应不是 JSON：" + preview(prepareBody));
            String prepareToken = stringValue(map.get("prepare_token"));
            if (prepareToken.isBlank()) throw new IOException("ChatGPT requirements 缺少 prepare_token：" + preview(prepareBody));
            Map<?, ?> proof = map.get("proofofwork") instanceof Map<?, ?> value ? value : Map.of();
            String proofToken = "";
            if (Boolean.TRUE.equals(proof.get("required"))) {
                proofToken = proofToken(stringValue(proof.get("seed")), stringValue(proof.get("difficulty")), userAgent, bootstrap);
            }
            Map<?, ?> turnstile = map.get("turnstile") instanceof Map<?, ?> value ? value : Map.of();
            String turnstileToken = "";
            if (Boolean.TRUE.equals(turnstile.get("required"))) {
                turnstileToken = solveTurnstile(stringValue(turnstile.get("dx")), p);
                if (turnstileToken.isBlank()) {
                    throw new IOException("ChatGPT 要求 Turnstile，dx VM 未返回 token；请在同一浏览器中重新复制 curl 后重试");
                }
            }
            String finalize = "{\"prepare_token\":" + SimpleJson.quote(prepareToken)
                    + ",\"proof_token\":" + SimpleJson.quote(proofToken)
                    + ",\"turnstile_token\":" + SimpleJson.quote(turnstileToken) + "}";
            String finalBody = postJson(context, "/backend-api/sentinel/chat-requirements/finalize", finalize, "application/json");
            Object finalParsed = SimpleJson.parse(finalBody);
            if (!(finalParsed instanceof Map<?, ?> finalMap)) throw new IOException("ChatGPT requirements finalize 响应无效：" + preview(finalBody));
            String requirements = stringValue(finalMap.get("token"));
            if (requirements.isBlank()) throw new IOException("ChatGPT requirements finalize 缺少 token：" + preview(finalBody));
            return new Sentinel(requirements, proofToken, turnstileToken, stringValue(finalMap.get("so_token")));
        }

        private String refreshConduit(RequestContext context, Map<String, Object> conversationBody,
                                      Map<String, Object> message, String parentMessageId) throws IOException {
            String path = "/backend-api/f/conversation/prepare";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", "next");
            body.put("fork_from_shared_post", false);
            body.put("parent_message_id", parentMessageId);
            body.put("model", conversationBody.getOrDefault("model", "auto"));
            body.put("client_prepare_state", "none");
            body.put("timezone_offset_min", conversationBody.getOrDefault("timezone_offset_min", -480));
            body.put("timezone", conversationBody.getOrDefault("timezone", "Asia/Shanghai"));
            body.put("conversation_mode", conversationBody.getOrDefault("conversation_mode", Map.of("kind", "primary_assistant")));
            body.put("system_hints", conversationBody.getOrDefault("system_hints", List.of()));
            body.put("partial_query", message);
            body.put("supports_buffering", conversationBody.getOrDefault("supports_buffering", true));
            body.put("supported_encodings", conversationBody.getOrDefault("supported_encodings", List.of("v1")));
            body.put("client_contextual_info", conversationBody.getOrDefault("client_contextual_info", Map.of("app_name", "chatgpt.com")));
            String raw = jsonStringify(body);
            Request.Builder builder = new Request.Builder().url(context.url.newBuilder().encodedPath(path).build())
                    .post(RequestBody.create(raw, JSON_MEDIA_TYPE));
            applyBaseHeaders(builder, context, path);
            builder.header("accept", "application/json")
                    .header("content-type", "application/json")
                    .header("x-conduit-token", "no-token")
                    .header("x-openai-target-path", path)
                    .header("x-openai-target-route", path);
            try (Response response = client.newCall(builder.build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    String error = response.body() == null ? "" : response.body().string();
                    throw new IOException("ChatGPT conversation/prepare 请求失败，HTTP 状态=" + response.code() + "，响应=" + preview(error));
                }
                Object parsed = SimpleJson.parse(response.body().string());
                String conduit = parsed instanceof Map<?, ?> map ? stringValue(map.get("conduit_token")) : "";
                if (conduit.isBlank()) throw new IOException("ChatGPT conversation/prepare 缺少 conduit_token");
                return conduit;
            }
        }

        private String postJson(RequestContext context, String path, String body, String accept) throws IOException {
            Request.Builder builder = new Request.Builder().url(context.url.newBuilder().encodedPath(path).build())
                    .post(RequestBody.create(body, JSON_MEDIA_TYPE));
            applyBaseHeaders(builder, context, path);
            builder.header("accept", accept).header("content-type", "application/json")
                    .header("x-openai-target-path", path).header("x-openai-target-route", path);
            if (path.endsWith("/conversation/prepare")) builder.header("x-conduit-token", "no-token");
            try (Response response = client.newCall(builder.build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) throw httpError(path, response);
                return response.body().string();
            }
        }

        private static void applyBaseHeaders(Request.Builder builder, RequestContext context, String path) {
            context.headers.forEach((name, value) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("host") || lower.equals("content-length") || lower.equals("content-type")
                        || lower.equals("accept") || lower.equals("x-conduit-token")
                        || lower.equals("x-openai-target-path") || lower.equals("x-openai-target-route")
                        || lower.startsWith("openai-sentinel-") || lower.equals("x-oai-is-client-observation")
                        || lower.equals("x-oai-turn-trace-id")) return;
                builder.header(name, value);
            });
        }

        private static String header(Map<String, String> headers, String name) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
            }
            return "";
        }

        private static String requirementsToken(String userAgent, Bootstrap bootstrap) {
            long started = System.nanoTime();
            java.util.ArrayList<Object> values = powConfig(userAgent, bootstrap);
            values.set(3, 1);
            values.set(9, (System.nanoTime() - started) / 1_000_000.0);
            return "gAAAAAC" + Base64.getEncoder().encodeToString(jsonStringify(values).getBytes(StandardCharsets.UTF_8));
        }

        private static String proofToken(String seed, String difficulty, String userAgent, Bootstrap bootstrap) throws IOException {
            java.util.ArrayList<Object> values = powConfig(userAgent, bootstrap);
            byte[] target;
            try {
                target = java.util.HexFormat.of().parseHex(difficulty.replaceFirst("^0x", ""));
            } catch (IllegalArgumentException e) {
                throw new IOException("PoW difficulty 无效", e);
            }
            byte[] seedBytes = seed.getBytes(StandardCharsets.UTF_8);
            int prefixLength = Math.max(0, target.length);
            for (int i = 0; i < 2_000_000; i++) {
                String prefix = jsonStringify(values.subList(0, 3));
                prefix = prefix.substring(0, prefix.length() - 1) + ",";
                String middle = jsonStringify(values.subList(4, 9));
                middle = "," + middle.substring(1, middle.length() - 1) + ",";
                String suffix = jsonStringify(values.subList(10, values.size()));
                suffix = "," + suffix.substring(1);
                String encodedText = prefix + i + middle + (i >> 1) + suffix;
                byte[] encoded = Base64.getEncoder().encode(encodedText.getBytes(StandardCharsets.UTF_8));
                byte[] digest;
                try {
                    digest = java.security.MessageDigest.getInstance("SHA3-512").digest(join(seedBytes, encoded));
                } catch (NoSuchAlgorithmException e) {
                    throw new IOException("JDK 不支持 SHA3-512", e);
                }
                if (lessOrEqual(digest, target, prefixLength)) {
                    return "gAAAAAB" + new String(encoded, StandardCharsets.US_ASCII) + "~S";
                }
            }
            throw new IOException("ChatGPT PoW 在 2000000 次尝试内未完成");
        }

        private static java.util.ArrayList<Object> powConfig(String userAgent, Bootstrap bootstrap) {
            java.util.ArrayList<Object> values = new java.util.ArrayList<>();
            int[][] screens = {{1920, 1080}, {1440, 900}, {2560, 1440}, {3840, 2160}};
            int[] screen = screens[ThreadLocalRandom.current().nextInt(screens.length)];
            values.add(screen[0] + screen[1]);
            values.add(new java.text.SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT-0500 (Eastern Standard Time)'", Locale.US).format(new java.util.Date()));
            values.add(4294705152L);
            values.add(1);
            values.add(userAgent == null || userAgent.isBlank() ? "Mozilla/5.0" : userAgent);
            List<String> scripts = bootstrap.scripts();
            values.add(scripts.isEmpty() ? "https://chatgpt.com/backend-api/sentinel/sdk.js" : scripts.get(ThreadLocalRandom.current().nextInt(scripts.size())));
            values.add(bootstrap.dataBuild());
            values.add("zh-CN");
            values.add("zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
            values.add(Math.random());
            String[] navigatorKeys = {"vendor−Google Inc.", "language−zh-CN", "webdriver−false", "cookieEnabled−true", "hardwareConcurrency−32", "platform−Linux x86_64"};
            String[] documentKeys = {"__reactContainer$fzelfjyxej8", "_reactListening5dehydibo78", "location"};
            String[] windowKeys = {"window", "document", "location", "navigator", "performance", "crypto", "localStorage", "fetch", "setTimeout"};
            values.add(navigatorKeys[ThreadLocalRandom.current().nextInt(navigatorKeys.length)]);
            values.add(documentKeys[ThreadLocalRandom.current().nextInt(documentKeys.length)]);
            values.add(windowKeys[ThreadLocalRandom.current().nextInt(windowKeys.length)]);
            values.add(System.nanoTime() / 1_000_000.0);
            values.add(UUID.randomUUID().toString());
            values.add("");
            values.add(new int[]{8, 16, 24, 32}[ThreadLocalRandom.current().nextInt(4)]);
            values.add(System.currentTimeMillis() * 1.0 - System.nanoTime() / 1_000_000.0);
            values.add(0); values.add(0); values.add(0); values.add(0); values.add(0); values.add(0); values.add(0);
            return values;
        }

        private static byte[] join(byte[] a, byte[] b) {
            byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, out, a.length, b.length);
            return out;
        }

        private static boolean lessOrEqual(byte[] a, byte[] b, int count) {
            for (int i = 0; i < count; i++) {
                int left = a[i] & 255, right = b[i] & 255;
                if (left < right) return true;
                if (left > right) return false;
            }
            return true;
        }

        /** Small interpreter for the dx instruction format documented in 111.html. */
        private static String solveTurnstile(String dx, String key) {
            if (dx == null || dx.isBlank() || key == null || key.isBlank()) return "";
            try {
                byte[] encoded;
                try { encoded = Base64.getDecoder().decode(dx); }
                catch (IllegalArgumentException e) { encoded = Base64.getUrlDecoder().decode(dx); }
                String decoded = xor(new String(encoded, StandardCharsets.UTF_8), key);
                Object parsed = SimpleJson.parse(decoded);
                if (!(parsed instanceof List<?> instructions)) return "";
                Map<Integer, Object> values = new LinkedHashMap<>();
                values.put(3, "__fn3");
                values.put(9, instructions);
                values.put(10, "window");
                values.put(16, key);
                String[] result = {""};
                long started = System.nanoTime();
                for (Object item : instructions) {
                    if (!(item instanceof List<?> token) || token.isEmpty()) continue;
                    int op = number(token.get(0));
                    try {
                        switch (op) {
                            case 1 -> values.put(number(token.get(1)), xor(jsString(values.get(number(token.get(1)))), jsString(values.get(number(token.get(2))))));
                            case 2 -> values.put(number(token.get(1)), token.size() > 2 ? token.get(2) : null);
                            case 3 -> result[0] = Base64.getEncoder().encodeToString(jsString(token.get(1)).getBytes(StandardCharsets.UTF_8));
                            case 5 -> {
                                Object left = values.get(number(token.get(1))), right = values.get(number(token.get(2)));
                                if (left instanceof List<?> list) { java.util.ArrayList<Object> out = new java.util.ArrayList<>(list); out.add(right); values.put(number(token.get(1)), out); }
                                else values.put(number(token.get(1)), jsString(left) + jsString(right));
                            }
                            case 6 -> {
                                String path = jsString(values.get(number(token.get(2)))) + "." + jsString(values.get(number(token.get(3))));
                                values.put(number(token.get(1)), "window.document.location".equals(path) ? "https://chatgpt.com" : path);
                            }
                            case 7 -> invoke(values, token, result);
                            case 8 -> values.put(number(token.get(1)), values.get(number(token.get(2))));
                            case 14 -> values.put(number(token.get(1)), SimpleJson.parse(jsString(values.get(number(token.get(2))))));
                            case 15 -> values.put(number(token.get(1)), jsonStringify(values.get(number(token.get(2)))));
                            case 17 -> invokeFunction(values, token, started, result);
                            case 18 -> {
                                byte[] raw = Base64.getDecoder().decode(jsString(values.get(number(token.get(1)))));
                                values.put(number(token.get(1)), new String(raw, StandardCharsets.UTF_8));
                            }
                            case 19 -> values.put(number(token.get(1)), Base64.getEncoder().encodeToString(jsString(values.get(number(token.get(1)))).getBytes(StandardCharsets.UTF_8)));
                            case 20 -> {
                                if (Objects.equals(values.get(number(token.get(1))), values.get(number(token.get(2))))) {
                                    invokeFunctionAt(values, token, 3, started, result);
                                }
                            }
                            case 21 -> { }
                            case 23 -> { if (values.get(number(token.get(1))) != null) invokeFunctionAt(values, token, 2, started, result); }
                            case 24 -> {
                                String path = jsString(values.get(number(token.get(1)))) + "." + jsString(values.get(number(token.get(2))));
                                values.put(number(token.get(1)), path);
                            }
                            default -> { }
                        }
                    } catch (RuntimeException ignored) {
                        // The browser VM ignores an individual malformed instruction.
                    }
                    if (System.nanoTime() - started > 500_000_000L) break;
                }
                return result[0];
            } catch (Exception ignored) {
                return "";
            }
        }

        private static void invoke(Map<Integer, Object> values, List<?> token, String[] result) {
            Object target = values.get(number(token.get(1)));
            if ("window.Reflect.set".equals(target) && token.size() > 4) {
                Object object = values.get(number(token.get(2)));
                if (object instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked") Map<Object, Object> writable = (Map<Object, Object>) map;
                    writable.put(values.get(number(token.get(3))), values.get(number(token.get(4))));
                }
            } else if ("__fn3".equals(target) && token.size() > 2) {
                result[0] = Base64.getEncoder().encodeToString(jsString(values.get(number(token.get(2)))).getBytes(StandardCharsets.UTF_8));
            }
        }

        private static void invokeFunction(Map<Integer, Object> values, List<?> token, long started, String[] result) {
            if (token.size() < 3) return;
            Object target = values.get(number(token.get(2)));
            if (!(target instanceof String name)) return;
            if ("window.performance.now".equals(name)) values.put(number(token.get(1)), (System.nanoTime() - started) / 1_000_000.0);
            else if ("window.Math.random".equals(name)) values.put(number(token.get(1)), Math.random());
            else if ("window.Object.create".equals(name)) values.put(number(token.get(1)), new LinkedHashMap<>());
            else if ("window.Object.keys".equals(name)) values.put(number(token.get(1)), List.of(
                    "STATSIG_LOCAL_STORAGE_INTERNAL_STORE_V4", "STATSIG_LOCAL_STORAGE_STABLE_ID",
                    "client-correlated-secret", "oai/apps/capExpiresAt", "oai-did",
                    "STATSIG_LOCAL_STORAGE_LOGGING_REQUEST", "UiState.isNavigationCollapsed.1"));
        }

        private static void invokeFunctionAt(Map<Integer, Object> values, List<?> token, int targetIndex, long started, String[] result) {
            if (token.size() <= targetIndex) return;
            Object target = values.get(number(token.get(targetIndex)));
            if (target instanceof String name && name.startsWith("__fn")) {
                if ("__fn3".equals(name) && token.size() > targetIndex + 1) {
                    Object argument = values.get(number(token.get(targetIndex + 1)));
                    result[0] = Base64.getEncoder().encodeToString(jsString(argument).getBytes(StandardCharsets.UTF_8));
                    return;
                }
                List<Object> copy = new java.util.ArrayList<>();
                copy.add(17); copy.add(token.get(0)); copy.add(target);
                invokeFunction(values, copy, started, result);
            }
        }

        private static int number(Object value) {
            return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
        }

        private static String xor(String text, String key) {
            StringBuilder out = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) out.append((char) (text.charAt(i) ^ key.charAt(i % key.length())));
            return out.toString();
        }

        private static String jsString(Object value) {
            if (value == null) return "undefined";
            if (value instanceof String string) {
                return switch (string) {
                    case "window.Math" -> "[object Math]";
                    case "window.Reflect" -> "[object Reflect]";
                    case "window.performance" -> "[object Performance]";
                    case "window.localStorage" -> "[object Storage]";
                    case "window.Object" -> "function Object() { [native code] }";
                    case "window.Reflect.set" -> "function set() { [native code] }";
                    case "window.performance.now" -> "function () { [native code] }";
                    case "window.Object.create" -> "function create() { [native code] }";
                    case "window.Object.keys" -> "function keys() { [native code] }";
                    case "window.Math.random" -> "function random() { [native code] }";
                    default -> string;
                };
            }
            if (value instanceof List<?> list && list.stream().allMatch(item -> item instanceof String)) {
                return list.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
            }
            if (value instanceof Double d && d == Math.rint(d)) return Long.toString(d.longValue());
            return String.valueOf(value);
        }

        private static IOException httpError(String operation, Response response) throws IOException {
            String body = response.body() == null ? "" : response.body().string();
            return new IOException(operation + " 请求失败，HTTP 状态=" + response.code() + "，响应=" + preview(body));
        }

        private record RequestContext(HttpUrl url, Map<String, String> headers) {}
        private record Bootstrap(java.util.List<String> scripts, String dataBuild) {}
        private record Sentinel(String token, String proofToken, String turnstileToken, String soToken) {}

        private static Map<String, Object> chatMessage(String text) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("id", UUID.randomUUID().toString());
            message.put("author", Map.of("role", "user"));
            message.put("create_time", System.currentTimeMillis() / 1000.0);
            message.put("content", Map.of("content_type", "text", "parts", List.of(text)));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("developer_mode_connector_ids", List.of());
            metadata.put("selected_sources", List.of());
            metadata.put("selected_github_repos", List.of());
            metadata.put("selected_all_github_repos", false);
            metadata.put("serialization_metadata", Map.of("custom_symbol_offsets", List.of()));
            metadata.put("submission_mode", "manual_send");
            message.put("metadata", metadata);
            return message;
        }

        private static String nestedString(Map<?, ?> map, String key) {
            String direct = stringValue(map.get(key));
            if (!direct.isBlank()) return direct;
            for (Object child : map.values()) {
                if (child instanceof Map<?, ?> nested) {
                    String found = nestedString(nested, key);
                    if (!found.isBlank()) return found;
                }
            }
            return "";
        }

        private static String assistantMessageId(Object value) {
            if (value instanceof List<?> list) {
                for (Object child : list) {
                    String found = assistantMessageId(child);
                    if (!found.isBlank()) return found;
                }
                return "";
            }
            if (!(value instanceof Map<?, ?> map)) return "";
            Object message = map.get("message");
            if (message instanceof Map<?, ?> messageMap) {
                Object author = messageMap.get("author");
                if (author instanceof Map<?, ?> authorMap && "assistant".equals(stringValue(authorMap.get("role")))) {
                    String id = stringValue(messageMap.get("id"));
                    if (!id.isBlank()) return id;
                }
            }
            String direct = stringValue(map.get("message_id"));
            if (!direct.isBlank()) return direct;
            for (Object child : map.values()) {
                String found = assistantMessageId(child);
                if (!found.isBlank()) return found;
            }
            return "";
        }

        private static String extractChatGptText(Object value, String current) {
            if (!(value instanceof Map<?, ?> map)) return current;
            for (Object candidate : new Object[]{map, map.get("v")}) {
                if (!(candidate instanceof Map<?, ?> nested)) continue;
                Object message = nested.get("message");
                if (message instanceof Map<?, ?> msg && msg.get("author") instanceof Map<?, ?> author
                        && "assistant".equals(stringValue(author.get("role")))) {
                    Object content = msg.get("content");
                    if (content instanceof Map<?, ?> cm && cm.get("parts") instanceof List<?> parts) {
                        StringBuilder out = new StringBuilder();
                        for (Object part : parts) if (part instanceof String s) out.append(s);
                        if (!out.isEmpty()) return out.toString();
                    }
                }
            }
            if ("/message/content/parts/0".equals(stringValue(map.get("p")))) {
                String valueText = stringValue(map.get("v"));
                if ("append".equals(stringValue(map.get("o")))) return current + valueText;
                if ("replace".equals(stringValue(map.get("o")))) return valueText;
            }
            if ("patch".equals(stringValue(map.get("o"))) && map.get("v") instanceof List<?> list) {
                String out = current;
                for (Object item : list) out = extractChatGptText(item, out);
                return out;
            }
            return current;
        }

        private static String jsonStringify(Object value) {
            if (value == null) return "null";
            if (value instanceof String s) return SimpleJson.quote(s);
            if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
            if (value instanceof Map<?, ?> map) {
                StringBuilder out = new StringBuilder("{"); boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) out.append(','); first = false;
                    out.append(SimpleJson.quote(String.valueOf(entry.getKey()))).append(':').append(jsonStringify(entry.getValue()));
                }
                return out.append('}').toString();
            }
            if (value instanceof Iterable<?> iterable) {
                StringBuilder out = new StringBuilder("["); boolean first = true;
                for (Object item : iterable) { if (!first) out.append(','); first = false; out.append(jsonStringify(item)); }
                return out.append(']').toString();
            }
            return SimpleJson.quote(String.valueOf(value));
        }

        private record ParsedChatGptCurl(HttpUrl url, Map<String, String> headers, String body) {
            static ParsedChatGptCurl parse(String raw) throws IOException {
                List<String> tokens = CurlRequest.shellTokens(raw);
                HttpUrl url = null; String body = ""; Map<String, String> headers = new LinkedHashMap<>();
                for (int i = 0; i < tokens.size(); i++) {
                    String token = tokens.get(i);
                    if (("--url".equals(token) || "-X".equals(token)) && i + 1 < tokens.size()) {
                        String next = tokens.get(++i); if (next.startsWith("http")) url = HttpUrl.get(next); continue;
                    }
                    if (url == null && token.startsWith("http")) { url = HttpUrl.get(token); continue; }
                    if (("-H".equals(token) || "--header".equals(token)) && i + 1 < tokens.size()) {
                        String header = tokens.get(++i); int colon = header.indexOf(':');
                        if (colon > 0) headers.put(header.substring(0, colon).trim(), header.substring(colon + 1).trim());
                        continue;
                    }
                    if (("--data-raw".equals(token) || "--data".equals(token) || "--data-binary".equals(token)) && i + 1 < tokens.size()) body = tokens.get(++i);
                    else if (token.startsWith("--data-raw=") || token.startsWith("--data=")) body = token.substring(token.indexOf('=') + 1);
                    if (("-b".equals(token) || "--cookie".equals(token)) && i + 1 < tokens.size()) headers.put("Cookie", tokens.get(++i));
                }
                if (url == null || body.isBlank()) throw new IOException("ChatGPT curl 缺少 URL 或 JSON body");
                return new ParsedChatGptCurl(url, headers, body);
            }
        }
    }

    /** Legacy generated-token client retained for compatibility with older configs. */
    private static final class ChatGptClient {
        private static final String BASE_URL = "https://chatgpt.com";
        private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";
        private final AppConfig config;
        private final OkHttpClient client;

        private ChatGptClient(AppConfig config, OkHttpClient client) {
            this.config = config;
            this.client = client;
        }

        private GeminiResult complete(String prompt, Consumer<String> deltaSink) throws IOException {
            String token = normalizeAccessToken(config.chatGptAccessToken);
            Bootstrap bootstrap = bootstrap(token);
            Sentinel sentinel = sentinel(token, bootstrap);
            String model = config.chatGptModel.isBlank() ? "auto" : config.chatGptModel;
            String message = messageJson(prompt);
            String conduit = prepareConduit(token, model, message);
            return streamConversation(token, sentinel, conduit, model, message, deltaSink);
        }

        private Bootstrap bootstrap(String token) throws IOException {
            Request request = baseRequest("/").header("Accept", "text/html,application/xhtml+xml").build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw httpError("ChatGPT 首页", response);
                }
                String html = response.body().string();
                java.util.ArrayList<String> scripts = new java.util.ArrayList<>();
                Matcher matcher = Pattern.compile("<script[^>]+src=[\\\"']([^\\\"']+)", Pattern.CASE_INSENSITIVE).matcher(html);
                while (matcher.find()) {
                    String source = matcher.group(1);
                    if (source.startsWith("/")) {
                        source = BASE_URL + source;
                    }
                    scripts.add(source);
                }
                Matcher build = Pattern.compile("data-build=[\\\"']([^\\\"']+)", Pattern.CASE_INSENSITIVE).matcher(html);
                return new Bootstrap(scripts, build.find() ? build.group(1) : "");
            }
        }

        private Sentinel sentinel(String token, Bootstrap bootstrap) throws IOException {
            String p = legacyRequirementsToken(bootstrap);
            String path = "/backend-api/sentinel/chat-requirements/prepare";
            String prepareBody = postJson(token, path, "{\"p\":" + SimpleJson.quote(p) + "}", false);
            Object parsed = SimpleJson.parse(prepareBody);
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IOException("ChatGPT requirements 响应不是 JSON：" + preview(prepareBody));
            }
            String prepareToken = stringValue(map.get("prepare_token"));
            if (prepareToken.isBlank()) {
                throw new IOException("ChatGPT requirements 缺少 prepare_token：" + preview(prepareBody));
            }
            Map<?, ?> proof = map.get("proofofwork") instanceof Map<?, ?> value ? value : java.util.Map.of();
            String proofToken = "";
            if (Boolean.TRUE.equals(proof.get("required"))) {
                String seed = stringValue(proof.get("seed"));
                String difficulty = stringValue(proof.get("difficulty"));
                proofToken = proofToken(seed, difficulty, bootstrap);
            }
            Map<?, ?> turnstile = map.get("turnstile") instanceof Map<?, ?> value ? value : java.util.Map.of();
            if (Boolean.TRUE.equals(turnstile.get("required"))) {
                throw new IOException("ChatGPT 要求 Turnstile 验证，当前服务无法自动完成；请稍后重试或使用浏览器会话。");
            }
            String finalize = "{\"prepare_token\":" + SimpleJson.quote(prepareToken)
                    + ",\"proof_token\":" + SimpleJson.quote(proofToken)
                    + ",\"turnstile_token\":\"\"}";
            String finalBody = postJson(token, "/backend-api/sentinel/chat-requirements/finalize", finalize, false);
            Object finalParsed = SimpleJson.parse(finalBody);
            if (!(finalParsed instanceof Map<?, ?> finalMap)) {
                throw new IOException("ChatGPT requirements finalize 响应无效：" + preview(finalBody));
            }
            String requirements = stringValue(finalMap.get("token"));
            if (requirements.isBlank()) {
                throw new IOException("ChatGPT requirements finalize 缺少 token：" + preview(finalBody));
            }
            return new Sentinel(requirements, proofToken, stringValue(finalMap.get("so_token")));
        }

        private String prepareConduit(String token, String model, String message) throws IOException {
            String path = "/backend-api/f/conversation/prepare";
            String body = "{\"action\":\"next\",\"fork_from_shared_post\":false,"
                    + "\"parent_message_id\":\"client-created-root\",\"model\":" + SimpleJson.quote(model)
                    + ",\"client_prepare_state\":\"none\",\"timezone_offset_min\":-480,\"timezone\":\"Asia/Shanghai\","
                    + "\"conversation_mode\":{\"kind\":\"primary_assistant\"},\"system_hints\":[],"
                    + "\"partial_query\":" + message + ",\"supports_buffering\":true,"
                    + "\"supported_encodings\":[\"v1\"],\"client_contextual_info\":{\"app_name\":\"chatgpt.com\"}}";
            String response = postJson(token, path, body, false);
            Object parsed = SimpleJson.parse(response);
            String conduit = parsed instanceof Map<?, ?> map ? stringValue(map.get("conduit_token")) : "";
            if (conduit.isBlank()) {
                throw new IOException("ChatGPT conversation/prepare 缺少 conduit_token：" + preview(response));
            }
            return conduit;
        }

        private GeminiResult streamConversation(String token, Sentinel sentinel, String conduit,
                                                String model, String message, Consumer<String> deltaSink) throws IOException {
            String path = "/backend-api/f/conversation";
            String body = "{\"action\":\"next\",\"messages\":[" + message + "],"
                    + "\"parent_message_id\":\"client-created-root\",\"model\":" + SimpleJson.quote(model)
                    + ",\"client_prepare_state\":\"success\",\"timezone_offset_min\":-480,\"timezone\":\"Asia/Shanghai\","
                    + "\"conversation_mode\":{\"kind\":\"primary_assistant\"},\"enable_message_followups\":true,"
                    + "\"system_hints\":[],\"supports_buffering\":true,\"supported_encodings\":[\"v1\"],"
                    + "\"client_contextual_info\":{\"is_dark_mode\":false,\"time_since_loaded\":120,"
                    + "\"page_height\":900,\"page_width\":1400,\"pixel_ratio\":1,\"screen_height\":900,"
                    + "\"screen_width\":1400,\"app_name\":\"chatgpt.com\"},\"paragen_cot_summary_display_override\":\"allow\","
                    + "\"force_parallel_switch\":\"auto\"}";
            Request.Builder builder = baseRequest(path)
                    .header("Accept", "text/event-stream")
                    .header("X-Conduit-Token", conduit)
                    .header("OpenAI-Sentinel-Chat-Requirements-Token", sentinel.token());
            if (!sentinel.proofToken().isBlank()) {
                builder.header("OpenAI-Sentinel-Proof-Token", sentinel.proofToken());
            }
            if (!sentinel.soToken().isBlank()) {
                builder.header("OpenAI-Sentinel-SO-Token", sentinel.soToken());
            }
            Request request = builder.post(RequestBody.create(body, JSON_MEDIA_TYPE)).build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw httpError("ChatGPT conversation", response);
                }
                StringBuilder all = new StringBuilder();
                String current = "";
                String conversationId = "";
                while (true) {
                    String line = response.body().source().readUtf8Line();
                    if (line == null) break;
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isBlank() || "[DONE]".equals(data)) continue;
                    Object event = SimpleJson.parse(data);
                    if (!(event instanceof Map<?, ?> map)) continue;
                    String cid = stringValue(map.get("conversation_id"));
                    if (!cid.isBlank()) conversationId = cid;
                    String next = extractChatGptText(event, current);
                    if (!next.equals(current)) {
                        String delta = next.startsWith(current) ? next.substring(current.length()) : next;
                        if (!delta.isBlank() && deltaSink != null) deltaSink.accept(delta);
                        current = next;
                    }
                }
                if (current.isBlank()) throw new IOException("ChatGPT SSE 未返回文本");
                return new GeminiResult(current, conversationId, "", "");
            }
        }

        private Request.Builder baseRequest(String path) {
            return new Request.Builder().url(BASE_URL + path)
                    .header("User-Agent", USER_AGENT)
                    .header("Origin", BASE_URL)
                    .header("Referer", BASE_URL + "/")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("OAI-Device-Id", UUID.randomUUID().toString())
                    .header("OAI-Session-Id", UUID.randomUUID().toString())
                    .header("OAI-Language", "zh-CN")
                    .header("OAI-Client-Version", "prod-760ab00c2c9dc7017b94fed36021fb0bb6c993e1")
                    .header("OAI-Client-Build-Number", "7172950")
                    .header("Authorization", "Bearer " + normalizeAccessToken(config.chatGptAccessToken));
        }

        private String postJson(String token, String path, String body, boolean stream) throws IOException {
            Request request = baseRequest(path).header("Accept", stream ? "text/event-stream" : "application/json")
                    .header("X-OpenAI-Target-Path", path)
                    .header("X-OpenAI-Target-Route", path)
                    .header("X-Conduit-Token", path.endsWith("/conversation/prepare") ? "no-token" : "")
                    .post(RequestBody.create(body, JSON_MEDIA_TYPE)).build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) throw httpError(path, response);
                return response.body().string();
            }
        }

        private static String messageJson(String text) {
            return "{\"id\":" + SimpleJson.quote(UUID.randomUUID().toString())
                    + ",\"author\":{\"role\":\"user\"},\"create_time\":" + (System.currentTimeMillis() / 1000.0)
                    + ",\"content\":{\"content_type\":\"text\",\"parts\":[" + SimpleJson.quote(text)
                    + "]},\"metadata\":{\"developer_mode_connector_ids\":[],\"selected_sources\":[],"
                    + "\"selected_github_repos\":[],\"selected_all_github_repos\":false,"
                    + "\"serialization_metadata\":{\"custom_symbol_offsets\":[]}}}";
        }

        private String legacyRequirementsToken(Bootstrap bootstrap) {
            java.util.List<Object> values = powConfig(bootstrap);
            return "gAAAAAC" + Base64.getEncoder().encodeToString(jsonStringify(values).getBytes(StandardCharsets.UTF_8));
        }

        private String proofToken(String seed, String difficulty, Bootstrap bootstrap) throws IOException {
            java.util.List<Object> values = powConfig(bootstrap);
            byte[] target;
            try { target = hexBytes(difficulty); } catch (IllegalArgumentException e) { throw new IOException("PoW difficulty 无效", e); }
            String prefix = jsonStringify(values.subList(0, 3));
            prefix = prefix.substring(0, prefix.length() - 1) + ",";
            String middle = jsonStringify(values.subList(4, 9));
            middle = "," + middle.substring(1, middle.length() - 1) + ",";
            String suffix = jsonStringify(values.subList(10, values.size()));
            suffix = "," + suffix.substring(1);
            byte[] seedBytes = seed.getBytes(StandardCharsets.UTF_8);
            int diffLen = target.length;
            for (int i = 0; i < 500_000; i++) {
                String candidate = prefix + i + middle + (i >> 1) + suffix;
                byte[] encoded = Base64.getEncoder().encode(candidate.getBytes(StandardCharsets.UTF_8));
                byte[] digest;
                try {
                    digest = java.security.MessageDigest.getInstance("SHA3-512").digest(join(seedBytes, encoded));
                } catch (NoSuchAlgorithmException e) {
                    throw new IOException("JDK 不支持 SHA3-512", e);
                }
                if (lessOrEqual(digest, target, diffLen)) {
                    return "gAAAAAB" + new String(encoded, StandardCharsets.US_ASCII);
                }
            }
            throw new IOException("ChatGPT PoW 在 500000 次尝试内未完成");
        }

        private java.util.List<Object> powConfig(Bootstrap bootstrap) {
            java.util.ArrayList<Object> values = new java.util.ArrayList<>();
            values.add(3200); values.add(new java.text.SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT-0500 (Eastern Standard Time)'", Locale.US).format(new java.util.Date()));
            values.add(4294705152L); values.add(1); values.add(USER_AGENT);
            values.add(bootstrap.scripts().isEmpty() ? BASE_URL + "/backend-api/sentinel/sdk.js" : bootstrap.scripts().get(ThreadLocalRandom.current().nextInt(bootstrap.scripts().size())));
            values.add(bootstrap.dataBuild()); values.add("en-US"); values.add("en-US,es-US,en,es"); values.add(Math.random());
            values.add("vendor-Google Inc."); values.add("__reactContainer"); values.add("window"); values.add(System.nanoTime() / 1_000_000.0);
            values.add(UUID.randomUUID().toString()); values.add(""); values.add(8); values.add(System.currentTimeMillis() * 1.0); values.add(0); values.add(0); values.add(0); values.add(0);
            return values;
        }

        private static byte[] hexBytes(String value) { return java.util.HexFormat.of().parseHex(value.replaceFirst("^0x", "")); }
        private static byte[] join(byte[] a, byte[] b) { byte[] out = java.util.Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, out, a.length, b.length); return out; }
        private static boolean lessOrEqual(byte[] a, byte[] b, int count) { for (int i = 0; i < count; i++) { int x = a[i] & 255, y = b[i] & 255; if (x < y) return true; if (x > y) return false; } return true; }

        private static String extractChatGptText(Object value, String current) {
            if (!(value instanceof Map<?, ?> map)) return current;
            for (Object candidate : new Object[]{map, map.get("v")}) {
                if (!(candidate instanceof Map<?, ?> nested)) continue;
                Object message = nested.get("message");
                if (message instanceof Map<?, ?> msg
                        && msg.get("author") instanceof Map<?, ?> author
                        && "assistant".equals(stringValue(author.get("role")))) {
                    Object content = msg.get("content");
                    if (content instanceof Map<?, ?> contentMap) {
                        if (contentMap.get("text") instanceof String text && !text.isBlank()) return text;
                        if (contentMap.get("parts") instanceof java.util.List<?> parts) {
                            StringBuilder out = new StringBuilder();
                            for (Object part : parts) if (part instanceof String s) out.append(s);
                            if (!out.isEmpty()) return out.toString();
                        }
                    }
                }
            }
            if ("/message/content/parts/0".equals(stringValue(map.get("p")))) {
                String valueText = stringValue(map.get("v"));
                if ("append".equals(stringValue(map.get("o")))) return current + valueText;
                if ("replace".equals(stringValue(map.get("o")))) return valueText;
            }
            if ("patch".equals(stringValue(map.get("o"))) && map.get("v") instanceof java.util.List<?> list) {
                String out = current; for (Object item : list) out = extractChatGptText(item, out); return out;
            }
            return current;
        }

        private static String jsonStringify(Object value) {
            if (value == null) return "null";
            if (value instanceof String s) return SimpleJson.quote(s);
            if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
            if (value instanceof Map<?, ?> map) { StringBuilder out = new StringBuilder("{"); boolean first = true; for (Map.Entry<?, ?> entry : map.entrySet()) { if (!first) out.append(','); first = false; out.append(SimpleJson.quote(String.valueOf(entry.getKey()))).append(':').append(jsonStringify(entry.getValue())); } return out.append('}').toString(); }
            if (value instanceof Iterable<?> iterable) { StringBuilder out = new StringBuilder("["); boolean first = true; for (Object item : iterable) { if (!first) out.append(','); first = false; out.append(jsonStringify(item)); } return out.append(']').toString(); }
            return SimpleJson.quote(String.valueOf(value));
        }

        private static String normalizeAccessToken(String token) { return token == null ? "" : token.replaceFirst("(?i)^Bearer\\s+", "").trim(); }
        private static IOException httpError(String operation, Response response) throws IOException { String body = response.body() == null ? "" : response.body().string(); return new IOException(operation + " 请求失败，HTTP 状态=" + response.code() + "，响应=" + preview(body)); }
        private record Bootstrap(java.util.List<String> scripts, String dataBuild) {}
        private record Sentinel(String token, String proofToken, String soToken) {}
    }

    private static final class ApiChatBackend implements OpenAiApiServer.ChatBackend {
        private final AppConfig config;
        private final AtomicReference<OkHttpClient> clientRef;
        private final Map<String, ApiConversation> conversations = new ConcurrentHashMap<>();

        private ApiChatBackend(AppConfig config, AtomicReference<OkHttpClient> clientRef) {
            this.config = config;
            this.clientRef = clientRef;
        }

        @Override
        public String complete(OpenAiApiServer.ChatRequest request, Consumer<String> deltaSink) throws IOException {
            String key = request.model() + ":" + request.sessionKey();
            ApiConversation conversation = conversations.computeIfAbsent(key, ignored -> new ApiConversation());
            synchronized (conversation) {
                if (request.newConversation()) {
                    conversation.clear();
                    if ("/new".equalsIgnoreCase(request.latestPrompt().trim())) {
                        return "已开启新对话。";
                    }
                }

                String prompt = apiPrompt(request, conversation);
                StringBuilder text = new StringBuilder();
                sendInternal(config, clientRef.get(), request.model(), prompt, request.images(),
                        ConversationState.stateless(conversation.chatGptConversation()), delta -> {
                    text.append(delta);
                    if (deltaSink != null) {
                        deltaSink.accept(delta);
                    }
                });
                String answer = text.toString();
                if (request.explicitSession() && request.messageCount() <= 1) {
                    conversation.appendUser(request.latestPrompt());
                    conversation.appendAssistant(answer);
                } else {
                    conversation.replaceTranscript(request.fullPrompt(), answer);
                }
                return answer;
            }
        }

        private static String apiPrompt(OpenAiApiServer.ChatRequest request, ApiConversation conversation) {
            String transcript;
            if (request.explicitSession() && request.messageCount() <= 1 && !conversation.transcript().isBlank()) {
                transcript = conversation.transcript()
                        + "\nuser: " + request.latestPrompt();
            } else {
                transcript = request.fullPrompt();
            }
            return isolatedPrompt(request, transcript);
        }

        private static String isolatedPrompt(String transcript) {
            return "当前对话正在通过 OpenAI 兼容网关回答。如果用户让你生成图片请你正常生成。图片生成请不要使用任何工具(如果你不是gemini请忽略图片相关)\n"
                    + "下面的“当前会话上下文”是唯一允许使用的上下文。\n"
                    + "如果上层客户端提供了工具，工具只能由 OpenAI 兼容客户端执行；你不能在 Claude 自己的环境、容器、浏览器、终端或内置工具里执行这些工具。\n"
                    + "当前会话上下文：\n"
                    + transcript;
        }

        private static String isolatedPrompt(OpenAiApiServer.ChatRequest request, String transcript) {
            String prompt = isolatedPrompt(transcript);
            if (!request.hasTools()) {
                return prompt;
            }
            return prompt + "\n\n下面是上层 OpenAI 客户端提供的工具 schema。你当前没有可直接执行的工具，只能决定是否请求客户端调用工具。\n"
                    + "可用工具如下：\n"
                    + request.toolsText()
                    + "\n\n如果需要调用工具，必须只输出一个 JSON 对象，不要输出任何解释、Markdown、代码块或自然语言。格式：\n"
                    + "{\"tool_calls\":[{\"function\":{\"name\":\"工具名\",\"arguments\":{\"参数名\":\"参数值\"}}}]}\n"
                    + "禁止声明你已经调用了工具；禁止尝试自己执行工具；禁止把工具调用改写成 shell、Python、浏览器或 Claude 内置工具操作。\n"
                    + "如果不确定参数，先用合理参数请求客户端工具调用，不要自己猜测执行结果。\n"
                    + "如果不需要调用工具，就正常回答。";
        }
    }

    private static final class ApiConversation {
        private final StringBuilder transcript = new StringBuilder();
        private final ChatGptConversation chatGpt = new ChatGptConversation();

        private void clear() {
            transcript.setLength(0);
            chatGpt.clear();
        }

        private String transcript() {
            return transcript.toString();
        }

        private ChatGptConversation chatGptConversation() {
            return chatGpt;
        }

        private void appendUser(String text) {
            append("user", text);
        }

        private void appendAssistant(String text) {
            append("assistant", text);
        }

        private void replaceTranscript(String fullPrompt, String assistantText) {
            transcript.setLength(0);
            transcript.append(fullPrompt);
            appendAssistant(assistantText);
        }

        private void append(String role, String text) {
            if (text == null || text.isBlank()) {
                return;
            }
            if (!transcript.isEmpty()) {
                transcript.append('\n');
            }
            transcript.append(role).append(": ").append(text);
        }
    }

    private static final class ConversationState {
        private String conversationId = "";
        private String responseId = "";
        private String choiceId = "";
        private int completedTurns;
        private final boolean stateless;
        private final ChatGptConversation chatGptConversation;

        private ConversationState() {
            this(false);
        }

        private ConversationState(boolean stateless) {
            this(stateless, new ChatGptConversation());
        }

        private ConversationState(boolean stateless, ChatGptConversation chatGptConversation) {
            this.stateless = stateless;
            this.chatGptConversation = chatGptConversation == null ? new ChatGptConversation() : chatGptConversation;
        }

        private static ConversationState stateless() {
            return new ConversationState(true);
        }

        private static ConversationState stateless(ChatGptConversation chatGptConversation) {
            return new ConversationState(true, chatGptConversation);
        }

        private boolean isActive() {
            return !conversationId.isBlank() && !responseId.isBlank() && !choiceId.isBlank();
        }

        private boolean isStateless() {
            return stateless;
        }

        private ChatGptConversation chatGptConversation() {
            return chatGptConversation;
        }

        private void clear() {
            conversationId = "";
            responseId = "";
            choiceId = "";
            completedTurns = 0;
            chatGptConversation.clear();
        }

        private void completeTurn(GeminiResult result) {
            String oldConversationId = conversationId;
            String oldResponseId = responseId;
            String oldChoiceId = choiceId;
            if (result.conversationId() != null && result.conversationId().startsWith("c_")) {
                this.conversationId = result.conversationId();
            }
            if (result.responseId() != null && result.responseId().startsWith("r_")) {
                this.responseId = result.responseId();
            }
            if (result.choiceId() != null && result.choiceId().startsWith("rc_")) {
                this.choiceId = result.choiceId();
            }
            if (isActive() && (!conversationId.equals(oldConversationId)
                    || !responseId.equals(oldResponseId)
                    || !choiceId.equals(oldChoiceId))) {
                completedTurns++;
            }
        }

        private String conversationId() {
            return conversationId;
        }

        private String responseId() {
            return responseId;
        }

        private String choiceId() {
            return choiceId;
        }

        private int nextTurnIndex() {
            return completedTurns;
        }
    }

    static final class BardRpcException extends IOException {
        private final String code;

        private BardRpcException(String code, String body) {
            super("Gemini RPC 返回 BardErrorInfo，错误码=" + code + "。响应=" + preview(body));
            this.code = code;
        }

        private String code() {
            return code;
        }
    }

    private enum ModelProvider {
        GEMINI,
        CLAUDE,
        CHATGPT
    }

    private record ModelProfile(String name, ModelProvider provider, String headerToken, Integer headerMode,
                                String requestHash, Integer tailMode) {
    }

    private record GeminiAttachment(String path, String mimeType, String filename) {
    }

    private record ImageUploadResult(java.util.List<GeminiAttachment> attachments, java.util.List<String> skipped) {
        private static ImageUploadResult empty() {
            return new ImageUploadResult(java.util.List.of(), java.util.List.of());
        }

        private int skippedCount() {
            return skipped == null ? 0 : skipped.size();
        }
    }

    private record ClaudeUploadResult(java.util.List<String> fileUuids, java.util.List<String> skipped) {
        private static ClaudeUploadResult empty() {
            return new ClaudeUploadResult(java.util.List.of(), java.util.List.of());
        }

        private int skippedCount() {
            return skipped == null ? 0 : skipped.size();
        }
    }

    private record GeminiResult(String text, String conversationId, String responseId, String choiceId) {
        private static GeminiResult empty() {
            return new GeminiResult("", "", "", "");
        }

        private GeminiResult merge(GeminiResult other) {
            if (other == null) {
                return this;
            }
            return new GeminiResult(
                    other.text == null || other.text.isBlank() ? this.text : other.text,
                    other.conversationId == null || other.conversationId.isBlank() ? this.conversationId : other.conversationId,
                    other.responseId == null || other.responseId.isBlank() ? this.responseId : other.responseId,
                    other.choiceId == null || other.choiceId.isBlank() ? this.choiceId : other.choiceId
            );
        }
    }

    private record ConversationIds(String conversationId, String responseId) {
    }

    private static final class MiniJson {
        private final String text;
        private int index;

        private MiniJson(String text) {
            this.text = text == null ? "" : text;
        }

        private static Object parse(String text) {
            try {
                return new MiniJson(text).readValue();
            } catch (RuntimeException e) {
                return null;
            }
        }

        private Object readValue() {
            skipWhitespace();
            if (index >= text.length()) {
                return null;
            }

            char c = text.charAt(index);
            if (c == '"') {
                JsonString value = readJsonString(text, index);
                if (value == null) {
                    throw new IllegalArgumentException("bad string");
                }
                index = value.endIndex();
                return value.value();
            }
            if (c == '[') {
                return readArray();
            }
            if (c == '{') {
                return readObject();
            }
            if (text.startsWith("null", index)) {
                index += 4;
                return null;
            }
            if (text.startsWith("true", index)) {
                index += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", index)) {
                index += 5;
                return Boolean.FALSE;
            }
            return readNumberOrToken();
        }

        private java.util.List<Object> readArray() {
            java.util.List<Object> values = new java.util.ArrayList<>();
            index++;
            skipWhitespace();
            if (peek(']')) {
                index++;
                return values;
            }

            while (index < text.length()) {
                values.add(readValue());
                skipWhitespace();
                if (peek(',')) {
                    index++;
                    continue;
                }
                if (peek(']')) {
                    index++;
                    break;
                }
                break;
            }
            return values;
        }

        private Map<String, Object> readObject() {
            Map<String, Object> values = new LinkedHashMap<>();
            index++;
            skipWhitespace();
            if (peek('}')) {
                index++;
                return values;
            }

            while (index < text.length()) {
                Object key = readValue();
                skipWhitespace();
                if (peek(':')) {
                    index++;
                }
                Object value = readValue();
                values.put(Objects.toString(key, ""), value);
                skipWhitespace();
                if (peek(',')) {
                    index++;
                    continue;
                }
                if (peek('}')) {
                    index++;
                    break;
                }
                break;
            }
            return values;
        }

        private Object readNumberOrToken() {
            int start = index;
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == ',' || c == ']' || c == '}' || Character.isWhitespace(c)) {
                    break;
                }
                index++;
            }
            String token = text.substring(start, index);
            if (token.contains(".") || token.contains("e") || token.contains("E")) {
                try {
                    return Double.parseDouble(token);
                } catch (NumberFormatException ignored) {
                    return token;
                }
            }
            try {
                return Long.parseLong(token);
            } catch (NumberFormatException ignored) {
                return token;
            }
        }

        private boolean peek(char c) {
            return index < text.length() && text.charAt(index) == c;
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }
    }
}
