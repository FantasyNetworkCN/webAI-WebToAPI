import config.Config;
import config.ConfigLoader;
import gemini.GeminiClient;
import okhttp3.Request;
import okhttp3.Response;

public class Main {

    public static void main(String[] args) {

        try {

            Config config = ConfigLoader.load();

            GeminiClient geminiClient = new GeminiClient(config);

            Request request = new Request.Builder()
                    .url("https://gemini.google.com")
                    .header("Cookie", config.cookie)
                    .build();

            Response response = geminiClient
                    .getClient()
                    .newCall(request)
                    .execute();

            System.out.println("状态码：" + response.code());

            if (response.body() != null) {
                System.out.println(
                        response.body()
                                .string()
                                .substring(0, 500)
                );
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}