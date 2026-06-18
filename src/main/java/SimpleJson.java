import java.util.LinkedHashMap;
import java.util.Map;

final class SimpleJson {
    private final String text;
    private int index;

    private SimpleJson(String text) {
        this.text = text == null ? "" : text;
    }

    static Object parse(String text) {
        return new SimpleJson(text).readValue();
    }

    static String quote(String value) {
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
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    private Object readValue() {
        skipWhitespace();
        if (index >= text.length()) {
            return null;
        }

        char c = text.charAt(index);
        if (c == '"') {
            return readString();
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
        return readNumber();
    }

    private String readString() {
        StringBuilder out = new StringBuilder();
        index++;
        while (index < text.length()) {
            char c = text.charAt(index++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\' || index >= text.length()) {
                out.append(c);
                continue;
            }

            char escaped = text.charAt(index++);
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
                    if (index + 4 <= text.length()) {
                        out.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                        index += 4;
                    }
                }
                default -> out.append(escaped);
            }
        }
        return out.toString();
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
            String key = String.valueOf(readValue());
            skipWhitespace();
            if (peek(':')) {
                index++;
            }
            Object value = readValue();
            values.put(key, value);
            skipWhitespace();
            if (peek(',')) {
                index++;
                continue;
            }
            if (peek('}')) {
                index++;
            }
            break;
        }
        return values;
    }

    private Object readNumber() {
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
