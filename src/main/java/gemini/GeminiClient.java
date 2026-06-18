package gemini;

import config.Config;
import okhttp3.OkHttpClient;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;

public class GeminiClient {

    private final OkHttpClient client;

    public GeminiClient(Config config) {

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(300))
                .writeTimeout(Duration.ofSeconds(30));

        if (config.proxy.enabled) {

            Proxy.Type type =
                    "socks".equalsIgnoreCase(config.proxy.type)
                            ? Proxy.Type.SOCKS
                            : Proxy.Type.HTTP;

            builder.proxy(
                    new Proxy(
                            type,
                            new InetSocketAddress(
                                    config.proxy.host,
                                    config.proxy.port
                            )
                    )
            );
        }

        client = builder.build();
    }

    public OkHttpClient getClient() {
        return client;
    }
}