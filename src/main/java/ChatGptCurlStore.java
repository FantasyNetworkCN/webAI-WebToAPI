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
import java.util.UUID;

/** Stores copied ChatGPT conversation curls without exposing their credentials in the UI. */
final class ChatGptCurlStore {
    private final Path databasePath;

    ChatGptCurlStore(Path databasePath) throws IOException {
        this.databasePath = databasePath;
        Path parent = databasePath.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    create table if not exists chatgpt_curls (
                        id text primary key,
                        curl text not null unique,
                        label text not null,
                        active integer not null default 1,
                        failure_count integer not null default 0,
                        last_error text not null default '',
                        last_used_at text not null default '',
                        created_at text not null,
                        updated_at text not null
                    )
                    """);
        } catch (SQLException e) {
            throw new IOException("初始化 ChatGPT curl SQLite 失败：" + e.getMessage(), e);
        }
    }

    synchronized CurlRecord addFromCurl(String raw) throws IOException {
        String curl = raw == null ? "" : raw.trim();
        if (curl.isBlank() || !curl.contains("chatgpt.com/backend-api/f/conversation")) {
            throw new IOException("不是 ChatGPT conversation curl；请粘贴包含 /backend-api/f/conversation 的完整 curl");
        }
        if (!curl.matches("(?s).*--data(?:-raw|-binary)?(?:=|\\s).+")) {
            throw new IOException("ChatGPT curl 缺少 JSON 请求体（--data-raw）");
        }
        String id = hash(curl);
        String now = Instant.now().toString();
        String label = "chatgpt-" + id.substring(0, 8);
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into chatgpt_curls
                     (id, curl, label, active, failure_count, last_error, last_used_at, created_at, updated_at)
                     values (?, ?, ?, 1, 0, '', '', ?, ?)
                     on conflict(curl) do update set active = 1, failure_count = 0, last_error = '', updated_at = excluded.updated_at
                     """)) {
            statement.setString(1, id);
            statement.setString(2, curl);
            statement.setString(3, label);
            statement.setString(4, now);
            statement.setString(5, now);
            statement.executeUpdate();
            return get(id);
        } catch (SQLException e) {
            throw new IOException("保存 ChatGPT curl 失败：" + e.getMessage(), e);
        }
    }

    synchronized List<CurlRecord> list() throws IOException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     select id, label, active, failure_count, last_error, last_used_at, created_at, updated_at, curl
                     from chatgpt_curls order by updated_at desc
                     """);
             ResultSet result = statement.executeQuery()) {
            ArrayList<CurlRecord> records = new ArrayList<>();
            while (result.next()) records.add(record(result));
            return List.copyOf(records);
        } catch (SQLException e) {
            throw new IOException("读取 ChatGPT curl 列表失败：" + e.getMessage(), e);
        }
    }

    synchronized List<CurlRecord> active() throws IOException {
        ArrayList<CurlRecord> records = new ArrayList<>();
        for (CurlRecord record : list()) if (record.active()) records.add(record);
        return List.copyOf(records);
    }

    synchronized void markSuccess(String id) { updateUsage(id, true, ""); }
    synchronized void markFailure(String id, String error) { updateUsage(id, false, error == null ? "" : error); }

    synchronized void delete(String id) throws IOException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("delete from chatgpt_curls where id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("删除 ChatGPT curl 失败：" + e.getMessage(), e);
        }
    }

    private CurlRecord get(String id) throws IOException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("select id, label, active, failure_count, last_error, last_used_at, created_at, updated_at, curl from chatgpt_curls where id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) return record(result);
            }
            throw new IOException("ChatGPT curl 保存后读取失败");
        } catch (SQLException e) {
            throw new IOException("读取 ChatGPT curl 失败：" + e.getMessage(), e);
        }
    }

    private CurlRecord record(ResultSet result) throws SQLException {
        String curl = result.getString("curl");
        return new CurlRecord(result.getString("id"), result.getString("label"), result.getInt("active") != 0,
                result.getInt("failure_count"), result.getString("last_error"), result.getString("last_used_at"),
                result.getString("created_at"), result.getString("updated_at"), curl == null ? 0 : curl.length(), curl);
    }

    private void updateUsage(String id, boolean success, String error) {
        String now = Instant.now().toString();
        String sql = success
                ? "update chatgpt_curls set failure_count = 0, last_error = '', last_used_at = ?, updated_at = ? where id = ?"
                : "update chatgpt_curls set failure_count = failure_count + 1, last_error = ?, last_used_at = ?, updated_at = ? where id = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (success) {
                statement.setString(1, now); statement.setString(2, now); statement.setString(3, id);
            } else {
                statement.setString(1, error.length() > 500 ? error.substring(0, 500) : error);
                statement.setString(2, now); statement.setString(3, now); statement.setString(4, id);
            }
            statement.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    private Connection connect() throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + databasePath); }

    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 16; i++) out.append(String.format("%02x", bytes[i]));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    record CurlRecord(String id, String label, boolean active, int failureCount, String lastError,
                      String lastUsedAt, String createdAt, String updatedAt, int curlLength, String curl) {
        String publicJson() {
            return "{\"id\":" + SimpleJson.quote(id) + ",\"label\":" + SimpleJson.quote(label)
                    + ",\"active\":" + active + ",\"failure_count\":" + failureCount
                    + ",\"last_error\":" + SimpleJson.quote(lastError == null ? "" : lastError)
                    + ",\"last_used_at\":" + SimpleJson.quote(lastUsedAt == null ? "" : lastUsedAt)
                    + ",\"created_at\":" + SimpleJson.quote(createdAt) + ",\"updated_at\":" + SimpleJson.quote(updatedAt)
                    + ",\"curl_length\":" + curlLength + "}";
        }
    }
}
