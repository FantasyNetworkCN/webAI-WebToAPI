import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class OpenAiApiServer {
    private final String host;
    private final int port;
    private final List<String> models;
    private final String defaultModel;
    private final ChatBackend backend;
    private HttpServer server;
    private ExecutorService executor;

    OpenAiApiServer(String host, int port, List<String> models, String defaultModel, ChatBackend backend) {
        this.host = host;
        this.port = port;
        this.defaultModel = defaultModel == null || defaultModel.isBlank() ? "Gemini-web" : defaultModel;
        this.models = models == null || models.isEmpty() ? List.of(this.defaultModel) : List.copyOf(models);
        this.backend = backend;
    }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/v1/responses", this::handleResponses);
        server.createContext("/v1/chat/completions", this::handleChatCompletions);
        server.createContext("/v1/models", this::handleModels);
        server.createContext("/debug/openai-logs", this::handleOpenAiLogs);
        server.createContext("/", this::handleStatic);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        System.out.println("OpenAI API 已启动：http://" + host + ":" + port);
        System.out.println("前端页面：http://" + host + ":" + port + "/");
        System.out.println("POST /v1/responses");
        System.out.println("POST /v1/chat/completions");
    }

    void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void handleModels(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange, "GET, POST, OPTIONS");
        if (handleCorsPreflight(exchange)) {
            return;
        }
        if (!isMethod(exchange, "GET") && !isMethod(exchange, "POST")) {
            sendMethodNotAllowed(exchange, "GET, POST, OPTIONS");
            return;
        }
        StringBuilder json = new StringBuilder("{\"object\":\"list\",\"data\":[");
        for (int i = 0; i < models.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":")
                    .append(SimpleJson.quote(models.get(i)))
                    .append(",\"object\":\"model\",\"created\":0,\"owned_by\":\"local\"}");
        }
        json.append("]}");
        sendJson(exchange, 200, json.toString());
    }

    private void handleOpenAiLogs(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange, "GET, POST, OPTIONS");
        if (handleCorsPreflight(exchange)) {
            return;
        }
        if (!isMethod(exchange, "GET") && !isMethod(exchange, "POST")) {
            sendMethodNotAllowed(exchange, "GET, POST, OPTIONS");
            return;
        }

        int limit = queryInt(exchange.getRequestURI().getRawQuery(), "limit", 80, 1, 500);
        Path path = Path.of("logs", "openai-requests.jsonl");
        if (!Files.exists(path)) {
            sendJson(exchange, 200, "{\"path\":\"logs/openai-requests.jsonl\",\"lines\":[]}");
            return;
        }

        List<String> all = Files.readAllLines(path, StandardCharsets.UTF_8);
        int from = Math.max(0, all.size() - limit);
        StringBuilder json = new StringBuilder("{\"path\":\"logs/openai-requests.jsonl\",\"lines\":[");
        for (int i = from; i < all.size(); i++) {
            if (i > from) {
                json.append(',');
            }
            json.append(SimpleJson.quote(all.get(i)));
        }
        json.append("]}");
        sendJson(exchange, 200, json.toString());
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange, "GET, HEAD, OPTIONS");
        if (handleCorsPreflight(exchange)) {
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/v1/")) {
            sendJson(exchange, 404, errorJson("接口未实现：" + path, "not_found_error"));
            return;
        }

        if (!isMethod(exchange, "GET") && !isMethod(exchange, "HEAD")) {
            sendMethodNotAllowed(exchange, "GET, HEAD, OPTIONS");
            return;
        }

        if (!"/".equals(path) && !"/index.html".equals(path)) {
            sendText(exchange, 404, "Not Found", "text/plain; charset=utf-8");
            return;
        }

        try (InputStream in = OpenAiApiServer.class.getResourceAsStream("/web/index.html")) {
            if (in == null) {
                sendText(exchange, 500, "前端资源缺失：/web/index.html", "text/plain; charset=utf-8");
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, "HEAD".equalsIgnoreCase(exchange.getRequestMethod()) ? -1 : bytes.length);
            if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        }
    }

    private void handleChatCompletions(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange, "POST, OPTIONS");
        if (handleCorsPreflight(exchange)) {
            return;
        }
        if (!isMethod(exchange, "POST")) {
            sendMethodNotAllowed(exchange, "POST, OPTIONS");
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Object parsed = SimpleJson.parse(body);
            if (!(parsed instanceof Map<?, ?> request)) {
                sendJson(exchange, 400, errorJson("请求体不是 JSON object"));
                return;
            }

            PromptData prompt = promptFromMessages(request.get("messages"));
            if (prompt.latest().isBlank() && prompt.images().isEmpty()) {
                sendJson(exchange, 400, errorJson("messages 为空"));
                return;
            }

            boolean stream = Boolean.TRUE.equals(request.get("stream"));
            String model = resolveModel(request.get("model"));
            SessionKey sessionKey = sessionKey(request, exchange, prompt);
            String toolsText = toolsText(request.get("tools"), request.get("tool_choice"));
            logRequest(exchange, body, request, model, stream, sessionKey, prompt, toolsText);
            ChatRequest chatRequest = new ChatRequest(
                    model,
                    sessionKey.value(),
                    sessionKey.explicit(),
                    prompt.full(),
                    prompt.latest(),
                    prompt.messageCount(),
                    prompt.images(),
                    toolsText,
                    wantsNewConversation(request, prompt, sessionKey));
            if (stream) {
                handleStream(exchange, chatRequest);
                return;
            }

            String text = backend.complete(chatRequest, null);
            ToolCallResult toolCall = parseToolCallResult(text);
            sendJson(exchange, 200, toolCall == null
                    ? completionJson(model, text)
                    : toolCompletionJson(model, toolCall));
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, errorJson(e.getMessage(), "invalid_request_error"));
        } catch (Exception e) {
            sendJson(exchange, 500, errorJson(e.getMessage()));
        }
    }

    private void handleResponses(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange, "POST, OPTIONS");
        if (handleCorsPreflight(exchange)) {
            return;
        }
        if (!isMethod(exchange, "POST")) {
            sendMethodNotAllowed(exchange, "POST, OPTIONS");
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Object parsed = SimpleJson.parse(body);
            if (!(parsed instanceof Map<?, ?> request)) {
                sendJson(exchange, 400, errorJson("请求体不是 JSON object", "invalid_request_error"));
                return;
            }

            PromptData prompt = promptFromResponsesRequest(request);
            if (prompt.latest().isBlank() && prompt.images().isEmpty()) {
                sendJson(exchange, 400, errorJson("input 为空", "invalid_request_error"));
                return;
            }

            boolean stream = Boolean.TRUE.equals(request.get("stream"));
            String model = resolveModel(request.get("model"));
            SessionKey sessionKey = sessionKey(request, exchange, prompt);
            String toolsText = responsesToolsText(request.get("tools"), request.get("tool_choice"));
            logRequest(exchange, body, request, model, stream, sessionKey, prompt, toolsText);
            ChatRequest chatRequest = new ChatRequest(
                    model,
                    sessionKey.value(),
                    sessionKey.explicit(),
                    prompt.full(),
                    prompt.latest(),
                    prompt.messageCount(),
                    prompt.images(),
                    toolsText,
                    wantsNewConversation(request, prompt, sessionKey));
            if (stream) {
                handleResponseStream(exchange, chatRequest);
                return;
            }

            String text = backend.complete(chatRequest, null);
            ToolCallResult toolCall = parseToolCallResult(text);
            sendJson(exchange, 200, toolCall == null
                    ? responseJson(model, text)
                    : toolResponseJson(model, toolCall));
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, errorJson(e.getMessage(), "invalid_request_error"));
        } catch (Exception e) {
            sendJson(exchange, 500, errorJson(e.getMessage()));
        }
    }

    private void handleStream(HttpExchange exchange, ChatRequest request) throws IOException {
        addCorsHeaders(exchange, "POST, OPTIONS");
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        String id = "chatcmpl-" + UUID.randomUUID();
        long created = Instant.now().getEpochSecond();
        try (OutputStream out = exchange.getResponseBody()) {
            if (request.hasTools()) {
                String text = backend.complete(request, null);
                ToolCallResult toolCall = parseToolCallResult(text);
                if (toolCall == null) {
                    if (!text.isEmpty()) {
                        sendSse(out, chunkJson(id, request.model(), created, text, false));
                    }
                    sendSse(out, chunkJson(id, request.model(), created, "", true));
                } else {
                    sendSse(out, toolChunkJson(id, request.model(), created, toolCall, false));
                    sendSse(out, toolChunkJson(id, request.model(), created, toolCall, true));
                }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }

            backend.complete(request, delta -> {
                try {
                    if (!delta.isEmpty()) {
                        sendSse(out, chunkJson(id, request.model(), created, delta, false));
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            sendSse(out, chunkJson(id, request.model(), created, "", true));
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private void handleResponseStream(HttpExchange exchange, ChatRequest request) throws IOException {
        addCorsHeaders(exchange, "POST, OPTIONS");
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        String id = "resp_" + UUID.randomUUID().toString().replace("-", "");
        long created = Instant.now().getEpochSecond();
        try (OutputStream out = exchange.getResponseBody()) {
            sendSse(out, responseCreatedEvent(id, request.model(), created));
            if (request.hasTools()) {
                String text = backend.complete(request, null);
                ToolCallResult toolCall = parseToolCallResult(text);
                if (toolCall == null) {
                    if (!text.isEmpty()) {
                        sendSse(out, responseTextDeltaEvent(id, text));
                    }
                    sendSse(out, responseCompletedEvent(id, request.model(), created, text));
                } else {
                    sendSse(out, responseCompletedEvent(id, request.model(), created, toolResponseSummary(toolCall)));
                }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }

            StringBuilder text = new StringBuilder();
            backend.complete(request, delta -> {
                try {
                    if (!delta.isEmpty()) {
                        text.append(delta);
                        sendSse(out, responseTextDeltaEvent(id, delta));
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            sendSse(out, responseCompletedEvent(id, request.model(), created, text.toString()));
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static PromptData promptFromMessages(Object messages) {
        if (!(messages instanceof List<?> list)) {
            return new PromptData("", "", "", 0, List.of());
        }

        StringBuilder full = new StringBuilder();
        String firstUser = "";
        String latest = "";
        int messageCount = 0;
        java.util.ArrayList<ImageInput> allImages = new java.util.ArrayList<>();
        java.util.ArrayList<ImageInput> latestUserImages = new java.util.ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> message)) {
                continue;
            }
            String role = stringValue(message.get("role"), "user");
            ContentParts parts = contentParts(message.get("content"));
            String content = parts.text();
            if (content.isBlank() && "assistant".equalsIgnoreCase(role)) {
                content = toolCallsText(message.get("tool_calls"));
            }
            if (content.isBlank() && parts.images().isEmpty()) {
                continue;
            }
            String label = roleLabel(role, message);
            if (!full.isEmpty()) {
                full.append('\n');
            }
            full.append(label).append(": ").append(content);
            if (!parts.images().isEmpty()) {
                if (!content.isBlank()) {
                    full.append('\n');
                }
                full.append("[images: ").append(parts.images().size()).append(']');
            }
            messageCount++;
            allImages.addAll(parts.images());
            if ("user".equalsIgnoreCase(role)) {
                if (firstUser.isBlank()) {
                    firstUser = content;
                }
                latest = content;
                latestUserImages.clear();
                latestUserImages.addAll(parts.images());
            } else if (latest.isBlank()) {
                latest = content;
            }
        }
        List<ImageInput> images = latestUserImages.isEmpty() ? List.copyOf(allImages) : List.copyOf(latestUserImages);
        return new PromptData(full.toString(), latest, firstUser.isBlank() ? latest : firstUser, messageCount, images);
    }

    private static PromptData promptFromResponsesRequest(Map<?, ?> request) {
        Object input = request.get("input");
        if (input == null) {
            return promptFromMessages(request.get("messages"));
        }

        String instructions = contentText(request.get("instructions")).trim();
        if (input instanceof String text) {
            String body = text.trim();
            String full = withInstructions(instructions, body.isBlank() ? "" : "user: " + body);
            String latest = body.isBlank() ? instructions : body;
            return new PromptData(full, latest, body.isBlank() ? latest : body, latest.isBlank() ? 0 : 1, List.of());
        }

        if (!(input instanceof List<?> list)) {
            String text = scalarString(input);
            String full = withInstructions(instructions, text.isBlank() ? "" : "user: " + text);
            String latest = text.isBlank() ? instructions : text;
            return new PromptData(full, latest, text.isBlank() ? latest : text, latest.isBlank() ? 0 : 1, List.of());
        }

        StringBuilder full = new StringBuilder();
        if (!instructions.isBlank()) {
            full.append("system: ").append(instructions);
        }
        String firstUser = "";
        String latest = "";
        int messageCount = instructions.isBlank() ? 0 : 1;
        java.util.ArrayList<ImageInput> allImages = new java.util.ArrayList<>();
        java.util.ArrayList<ImageInput> latestUserImages = new java.util.ArrayList<>();
        for (Object item : list) {
            InputMessage message = responseInputMessage(item);
            if (message.text().isBlank() && message.images().isEmpty()) {
                continue;
            }
            if (!full.isEmpty()) {
                full.append('\n');
            }
            full.append(message.role()).append(": ").append(message.text());
            if (!message.images().isEmpty()) {
                if (!message.text().isBlank()) {
                    full.append('\n');
                }
                full.append("[images: ").append(message.images().size()).append(']');
            }
            messageCount++;
            allImages.addAll(message.images());
            if ("user".equalsIgnoreCase(message.role())) {
                if (firstUser.isBlank()) {
                    firstUser = message.text();
                }
                latest = message.text();
                latestUserImages.clear();
                latestUserImages.addAll(message.images());
            } else if (latest.isBlank()) {
                latest = message.text();
            }
        }
        if (latest.isBlank()) {
            latest = instructions;
        }
        List<ImageInput> images = latestUserImages.isEmpty() ? List.copyOf(allImages) : List.copyOf(latestUserImages);
        return new PromptData(full.toString(), latest, firstUser.isBlank() ? latest : firstUser, messageCount, images);
    }

    private static String withInstructions(String instructions, String body) {
        if (instructions.isBlank()) {
            return body;
        }
        if (body.isBlank()) {
            return "system: " + instructions;
        }
        return "system: " + instructions + "\n" + body;
    }

    private static InputMessage responseInputMessage(Object item) {
        if (item instanceof String text) {
            ContentParts parts = textWithInlineImages(text);
            return new InputMessage("user", parts.text(), parts.images());
        }
        if (!(item instanceof Map<?, ?> map)) {
            ContentParts parts = textWithInlineImages(scalarString(item));
            return new InputMessage("user", parts.text(), parts.images());
        }

        String role = firstNonBlank(
                stringValue(map.get("role"), ""),
                responseRoleFromType(stringValue(map.get("type"), "")),
                "user");
        Object content = firstNonNull(map.get("content"), map.get("text"), map.get("input"), map.get("output"));
        ContentParts parts = responseContentParts(content);
        String text = parts.text();
        if (text.isBlank()) {
            text = responseContentText(map.get("arguments"));
        }
        return new InputMessage(role, text, parts.images());
    }

    private static String responseRoleFromType(String type) {
        if ("message".equalsIgnoreCase(type)) {
            return "";
        }
        if (type != null && type.toLowerCase().contains("assistant")) {
            return "assistant";
        }
        if (type != null && type.toLowerCase().contains("system")) {
            return "system";
        }
        if (type != null && type.toLowerCase().contains("tool")) {
            return "tool";
        }
        return "user";
    }

    private static String responseContentText(Object content) {
        return responseContentParts(content).text();
    }

    private static ContentParts responseContentParts(Object content) {
        if (content instanceof String text) {
            return textWithInlineImages(text);
        }
        if (content instanceof Number || content instanceof Boolean) {
            return new ContentParts(String.valueOf(content), List.of());
        }
        if (content instanceof Map<?, ?> map) {
            String type = stringValue(map.get("type"), "");
            if (isImagePartType(type)) {
                ImageInput image = imageInputFromPart(map);
                return new ContentParts("", image == null ? List.of() : List.of(image));
            }
            return responseContentParts(firstNonNull(
                    map.get("text"),
                    map.get("content"),
                    map.get("input_text"),
                    map.get("output_text"),
                    map.get("arguments")));
        }
        if (!(content instanceof List<?> parts)) {
            return new ContentParts("", List.of());
        }

        StringBuilder out = new StringBuilder();
        java.util.ArrayList<ImageInput> images = new java.util.ArrayList<>();
        for (Object part : parts) {
            ContentParts child = responseContentParts(part);
            if (!child.text().isBlank()) {
                if (!out.isEmpty()) {
                    out.append('\n');
                }
                out.append(child.text());
            }
            images.addAll(child.images());
        }
        return new ContentParts(out.toString(), List.copyOf(images));
    }

    private static String roleLabel(String role, Map<?, ?> message) {
        if (!"tool".equalsIgnoreCase(role)) {
            return role;
        }
        String name = firstNonBlank(
                stringValue(message.get("name"), ""),
                stringValue(message.get("tool_call_id"), ""));
        return name.isBlank() ? "tool" : "tool " + name;
    }

    private static String toolsText(Object tools, Object toolChoice) {
        if (!(tools instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        return "tool_choice: " + toJson(toolChoice == null ? "auto" : toolChoice)
                + "\ntools: " + toJson(list);
    }

    private static String responsesToolsText(Object tools, Object toolChoice) {
        if (!(tools instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        return "tool_choice: " + toJson(toolChoice == null ? "auto" : toolChoice)
                + "\nresponses_tools: " + toJson(list);
    }

    private static String toolCallsText(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        return "tool_calls: " + toJson(list);
    }

    private static SessionKey sessionKey(Map<?, ?> request, HttpExchange exchange, PromptData prompt) {
        List<SessionCandidate> candidates = sessionCandidates(request, exchange);
        if (!candidates.isEmpty()) {
            SessionCandidate explicit = candidates.get(0);
            return new SessionKey("explicit:" + cleanKey(explicit.value()), true, explicit.source(), candidates);
        }

        String origin = firstNonBlank(
                exchange.getRequestHeaders().getFirst("origin"),
                exchange.getRequestHeaders().getFirst("referer"),
                exchange.getRemoteAddress() == null ? "" : exchange.getRemoteAddress().getAddress().getHostAddress());
        String user = stringValue(request.get("user"), "");
        String seed = firstNonBlank(prompt.firstUser(), prompt.latest(), prompt.full());
        return new SessionKey("auto:" + shortHash(origin + "\n" + user + "\n" + seed),
                false,
                "auto:origin+user+first_user",
                candidates);
    }

    private static List<SessionCandidate> sessionCandidates(Map<?, ?> request, HttpExchange exchange) {
        java.util.ArrayList<SessionCandidate> candidates = new java.util.ArrayList<>();
        addCandidate(candidates, "body.conversation_id", request.get("conversation_id"));
        addCandidate(candidates, "body.session_id", request.get("session_id"));
        addCandidate(candidates, "body.chat_id", request.get("chat_id"));
        addCandidate(candidates, "body.thread_id", request.get("thread_id"));
        addCandidate(candidates, "body.channel_id", request.get("channel_id"));
        addCandidate(candidates, "body.room_id", request.get("room_id"));
        addCandidate(candidates, "body.dialogue_id", request.get("dialogue_id"));
        addCandidate(candidates, "body.dialog_id", request.get("dialog_id"));
        addCandidate(candidates, "body.context_id", request.get("context_id"));

        Object metadata = request.get("metadata");
        addCandidate(candidates, "metadata.conversation_id", nestedString(metadata, "conversation_id"));
        addCandidate(candidates, "metadata.session_id", nestedString(metadata, "session_id"));
        addCandidate(candidates, "metadata.chat_id", nestedString(metadata, "chat_id"));
        addCandidate(candidates, "metadata.thread_id", nestedString(metadata, "thread_id"));
        addCandidate(candidates, "metadata.channel_id", nestedString(metadata, "channel_id"));
        addCandidate(candidates, "metadata.room_id", nestedString(metadata, "room_id"));
        addCandidate(candidates, "metadata.dialogue_id", nestedString(metadata, "dialogue_id"));
        addCandidate(candidates, "metadata.dialog_id", nestedString(metadata, "dialog_id"));
        addCandidate(candidates, "metadata.context_id", nestedString(metadata, "context_id"));

        addHeaderCandidate(candidates, exchange, "x-conversation-id");
        addHeaderCandidate(candidates, exchange, "x-session-id");
        addHeaderCandidate(candidates, exchange, "x-chat-id");
        addHeaderCandidate(candidates, exchange, "x-thread-id");
        addHeaderCandidate(candidates, exchange, "x-openwebui-chat-id");
        addHeaderCandidate(candidates, exchange, "x-astrbot-session-id");

        collectNestedSessionCandidates(candidates, request, "body", 0);
        addCompoundAstrBotCandidate(candidates, request, exchange);
        return dedupeCandidates(candidates);
    }

    private static void addHeaderCandidate(List<SessionCandidate> candidates, HttpExchange exchange, String name) {
        addCandidate(candidates, "header." + name, exchange.getRequestHeaders().getFirst(name));
    }

    private static void addCompoundAstrBotCandidate(List<SessionCandidate> candidates,
                                                    Map<?, ?> request,
                                                    HttpExchange exchange) {
        String platform = firstNonBlank(
                findNestedString(request, "platform"),
                findNestedString(request, "adapter"),
                findNestedString(request, "provider"),
                exchange.getRequestHeaders().getFirst("x-astrbot-platform"),
                exchange.getRequestHeaders().getFirst("x-platform"));
        String space = firstNonBlank(
                findNestedString(request, "group_id"),
                findNestedString(request, "guild_id"),
                findNestedString(request, "channel_id"),
                findNestedString(request, "room_id"),
                findNestedString(request, "chat_id"),
                findNestedString(request, "thread_id"),
                exchange.getRequestHeaders().getFirst("x-astrbot-group-id"),
                exchange.getRequestHeaders().getFirst("x-astrbot-channel-id"),
                exchange.getRequestHeaders().getFirst("x-group-id"),
                exchange.getRequestHeaders().getFirst("x-channel-id"));
        String user = firstNonBlank(
                findNestedString(request, "user_id"),
                findNestedString(request, "sender_id"),
                findNestedString(request, "author_id"),
                findNestedString(request, "from_id"),
                exchange.getRequestHeaders().getFirst("x-astrbot-user-id"),
                exchange.getRequestHeaders().getFirst("x-user-id"));

        if (!platform.isBlank() && !space.isBlank()) {
            addCandidate(candidates, "compound.platform+space+user",
                    platform + ":" + space + (user.isBlank() ? "" : ":" + user));
        } else if (!platform.isBlank() && !user.isBlank()) {
            addCandidate(candidates, "compound.platform+user", platform + ":" + user);
        }
    }

    private static void collectNestedSessionCandidates(List<SessionCandidate> candidates,
                                                       Object value,
                                                       String path,
                                                       int depth) {
        if (value == null || depth > 6) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String childPath = path + "." + key;
                if (isStableSessionField(key)) {
                    addCandidate(candidates, childPath, entry.getValue());
                }
                collectNestedSessionCandidates(candidates, entry.getValue(), childPath, depth + 1);
            }
            return;
        }
        if (value instanceof List<?> list) {
            int limit = Math.min(list.size(), 20);
            for (int i = 0; i < limit; i++) {
                collectNestedSessionCandidates(candidates, list.get(i), path + "[" + i + "]", depth + 1);
            }
        }
    }

    private static String findNestedString(Object value, String key) {
        return findNestedString(value, key, 0);
    }

    private static String findNestedString(Object value, String key, int depth) {
        if (value == null || depth > 6) {
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (key.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                    String text = stringValue(entry.getValue(), "");
                    if (!text.isBlank()) {
                        return text;
                    }
                    if (entry.getValue() instanceof Number || entry.getValue() instanceof Boolean) {
                        return String.valueOf(entry.getValue());
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
        if (value instanceof List<?> list) {
            for (Object child : list) {
                String found = findNestedString(child, key, depth + 1);
                if (!found.isBlank()) {
                    return found;
                }
            }
        }
        return "";
    }

    private static boolean isStableSessionField(String key) {
        String lower = key == null ? "" : key.toLowerCase();
        return lower.equals("conversation_id")
                || lower.equals("conversationid")
                || lower.equals("session_id")
                || lower.equals("sessionid")
                || lower.equals("chat_id")
                || lower.equals("chatid")
                || lower.equals("thread_id")
                || lower.equals("threadid")
                || lower.equals("channel_id")
                || lower.equals("channelid")
                || lower.equals("room_id")
                || lower.equals("roomid")
                || lower.equals("dialogue_id")
                || lower.equals("dialogueid")
                || lower.equals("dialog_id")
                || lower.equals("dialogid")
                || lower.equals("context_id")
                || lower.equals("contextid");
    }

    private static void addCandidate(List<SessionCandidate> candidates, String source, Object value) {
        String text = scalarString(value);
        if (!text.isBlank()) {
            candidates.add(new SessionCandidate(source, text));
        }
    }

    private static String scalarString(Object value) {
        if (value instanceof String text) {
            return text.trim();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "";
    }

    private static List<SessionCandidate> dedupeCandidates(List<SessionCandidate> candidates) {
        java.util.ArrayList<SessionCandidate> out = new java.util.ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (SessionCandidate candidate : candidates) {
            String key = candidate.source() + "\n" + candidate.value();
            if (seen.add(key)) {
                out.add(candidate);
            }
        }
        return List.copyOf(out);
    }

    private static boolean wantsNewConversation(Map<?, ?> request, PromptData prompt, SessionKey sessionKey) {
        return booleanValue(request.get("new_conversation"))
                || booleanValue(request.get("new"))
                || "/new".equalsIgnoreCase(prompt.latest().trim())
                || (!sessionKey.explicit() && prompt.messageCount() <= 1);
    }

    private static void logRequest(HttpExchange exchange, String rawBody, Map<?, ?> request, String model,
                                   boolean stream, SessionKey sessionKey, PromptData prompt, String toolsText) {
        try {
            Path dir = Path.of("logs");
            Files.createDirectories(dir);
            String line = "{"
                    + "\"time\":" + SimpleJson.quote(Instant.now().toString()) + ","
                    + "\"remote\":" + SimpleJson.quote(exchange.getRemoteAddress() == null ? "" : exchange.getRemoteAddress().toString()) + ","
                    + "\"method\":" + SimpleJson.quote(exchange.getRequestMethod()) + ","
                    + "\"path\":" + SimpleJson.quote(exchange.getRequestURI().toString()) + ","
                    + "\"model\":" + SimpleJson.quote(model) + ","
                    + "\"stream\":" + stream + ","
                    + "\"session_key_hash\":" + SimpleJson.quote(shortHash(sessionKey.value())) + ","
                    + "\"session_source\":" + SimpleJson.quote(sessionKey.source()) + ","
                    + "\"session_explicit\":" + sessionKey.explicit() + ","
                    + "\"session_candidates\":" + sessionCandidatesJson(sessionKey.candidates()) + ","
                    + "\"new_conversation\":" + wantsNewConversation(request, prompt, sessionKey) + ","
                    + "\"message_count\":" + prompt.messageCount() + ","
                    + "\"latest\":" + SimpleJson.quote(prompt.latest()) + ","
                    + "\"first_user\":" + SimpleJson.quote(prompt.firstUser()) + ","
                    + "\"has_tools\":" + !toolsText.isBlank() + ","
                    + "\"headers\":" + requestHeadersJson(exchange) + ","
                    + "\"body_hash\":" + SimpleJson.quote(shortHash(rawBody)) + ","
                    + "\"body\":" + toJson(sanitizeForLog(request, "body"))
                    + "}\n";
            Files.writeString(dir.resolve("openai-requests.jsonl"), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private static String requestHeadersJson(HttpExchange exchange) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            if (!isInterestingHeader(entry.getKey())) {
                continue;
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            String key = entry.getKey().toLowerCase();
            out.append(SimpleJson.quote(key))
                    .append(':')
                    .append(toJson(logHeaderValues(key, entry.getValue())));
        }
        return out.append('}').toString();
    }

    private static boolean isInterestingHeader(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        return lower.equals("user-agent")
                || lower.equals("origin")
                || lower.equals("referer")
                || lower.equals("x-conversation-id")
                || lower.equals("x-session-id")
                || lower.equals("x-chat-id")
                || lower.equals("x-thread-id")
                || lower.equals("x-openwebui-chat-id")
                || lower.equals("x-astrbot-session-id")
                || lower.equals("x-astrbot-platform")
                || lower.equals("x-astrbot-user-id")
                || lower.equals("x-astrbot-group-id")
                || lower.equals("x-astrbot-channel-id")
                || lower.equals("x-platform")
                || lower.equals("x-user-id")
                || lower.equals("x-group-id")
                || lower.equals("x-channel-id");
    }

    private static List<String> logHeaderValues(String key, List<String> values) {
        if (values == null) {
            return List.of();
        }
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String value : values) {
            out.add(isSensitiveLogKey(key) ? fingerprint(value) : value);
        }
        return out;
    }

    private static String sessionCandidatesJson(List<SessionCandidate> candidates) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            SessionCandidate candidate = candidates.get(i);
            out.append("{\"source\":")
                    .append(SimpleJson.quote(candidate.source()))
                    .append(",\"value\":")
                    .append(SimpleJson.quote(fingerprint(candidate.value())))
                    .append('}');
        }
        return out.append(']').toString();
    }

    private static Object sanitizeForLog(Object value, String key) {
        if (value == null) {
            return null;
        }
        if (isSensitiveLogKey(key)) {
            return fingerprint(String.valueOf(value));
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String childKey = String.valueOf(entry.getKey());
                out.put(childKey, sanitizeForLog(entry.getValue(), childKey));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            java.util.ArrayList<Object> out = new java.util.ArrayList<>();
            for (Object item : list) {
                out.add(sanitizeForLog(item, key));
            }
            return out;
        }
        return value;
    }

    private static boolean isSensitiveLogKey(String key) {
        String lower = key == null ? "" : key.toLowerCase();
        return lower.equals("authorization")
                || lower.equals("cookie")
                || lower.contains("token")
                || lower.contains("secret")
                || lower.contains("password")
                || lower.contains("apikey")
                || lower.contains("api_key")
                || lower.equals("user")
                || lower.equals("id")
                || lower.endsWith("_id")
                || lower.endsWith("-id")
                || lower.contains("session")
                || lower.contains("conversation")
                || lower.contains("thread")
                || lower.contains("channel")
                || lower.contains("room")
                || lower.contains("dialog")
                || lower.contains("guild")
                || lower.contains("group")
                || lower.contains("sender")
                || lower.contains("author")
                || lower.equals("from");
    }

    private static String fingerprint(String value) {
        String text = value == null ? "" : value;
        return "sha256:" + shortHash(text) + ":len=" + text.length();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String nestedString(Object value, String key) {
        if (!(value instanceof Map<?, ?> map)) {
            return "";
        }
        return stringValue(map.get(key), "");
    }

    private static String cleanKey(String value) {
        String cleaned = value == null ? "" : value.replace('\0', '_').trim();
        return cleaned.isBlank() ? "default" : cleaned;
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12 && i < bytes.length; i++) {
                out.append(String.format("%02x", bytes[i] & 0xff));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return false;
    }

    private static int queryInt(String rawQuery, String name, int fallback, int min, int max) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return fallback;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (!name.equals(key)) {
                continue;
            }
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            try {
                int parsed = Integer.parseInt(value);
                return Math.max(min, Math.min(max, parsed));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static ContentParts contentParts(Object content) {
        if (content instanceof String text) {
            return textWithInlineImages(text);
        }
        if (!(content instanceof List<?> parts)) {
            return new ContentParts("", List.of());
        }

        StringBuilder out = new StringBuilder();
        java.util.ArrayList<ImageInput> images = new java.util.ArrayList<>();
        for (Object part : parts) {
            if (part instanceof Map<?, ?> map) {
                String type = stringValue(map.get("type"), "");
                if ("text".equals(type)) {
                    ContentParts text = textWithInlineImages(stringValue(map.get("text"), ""));
                    if (!out.isEmpty()) {
                        out.append('\n');
                    }
                    out.append(text.text());
                    images.addAll(text.images());
                } else if (isImagePartType(type)) {
                    ImageInput image = imageInputFromPart(map);
                    if (image != null) {
                        images.add(image);
                    }
                }
            }
        }
        return new ContentParts(out.toString(), List.copyOf(images));
    }

    private static String contentText(Object content) {
        return contentParts(content).text();
    }

    private static ContentParts textWithInlineImages(String text) {
        if (text == null || text.isBlank()) {
            return new ContentParts(text == null ? "" : text, List.of());
        }
        Matcher matcher = Pattern.compile("\\[Image Attachment:\\s*path\\s+([^\\]]+)]").matcher(text);
        java.util.ArrayList<ImageInput> images = new java.util.ArrayList<>();
        StringBuilder cleaned = new StringBuilder();
        while (matcher.find()) {
            String path = matcher.group(1).trim();
            images.add(new ImageInput(path, mimeFromFilename(path), filenameFromPath(path)));
            matcher.appendReplacement(cleaned, Matcher.quoteReplacement("[image]"));
        }
        matcher.appendTail(cleaned);
        return new ContentParts(cleaned.toString(), List.copyOf(images));
    }

    private static boolean isImagePartType(String type) {
        String lower = type == null ? "" : type.toLowerCase();
        return lower.equals("image_url")
                || lower.equals("input_image")
                || lower.equals("image")
                || lower.equals("file");
    }

    private static ImageInput imageInputFromPart(Map<?, ?> map) {
        Object imageUrl = firstNonNull(map.get("image_url"), map.get("input_image"), map.get("image"), map.get("file"));
        String url = "";
        if (imageUrl instanceof Map<?, ?> nested) {
            url = firstNonBlank(
                    stringValue(nested.get("url"), ""),
                    stringValue(nested.get("data"), ""),
                    stringValue(nested.get("base64"), ""),
                    stringValue(nested.get("image_url"), ""));
        } else {
            url = stringValue(imageUrl, "");
        }
        if (url.isBlank()) {
            url = firstNonBlank(
                    stringValue(map.get("url"), ""),
                    stringValue(map.get("data"), ""),
                    stringValue(map.get("base64"), ""));
        }
        if (url.isBlank()) {
            return null;
        }
        String mime = firstNonBlank(
                stringValue(map.get("mime_type"), ""),
                stringValue(map.get("mimeType"), ""),
                mimeFromDataUrl(url),
                mimeFromFilename(url));
        String filename = firstNonBlank(
                stringValue(map.get("filename"), ""),
                stringValue(map.get("name"), ""),
                "image_" + shortHash(url) + extensionForMime(mime));
        return new ImageInput(url, mime, filename);
    }

    private static String mimeFromDataUrl(String value) {
        if (value == null || !value.startsWith("data:")) {
            return "";
        }
        int semi = value.indexOf(';');
        return semi > 5 ? value.substring(5, semi) : "";
    }

    private static String mimeFromFilename(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/png";
    }

    private static String extensionForMime(String mime) {
        String lower = mime == null ? "" : mime.toLowerCase();
        if (lower.contains("jpeg") || lower.contains("jpg")) {
            return ".jpg";
        }
        if (lower.contains("webp")) {
            return ".webp";
        }
        if (lower.contains("gif")) {
            return ".gif";
        }
        return ".png";
    }

    private static String filenameFromPath(String path) {
        if (path == null || path.isBlank()) {
            return "image.png";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isBlank() ? "image.png" : name;
    }

    private static ToolCallResult parseToolCallResult(String text) {
        String json = extractJsonObject(text);
        if (json.isBlank()) {
            return null;
        }
        Object parsed = SimpleJson.parse(json);
        if (!(parsed instanceof Map<?, ?> root)) {
            return null;
        }

        Object callObject = root.get("tool_calls");
        if (callObject instanceof List<?> calls && !calls.isEmpty()) {
            return toolCallFromObject(calls.get(0));
        }
        return toolCallFromObject(root);
    }

    private static ToolCallResult toolCallFromObject(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Object function = map.get("function");
        if (function instanceof Map<?, ?> fn) {
            String name = stringValue(fn.get("name"), "");
            Object arguments = fn.get("arguments");
            if (!name.isBlank()) {
                return new ToolCallResult(name, argumentsJson(arguments));
            }
        }

        String name = firstNonBlank(
                stringValue(map.get("name"), ""),
                stringValue(map.get("tool"), ""),
                stringValue(map.get("function"), ""));
        Object arguments = firstNonNull(map.get("arguments"), map.get("args"), map.get("parameters"));
        if (name.isBlank()) {
            return null;
        }
        return new ToolCallResult(name, argumentsJson(arguments));
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String argumentsJson(Object value) {
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return trimmed;
            }
        }
        return value == null ? "{}" : toJson(value);
    }

    private static String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return trimmed.substring(start, end + 1);
    }

    private static String completionJson(String model, String text) {
        String id = "chatcmpl-" + UUID.randomUUID();
        long created = Instant.now().getEpochSecond();
        return "{"
                + "\"id\":" + SimpleJson.quote(id) + ","
                + "\"object\":\"chat.completion\","
                + "\"created\":" + created + ","
                + "\"model\":" + SimpleJson.quote(model) + ","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
                + SimpleJson.quote(text) + "},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}"
                + "}";
    }

    private static String toolCompletionJson(String model, ToolCallResult call) {
        String id = "chatcmpl-" + UUID.randomUUID();
        long created = Instant.now().getEpochSecond();
        return "{"
                + "\"id\":" + SimpleJson.quote(id) + ","
                + "\"object\":\"chat.completion\","
                + "\"created\":" + created + ","
                + "\"model\":" + SimpleJson.quote(model) + ","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
                + toolCallJson(call, 0)
                + "]},\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}"
                + "}";
    }

    private static String responseJson(String model, String text) {
        String id = "resp_" + UUID.randomUUID().toString().replace("-", "");
        long created = Instant.now().getEpochSecond();
        String outputId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        return "{"
                + "\"id\":" + SimpleJson.quote(id) + ","
                + "\"object\":\"response\","
                + "\"created_at\":" + created + ","
                + "\"status\":\"completed\","
                + "\"model\":" + SimpleJson.quote(model) + ","
                + "\"output_text\":" + SimpleJson.quote(text) + ","
                + "\"output\":[{\"id\":" + SimpleJson.quote(outputId)
                + ",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":"
                + SimpleJson.quote(text) + ",\"annotations\":[]}]}],"
                + "\"usage\":{\"input_tokens\":0,\"output_tokens\":0,\"total_tokens\":0}"
                + "}";
    }

    private static String toolResponseJson(String model, ToolCallResult call) {
        String id = "resp_" + UUID.randomUUID().toString().replace("-", "");
        long created = Instant.now().getEpochSecond();
        return "{"
                + "\"id\":" + SimpleJson.quote(id) + ","
                + "\"object\":\"response\","
                + "\"created_at\":" + created + ","
                + "\"status\":\"completed\","
                + "\"model\":" + SimpleJson.quote(model) + ","
                + "\"output_text\":\"\","
                + "\"output\":[" + responseToolCallJson(call) + "],"
                + "\"usage\":{\"input_tokens\":0,\"output_tokens\":0,\"total_tokens\":0}"
                + "}";
    }

    private static String chunkJson(String id, String model, long created, String delta, boolean done) {
        return "{"
                + "\"id\":" + SimpleJson.quote(id) + ","
                + "\"object\":\"chat.completion.chunk\","
                + "\"created\":" + created + ","
                + "\"model\":" + SimpleJson.quote(model) + ","
                + "\"choices\":[{\"index\":0,\"delta\":"
                + (done ? "{}" : "{\"content\":" + SimpleJson.quote(delta) + "}")
                + ",\"finish_reason\":" + (done ? "\"stop\"" : "null") + "}]"
                + "}";
    }

    private static String toolChunkJson(String id, String model, long created, ToolCallResult call, boolean done) {
        return "{"
                + "\"id\":" + SimpleJson.quote(id) + ","
                + "\"object\":\"chat.completion.chunk\","
                + "\"created\":" + created + ","
                + "\"model\":" + SimpleJson.quote(model) + ","
                + "\"choices\":[{\"index\":0,\"delta\":"
                + (done ? "{}" : "{\"tool_calls\":[" + toolCallJson(call, 0) + "]}")
                + ",\"finish_reason\":" + (done ? "\"tool_calls\"" : "null") + "}]"
                + "}";
    }

    private static String responseCreatedEvent(String id, String model, long created) {
        return "{"
                + "\"type\":\"response.created\","
                + "\"response\":{\"id\":" + SimpleJson.quote(id)
                + ",\"object\":\"response\",\"created_at\":" + created
                + ",\"status\":\"in_progress\",\"model\":" + SimpleJson.quote(model)
                + ",\"output\":[]}"
                + "}";
    }

    private static String responseTextDeltaEvent(String id, String delta) {
        return "{"
                + "\"type\":\"response.output_text.delta\","
                + "\"response_id\":" + SimpleJson.quote(id) + ","
                + "\"output_index\":0,"
                + "\"content_index\":0,"
                + "\"delta\":" + SimpleJson.quote(delta)
                + "}";
    }

    private static String responseCompletedEvent(String id, String model, long created, String text) {
        return "{"
                + "\"type\":\"response.completed\","
                + "\"response\":" + responseJsonWithId(id, model, created, text)
                + "}";
    }

    private static String responseJsonWithId(String id, String model, long created, String text) {
        String outputId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        return "{"
                + "\"id\":" + SimpleJson.quote(id) + ","
                + "\"object\":\"response\","
                + "\"created_at\":" + created + ","
                + "\"status\":\"completed\","
                + "\"model\":" + SimpleJson.quote(model) + ","
                + "\"output_text\":" + SimpleJson.quote(text) + ","
                + "\"output\":[{\"id\":" + SimpleJson.quote(outputId)
                + ",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":"
                + SimpleJson.quote(text) + ",\"annotations\":[]}]}],"
                + "\"usage\":{\"input_tokens\":0,\"output_tokens\":0,\"total_tokens\":0}"
                + "}";
    }

    private static String responseToolCallJson(ToolCallResult call) {
        return "{"
                + "\"id\":" + SimpleJson.quote("fc_" + UUID.randomUUID().toString().replace("-", "")) + ","
                + "\"type\":\"function_call\","
                + "\"status\":\"completed\","
                + "\"call_id\":" + SimpleJson.quote("call_" + UUID.randomUUID().toString().replace("-", "")) + ","
                + "\"name\":" + SimpleJson.quote(call.name()) + ","
                + "\"arguments\":" + SimpleJson.quote(call.argumentsJson())
                + "}";
    }

    private static String toolResponseSummary(ToolCallResult call) {
        return "function_call: " + call.name() + "(" + call.argumentsJson() + ")";
    }

    private static String toolCallJson(ToolCallResult call, int index) {
        return "{"
                + "\"index\":" + index + ","
                + "\"id\":" + SimpleJson.quote("call_" + UUID.randomUUID().toString().replace("-", "")) + ","
                + "\"type\":\"function\","
                + "\"function\":{\"name\":" + SimpleJson.quote(call.name())
                + ",\"arguments\":" + SimpleJson.quote(call.argumentsJson()) + "}"
                + "}";
    }

    private static void sendSse(OutputStream out, String json) throws IOException {
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static boolean isMethod(HttpExchange exchange, String method) {
        return method.equalsIgnoreCase(exchange.getRequestMethod());
    }

    private static boolean handleCorsPreflight(HttpExchange exchange) throws IOException {
        if (!isMethod(exchange, "OPTIONS")) {
            return false;
        }
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return true;
    }

    private static void addCorsHeaders(HttpExchange exchange, String methods) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", methods);
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers",
                "authorization, content-type, openai-beta, openai-organization, openai-project, x-requested-with, x-conversation-id, x-session-id, x-chat-id, x-thread-id");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "86400");
    }

    private static void sendMethodNotAllowed(HttpExchange exchange, String allowedMethods) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowedMethods);
        sendJson(exchange, 405, errorJson("此接口支持的方法：" + allowedMethods, "invalid_request_error"));
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        addCorsHeaders(exchange, "GET, POST, OPTIONS");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendText(HttpExchange exchange, int status, String text, String contentType) throws IOException {
        addCorsHeaders(exchange, "GET, HEAD, OPTIONS");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String errorJson(String message) {
        return errorJson(message, "server_error");
    }

    private static String errorJson(String message, String type) {
        return "{\"error\":{\"message\":" + SimpleJson.quote(message == null ? "未知错误" : message)
                + ",\"type\":" + SimpleJson.quote(type == null ? "server_error" : type) + "}}";
    }

    private static String stringValue(Object value, String fallback) {
        return value instanceof String text ? text : fallback;
    }

    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return SimpleJson.quote(text);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(SimpleJson.quote(String.valueOf(entry.getKey())))
                        .append(':')
                        .append(toJson(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(toJson(list.get(i)));
            }
            return out.append(']').toString();
        }
        return SimpleJson.quote(String.valueOf(value));
    }

    private String resolveModel(Object value) {
        String requested = stringValue(value, defaultModel);
        if (requested == null || requested.isBlank()) {
            requested = defaultModel;
        }
        for (String model : models) {
            if (model.equals(requested)) {
                return model;
            }
        }
        for (String model : models) {
            if (model.equalsIgnoreCase(requested)) {
                return model;
            }
        }
        throw new IllegalArgumentException("未知模型：" + requested + "。可用模型：" + String.join(", ", models));
    }

    @FunctionalInterface
    interface ChatBackend {
        String complete(ChatRequest request, Consumer<String> deltaSink) throws IOException;
    }

    record ChatRequest(String model, String sessionKey, boolean explicitSession, String fullPrompt,
                       String latestPrompt, int messageCount, List<ImageInput> images,
                       String toolsText, boolean newConversation) {
        boolean hasTools() {
            return toolsText != null && !toolsText.isBlank();
        }
    }

    private record SessionKey(String value, boolean explicit, String source, List<SessionCandidate> candidates) {
    }

    private record SessionCandidate(String source, String value) {
    }

    private record PromptData(String full, String latest, String firstUser, int messageCount, List<ImageInput> images) {
    }

    record ImageInput(String url, String mimeType, String filename) {
    }

    private record ContentParts(String text, List<ImageInput> images) {
    }

    private record InputMessage(String role, String text, List<ImageInput> images) {
    }

    private record ToolCallResult(String name, String argumentsJson) {
    }
}
