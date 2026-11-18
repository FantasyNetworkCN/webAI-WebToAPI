import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatGptAnswerStreamTest {
    @Test
    void dropsInternalMessagesAndTheirDeltasBeforeReturningFinalText() {
        assertEquals("Only the answer.", read(
                """
                {"v":{"message":{"author":{"role":"assistant"},"recipient":"api_tool.call_tool",
                  "content":{"content_type":"code","text":"{\\"path\\":\\"/files/search\\"}"},
                  "status":"finished_successfully"}}}
                """,
                """
                {"o":"patch","v":[{"p":"/message/content/text","o":"append","v":" internal request"}]}
                """,
                """
                {"v":[{"p":"/message/content/text","o":"append","v":" continued"}]}
                """,
                """
                {"v":{"message":{"author":{"role":"tool"},"recipient":"all",
                  "content":{"content_type":"text","parts":["private search results"]}}}}
                """,
                """
                {"v":{"message":{"author":{"role":"assistant"},"channel":"commentary","recipient":"all",
                  "content":{"content_type":"text","parts":["intermediate commentary"]}}}}
                """,
                """
                {"v":{"message":{"author":{"role":"assistant"},"channel":"final","recipient":"all",
                  "content":{"content_type":"text","parts":[""]}}}}
                """,
                """
                {"o":"patch","v":[{"p":"/message/content/parts/0","o":"append","v":"Only "}]}
                """,
                """
                {"v":[{"p":"/message/content/parts/0","o":"append","v":"the answer."}]}
                """));
    }

    @Test
    void keepsUserFacingJsonEvenWhenItLooksLikeAToolCall() {
        assertEquals("{\"path\":\"/files/search\"}", read("""
                {"v":{"message":{"author":{"role":"assistant"},"channel":"final","recipient":"all",
                  "content":{"content_type":"text","parts":["{\\"path\\":\\"/files/search\\"}"]}}}}
                """));
    }

    @Test
    void keepsLegacySnapshotsAndBareTextDeltas() {
        assertEquals("Hello  world", read(
                """
                {"message":{"author":{"role":"assistant"},"content":{"parts":["Hello"]}}}
                """,
                """
                {"v":" "}
                """,
                """
                {"v":" world"}
                """));
    }

    private static String read(String... frames) {
        var decoder = new Main.ChatGptDeltaDecoder();
        var answer = new Main.ChatGptAnswerStream();
        String text = "";
        for (String frame : frames) {
            text = answer.read(decoder.decode((Map<?, ?>) SimpleJson.parse(frame)), text);
        }
        return text;
    }
}
