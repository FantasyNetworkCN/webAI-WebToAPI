import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Solves chat.deepseek.com's one-time proof-of-work challenge and builds the
 * response header the site sends back with sensitive requests.
 *
 * <p>The server hands out {@code {salt, challenge, difficulty, signature,
 * expire_at, target_path}}. The client searches for the smallest integer
 * {@code answer} in {@code [0, difficulty)} such that
 * {@code DeepSeekHashV1(salt + "_" + expire_at + "_" + answer)} equals the
 * challenge. That base64 JSON blob then travels in {@code X-DS-PoW-Response}.
 */
final class DeepSeekPow {
    private DeepSeekPow() {
    }

    /** Smallest {@code answer} whose hash matches, or throws when none exists in range. */
    static long solve(String challengeHex, String prefix, long difficulty) throws IOException {
        for (long answer = 0; answer < difficulty; answer++) {
            if (DeepSeekHash.hex(prefix + answer).equals(challengeHex)) {
                return answer;
            }
        }
        throw new IOException("DeepSeek PoW 求解失败：在 [0," + difficulty + ") 内没有满足条件的 answer");
    }

    /** Builds the value for the {@code X-DS-PoW-Response} header (authenticated users). */
    static String responseHeader(String algorithm, String challenge, String salt, long answer,
                                 String signature, String targetPath) {
        String json = "{"
                + "\"algorithm\":" + SimpleJson.quote(algorithm) + ","
                + "\"challenge\":" + SimpleJson.quote(challenge) + ","
                + "\"salt\":" + SimpleJson.quote(salt) + ","
                + "\"answer\":" + answer + ","
                + "\"signature\":" + SimpleJson.quote(signature) + ","
                + "\"target_path\":" + SimpleJson.quote(targetPath)
                + "}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Builds the value for the {@code X-DS-Guest-PoW-Response} header (guests). */
    static String guestResponseHeader(String salt, long answer) {
        String json = "{\"salt\":" + SimpleJson.quote(salt) + ",\"answer\":" + answer + "}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
