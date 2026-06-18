import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
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
        server.createContext("/v1/chat/completions", this::handleChatCompletions);
        server.createContext("/v1/models", this::handleModels);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        System.out.println("OpenAI API 已启动：http://" + host + ":" + port);
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
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":{\"message\":\"只支持 GET\"}}");
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

    private void handleChatCompletions(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":{\"message\":\"只支持 POST\"}}");
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
            if (prompt.latest().isBlank()) {
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

    private void handleStream(HttpExchange exchange, ChatRequest request) throws IOException {
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

    private static PromptData promptFromMessages(Object messages) {
        if (!(messages instanceof List<?> list)) {
            return new PromptData("", "", "", 0);
        }

        StringBuilder full = new StringBuilder();
        String firstUser = "";
        String latest = "";
        int messageCount = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> message)) {
                continue;
            }
            String role = stringValue(message.get("role"), "user");
            String content = contentText(message.get("content"));
            if (content.isBlank() && "assistant".equalsIgnoreCase(role)) {
                content = toolCallsText(message.get("tool_calls"));
            }
            if (content.isBlank()) {
                continue;
            }
            String label = roleLabel(role, message);
            if (!full.isEmpty()) {
                full.append('\n');
            }
            full.append(label).append(": ").append(content);
            messageCount++;
            if ("user".equalsIgnoreCase(role)) {
                if (firstUser.isBlank()) {
                    firstUser = content;
                }
                latest = content;
            } else if (latest.isBlank()) {
                latest = content;
            }
        }
        return new PromptData(full.toString(), latest, firstUser.isBlank() ? latest : firstUser, messageCount);
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

    private static String contentText(Object content) {
        if (content instanceof String text) {
            return text;
        }
        if (!(content instanceof List<?> parts)) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        for (Object part : parts) {
            if (part instanceof Map<?, ?> map) {
                String type = stringValue(map.get("type"), "");
                if ("text".equals(type)) {
                    if (!out.isEmpty()) {
                        out.append('\n');
                    }
                    out.append(stringValue(map.get("text"), ""));
                }
            }
        }
        return out.toString();
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

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
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
                       String latestPrompt, int messageCount, String toolsText, boolean newConversation) {
        boolean hasTools() {
            return toolsText != null && !toolsText.isBlank();
        }
    }

    private record SessionKey(String value, boolean explicit, String source, List<SessionCandidate> candidates) {
    }

    private record SessionCandidate(String source, String value) {
    }

    private record PromptData(String full, String latest, String firstUser, int messageCount) {
    }

    private record ToolCallResult(String name, String argumentsJson) {
    }
}
