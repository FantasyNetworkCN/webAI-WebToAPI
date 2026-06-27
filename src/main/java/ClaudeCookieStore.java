import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ClaudeCookieStore {
    private static final Pattern ORG_PATTERN = Pattern.compile("(?:^|;\\s*)lastActiveOrg=([^;]+)");
    private static final Pattern SESSION_PATTERN = Pattern.compile("(?:^|;\\s*)sessionKey=([^;]+)");
    private final Path databasePath;

    ClaudeCookieStore(Path databasePath) throws IOException {
        this.databasePath = databasePath;
        Path parent = databasePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        init();
    }

    synchronized void init() throws IOException {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    create table if not exists claude_cookies (
                        id text primary key,
	                        cookie text not null unique,
	                        org_id text not null,
	                        label text not null,
	                        active integer not null default 1,
	                        failure_count integer not null default 0,
	                        last_error text not null default '',
	                        last_used_at text not null default '',
	                        disabled_until text not null default '',
	                        created_at text not null,
	                        updated_at text not null
	                    )
	                    """);
            addColumnIfMissing(statement, "claude_cookies", "disabled_until", "text not null default ''");
        } catch (SQLException e) {
            throw new IOException("初始化 Claude cookie SQLite 失败：" + e.getMessage(), e);
        }
    }

    synchronized CookieRecord addFromCurl(String raw) throws IOException {
        String cookie = extractCookie(raw);
        if (cookie.isBlank()) {
            throw new IOException("没有从输入中解析到 cookie；请粘贴包含 -b/--cookie 的 Claude curl，或直接粘贴 cookie");
        }
        String orgId = firstNonBlank(cookieValue(cookie, ORG_PATTERN), "33cf6031-0e58-4371-befe-f1925e92f424");
        String id = shortHash(cookie);
        String now = Instant.now().toString();
        String label = "claude-" + id.substring(0, Math.min(8, id.length()));
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into claude_cookies
                     (id, cookie, org_id, label, active, failure_count, last_error, last_used_at, disabled_until, created_at, updated_at)
                     values (?, ?, ?, ?, 1, 0, '', '', '', ?, ?)
                     on conflict(cookie) do update set
                         org_id = excluded.org_id,
                         active = 1,
                         failure_count = 0,
                         last_error = '',
                         disabled_until = '',
                         updated_at = excluded.updated_at
                     """)) {
            statement.setString(1, id);
            statement.setString(2, cookie);
            statement.setString(3, orgId);
            statement.setString(4, label);
            statement.setString(5, now);
            statement.setString(6, now);
            statement.executeUpdate();
            return get(id);
        } catch (SQLException e) {
            throw new IOException("保存 Claude cookie 失败：" + e.getMessage(), e);
        }
    }

    synchronized List<CookieRecord> list() throws IOException {
        clearExpiredDisables();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     select id, org_id, label, active, failure_count, last_error, last_used_at, disabled_until, created_at, updated_at, cookie
                     from claude_cookies
                     order by updated_at desc
                     """);
             ResultSet result = statement.executeQuery()) {
            ArrayList<CookieRecord> out = new ArrayList<>();
            while (result.next()) {
                out.add(record(result));
            }
            return List.copyOf(out);
        } catch (SQLException e) {
            throw new IOException("读取 Claude cookie 列表失败：" + e.getMessage(), e);
        }
    }

    synchronized List<CookieRecord> activeShuffled() throws IOException {
        ArrayList<CookieRecord> records = new ArrayList<>();
        for (CookieRecord record : list()) {
            if (record.active() && !record.isTemporarilyDisabled()) {
                records.add(record);
            }
        }
        for (int i = records.size() - 1; i > 0; i--) {
            int j = ThreadLocalRandom.current().nextInt(i + 1);
            CookieRecord tmp = records.get(i);
            records.set(i, records.get(j));
            records.set(j, tmp);
        }
        return List.copyOf(records);
    }

    synchronized void markSuccess(String id) {
        updateUsage(id, true, "");
    }

    synchronized void markFailure(String id, String error) {
        updateUsage(id, false, error == null ? "" : error);
    }

    synchronized void disableUntil(String id, Instant until, String error) {
        if (until == null || !until.isAfter(Instant.now())) {
            markFailure(id, error);
            return;
        }
        String now = Instant.now().toString();
        String safeError = error == null ? "" : error;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     update claude_cookies
                     set failure_count = failure_count + 1,
                         last_error = ?,
                         last_used_at = ?,
                         disabled_until = ?,
                         updated_at = ?
                     where id = ?
                     """)) {
            statement.setString(1, safeError.length() > 500 ? safeError.substring(0, 500) : safeError);
            statement.setString(2, now);
            statement.setString(3, until.toString());
            statement.setString(4, now);
            statement.setString(5, id);
            statement.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    synchronized void delete(String id) throws IOException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("delete from claude_cookies where id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("删除 Claude cookie 失败：" + e.getMessage(), e);
        }
    }

    private void updateUsage(String id, boolean success, String error) {
        String now = Instant.now().toString();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(success
                     ? "update claude_cookies set failure_count = 0, last_error = '', last_used_at = ?, disabled_until = '', updated_at = ? where id = ?"
                     : "update claude_cookies set failure_count = failure_count + 1, last_error = ?, last_used_at = ?, updated_at = ? where id = ?")) {
            if (success) {
                statement.setString(1, now);
                statement.setString(2, now);
                statement.setString(3, id);
            } else {
                statement.setString(1, error.length() > 500 ? error.substring(0, 500) : error);
                statement.setString(2, now);
                statement.setString(3, now);
                statement.setString(4, id);
            }
            statement.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    private CookieRecord get(String id) throws IOException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     select id, org_id, label, active, failure_count, last_error, last_used_at, disabled_until, created_at, updated_at, cookie
                     from claude_cookies where id = ?
                     """)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return record(result);
                }
            }
            throw new IOException("Claude cookie 保存后读取失败");
        } catch (SQLException e) {
            throw new IOException("读取 Claude cookie 失败：" + e.getMessage(), e);
        }
    }

    private CookieRecord record(ResultSet result) throws SQLException {
        String cookie = result.getString("cookie");
        return new CookieRecord(
                result.getString("id"),
                result.getString("org_id"),
                result.getString("label"),
                result.getInt("active") != 0,
                result.getInt("failure_count"),
                result.getString("last_error"),
                result.getString("last_used_at"),
                result.getString("disabled_until"),
                result.getString("created_at"),
                result.getString("updated_at"),
                cookie == null ? 0 : cookie.length(),
                !cookieValue(cookie, SESSION_PATTERN).isBlank(),
                cookie
        );
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }

    private static void addColumnIfMissing(Statement statement, String table, String column, String definition) throws SQLException {
        try {
            statement.executeUpdate("alter table " + table + " add column " + column + " " + definition);
        } catch (SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (!message.contains("duplicate column name")) {
                throw e;
            }
        }
    }

    private void clearExpiredDisables() {
        String now = Instant.now().toString();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     update claude_cookies
                     set disabled_until = '', updated_at = ?
                     where disabled_until <> '' and disabled_until <= ?
                     """)) {
            statement.setString(1, now);
            statement.setString(2, now);
            statement.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    private static String extractCookie(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isBlank()) {
            return "";
        }
        List<String> tokens = shellTokens(text);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (("-b".equals(token) || "--cookie".equals(token)) && i + 1 < tokens.size()) {
                return tokens.get(i + 1).trim();
            }
            if (token.startsWith("--cookie=")) {
                return token.substring("--cookie=".length()).trim();
            }
        }
        return text.startsWith("curl ") ? "" : text;
    }

    private static String cookieValue(String cookie, Pattern pattern) {
        Matcher matcher = pattern.matcher(cookie == null ? "" : cookie);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 16 && i < hash.length; i++) {
                out.append(String.format("%02x", hash[i]));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    private static List<String> shellTokens(String curl) {
        String normalized = curl.replaceAll("\\\\\\R", " ");
        ArrayList<String> tokens = new ArrayList<>();
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
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    record CookieRecord(String id, String orgId, String label, boolean active, int failureCount,
                        String lastError, String lastUsedAt, String disabledUntil, String createdAt, String updatedAt,
                        int cookieLength, boolean hasSessionKey, String cookie) {
        boolean isTemporarilyDisabled() {
            if (disabledUntil == null || disabledUntil.isBlank()) {
                return false;
            }
            try {
                return Instant.parse(disabledUntil).isAfter(Instant.now());
            } catch (Exception ignored) {
                return false;
            }
        }

        String publicJson() {
            return "{"
                    + "\"id\":" + SimpleJson.quote(id) + ","
                    + "\"org_id\":" + SimpleJson.quote(orgId) + ","
                    + "\"label\":" + SimpleJson.quote(label) + ","
                    + "\"active\":" + active + ","
                    + "\"failure_count\":" + failureCount + ","
                    + "\"last_error\":" + SimpleJson.quote(lastError == null ? "" : lastError) + ","
                    + "\"last_used_at\":" + SimpleJson.quote(lastUsedAt == null ? "" : lastUsedAt) + ","
                    + "\"disabled_until\":" + SimpleJson.quote(disabledUntil == null ? "" : disabledUntil) + ","
                    + "\"temporarily_disabled\":" + isTemporarilyDisabled() + ","
                    + "\"created_at\":" + SimpleJson.quote(createdAt == null ? "" : createdAt) + ","
                    + "\"updated_at\":" + SimpleJson.quote(updatedAt == null ? "" : updatedAt) + ","
                    + "\"cookie_length\":" + cookieLength + ","
                    + "\"has_session_key\":" + hasSessionKey
                    + "}";
        }
    }
}
