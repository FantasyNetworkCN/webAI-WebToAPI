import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    private static final MediaType FORM_MEDIA_TYPE =
            MediaType.get("application/x-www-form-urlencoded;charset=UTF-8");
    private static final String DEFAULT_MODEL = "gemini-3.5-Flash";
    private static final Map<String, ModelProfile> MODEL_PROFILES = modelProfiles();

    public static void main(String[] args) {
        try {
            AppConfig config = AppConfig.load(Path.of("config.yml"));
            OkHttpClient client = buildHttpClient(config);

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

            OpenAiApiServer server = startApiServer(config, client);
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

    private static OpenAiApiServer startApiServer(AppConfig config, OkHttpClient client) throws IOException {
        if (!config.openAiEnabled) {
            System.out.println("OpenAI API 未启用。");
            return null;
        }

        OpenAiApiServer server = new OpenAiApiServer(
                config.openAiHost,
                config.openAiPort,
                modelNames(),
                DEFAULT_MODEL,
                new ApiChatBackend(config, client));
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
        sendInternal(config, client, model, prompt, conversation, System.out::print);
        System.out.println();
    }

    private static GeminiResult sendInternal(AppConfig config, OkHttpClient client, String model, String prompt,
                                             ConversationState conversation, Consumer<String> deltaSink) throws IOException {
        ModelProfile profile = modelProfile(model);
        CurlRequest curl = CurlRequest.parse(config.curl);
        curl.prepareForConversation(conversation);
        curl.applyModel(profile);
        if (!prompt.isBlank() && !prompt.equals(curl.originalPrompt())) {
            curl.replacePrompt(prompt);
        }

        try (Response response = client.newCall(curl.toRequest()).execute()) {
            if (response.body() == null) {
                throw new IOException("Gemini 返回了空响应");
            }
            if (!response.isSuccessful()) {
                String body = response.body().string();
                throw new IOException("Gemini 请求失败，HTTP 状态=" + response.code()
                        + "，响应=" + preview(body));
            }
            GeminiResult result = streamGeminiText(response, deltaSink);
            conversation.completeTurn(result);
            return result;
        }
    }

    private static Map<String, ModelProfile> modelProfiles() {
        Map<String, ModelProfile> profiles = new LinkedHashMap<>();
        profiles.put(DEFAULT_MODEL, new ModelProfile(DEFAULT_MODEL, null, null, null, null));
        profiles.put("gemini-3.1-Pro", new ModelProfile(
                "gemini-3.1-Pro",
                "9d8ca3786ebdfbea",
                3,
                "2cc5278d23ac6305f37b1e606442fb1e",
                3));
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
        Matcher bardError = Pattern.compile("BardErrorInfo\"\\s*,\\s*\\[(\\d+)]").matcher(body);
        if (bardError.find()) {
            throw new IOException("Gemini RPC 返回 BardErrorInfo，错误码=" + bardError.group(1)
                    + "。响应=" + preview(body));
        }
        throw new IOException("无法从 Gemini 响应里解析文本：" + preview(body));
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

    private static final class AppConfig {
        private boolean proxyEnabled;
        private String proxyType = "http";
        private String proxyHost = "127.0.0.1";
        private int proxyPort = 7890;
        private boolean openAiEnabled = true;
        private String openAiHost = "127.0.0.1";
        private int openAiPort = 8080;
        private String curl;

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
            config.curl = extractCurl(text);
            if (!hasCurlText(config.curl)) {
                throw new IOException("config.yml 里没有 curl 内容。把完整 StreamGenerate curl 粘到 curl: 后面。");
            }
            return config;
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
            request.cookie = required(request.cookie, "cookie");
            request.form = required(request.form, "form body");
            request.originalPrompt = readPromptFromForm(request.form);
            request.headers.put("Cookie", request.cookie);
            return request;
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
                    int end = findClosingQuote(normalized, i + 2, '\'');
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
                    int end = findClosingQuote(normalized, i + 1, '"');
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

    private static final class ApiChatBackend implements OpenAiApiServer.ChatBackend {
        private final AppConfig config;
        private final OkHttpClient client;
        private final Map<String, ConversationState> conversations = new ConcurrentHashMap<>();

        private ApiChatBackend(AppConfig config, OkHttpClient client) {
            this.config = config;
            this.client = client;
        }

        @Override
        public String complete(OpenAiApiServer.ChatRequest request, Consumer<String> deltaSink) throws IOException {
            String key = request.model() + ":" + request.sessionKey();
            ConversationState conversation = conversations.computeIfAbsent(key, ignored -> new ConversationState());
            synchronized (conversation) {
                boolean wasReset = false;
                if (request.newConversation()) {
                    conversation.clear();
                    wasReset = true;
                    if ("/new".equalsIgnoreCase(request.latestPrompt().trim())) {
                        return "已开启新对话。";
                    }
                }

                String prompt = conversation.isActive() && !wasReset ? request.latestPrompt() : request.fullPrompt();
                StringBuilder text = new StringBuilder();
                sendInternal(config, client, request.model(), prompt, conversation, delta -> {
                    text.append(delta);
                    if (deltaSink != null) {
                        deltaSink.accept(delta);
                    }
                });
                return text.toString();
            }
        }
    }

    private static final class ConversationState {
        private String conversationId = "";
        private String responseId = "";
        private String choiceId = "";
        private int completedTurns;

        private boolean isActive() {
            return !conversationId.isBlank() && !responseId.isBlank() && !choiceId.isBlank();
        }

        private void clear() {
            conversationId = "";
            responseId = "";
            choiceId = "";
            completedTurns = 0;
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

    private record ModelProfile(String name, String headerToken, Integer headerMode, String requestHash, Integer tailMode) {
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
