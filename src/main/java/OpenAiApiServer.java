import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
        String explicit = firstNonBlank(
                stringValue(request.get("conversation_id"), ""),
                stringValue(request.get("session_id"), ""),
                stringValue(request.get("chat_id"), ""),
                stringValue(request.get("thread_id"), ""),
                stringValue(request.get("channel_id"), ""),
                stringValue(request.get("room_id"), ""),
                stringValue(request.get("dialogue_id"), ""),
                nestedString(request.get("metadata"), "conversation_id"),
                nestedString(request.get("metadata"), "session_id"),
                nestedString(request.get("metadata"), "chat_id"),
                nestedString(request.get("metadata"), "thread_id"),
                nestedString(request.get("metadata"), "channel_id"),
                nestedString(request.get("metadata"), "room_id"),
                nestedString(request.get("metadata"), "dialogue_id"),
                exchange.getRequestHeaders().getFirst("x-conversation-id"),
                exchange.getRequestHeaders().getFirst("x-session-id"),
                exchange.getRequestHeaders().getFirst("x-chat-id"),
                exchange.getRequestHeaders().getFirst("x-thread-id"),
                exchange.getRequestHeaders().getFirst("x-openwebui-chat-id"),
                exchange.getRequestHeaders().getFirst("x-astrbot-session-id"));
        if (explicit != null && !explicit.isBlank()) {
            return new SessionKey("explicit:" + cleanKey(explicit), true);
        }

        String origin = firstNonBlank(
                exchange.getRequestHeaders().getFirst("origin"),
                exchange.getRequestHeaders().getFirst("referer"),
                exchange.getRemoteAddress() == null ? "" : exchange.getRemoteAddress().getAddress().getHostAddress());
        String user = stringValue(request.get("user"), "");
        String seed = firstNonBlank(prompt.firstUser(), prompt.latest(), prompt.full());
        return new SessionKey("auto:" + shortHash(origin + "\n" + user + "\n" + seed), false);
    }

    private static boolean wantsNewConversation(Map<?, ?> request, PromptData prompt, SessionKey sessionKey) {
        return booleanValue(request.get("new_conversation"))
                || booleanValue(request.get("new"))
                || "/new".equalsIgnoreCase(prompt.latest().trim())
                || (!sessionKey.explicit() && prompt.messageCount() <= 1);
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

    private record SessionKey(String value, boolean explicit) {
    }

    private record PromptData(String full, String latest, String firstUser, int messageCount) {
    }

    private record ToolCallResult(String name, String argumentsJson) {
    }
}
