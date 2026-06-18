package config;

public class Config {

    public ProxyConfig proxy;
    public String cookie;

    public static class ProxyConfig {
        public boolean enabled;
        public String type;
        public String host;
        public int port;
    }
}