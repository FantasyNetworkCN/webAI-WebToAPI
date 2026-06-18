package config;

import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class ConfigLoader {

    public static Config load() throws Exception {

        File file = new File("config.yml");

        // 不存在则释放默认配置
        if (!file.exists()) {

            try (InputStream in =
                         ConfigLoader.class.getClassLoader()
                                 .getResourceAsStream("config.yml")) {

                if (in == null)
                    throw new RuntimeException("jar 内没有默认 config.yml");

                Files.copy(
                        in,
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

            System.out.println("已生成 config.yml，请修改后重新启动程序。");
            System.exit(0);
        }

        // 加载外部配置
        Yaml yaml = new Yaml();

        try (InputStream in = new FileInputStream(file)) {
            return yaml.loadAs(in, Config.class);
        }
    }

}