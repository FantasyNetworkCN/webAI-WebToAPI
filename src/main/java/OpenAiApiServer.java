import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
            ChatRequest chatRequest = new ChatRequest(
                    model,
                    sessionKey(request, exchange),
                    prompt.full(),
                    prompt.latest(),
                    wantsNewConversation(request, prompt));
            if (stream) {
                handleStream(exchange, chatRequest);
                return;
            }

            String text = backend.complete(chatRequest, null);
            sendJson(exchange, 200, completionJson(model, text));
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
            return new PromptData("", "");
        }

        StringBuilder full = new StringBuilder();
        String latest = "";
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> message)) {
                continue;
            }
            String role = stringValue(message.get("role"), "user");
            String content = contentText(message.get("content"));
            if (content.isBlank()) {
                continue;
            }
            if (!full.isEmpty()) {
                full.append('\n');
            }
            full.append(role).append(": ").append(content);
            if ("user".equalsIgnoreCase(role)) {
                latest = content;
            } else if (latest.isBlank()) {
                latest = content;
            }
        }
        return new PromptData(full.toString(), latest);
    }

    private static String sessionKey(Map<?, ?> request, HttpExchange exchange) {
        String value = firstNonBlank(
                stringValue(request.get("conversation_id"), ""),
                stringValue(request.get("session_id"), ""),
                stringValue(request.get("user"), ""),
                exchange.getRequestHeaders().getFirst("x-conversation-id"),
                exchange.getRequestHeaders().getFirst("x-session-id"));
        return value == null || value.isBlank() ? "default" : value.replace('\0', '_').trim();
    }

    private static boolean wantsNewConversation(Map<?, ?> request, PromptData prompt) {
        return booleanValue(request.get("new_conversation"))
                || booleanValue(request.get("new"))
                || "/new".equalsIgnoreCase(prompt.latest().trim());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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

    record ChatRequest(String model, String sessionKey, String fullPrompt, String latestPrompt,
                       boolean newConversation) {
    }

    private record PromptData(String full, String latest) {
    }
}
