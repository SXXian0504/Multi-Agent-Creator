package com.sxxian.multiagentcreator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rag")
public class RagConfig {

    private boolean enabled = true;

    private int topK = 8;

    private int contextMaxChars = 4000;

    private String localStorageDir = "data/rag";

    private Datasource datasource = new Datasource();

    private Embedding embedding = new Embedding();

    @Data
    public static class Datasource {
        private String url = "jdbc:postgresql://localhost:5432/multi_agent_rag";
        private String username = "postgres";
        private String password = "postgres";
    }

    @Data
    public static class Embedding {
        private String endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";
        private String model = "text-embedding-v4";
        private int dimension = 1536;
        private boolean localFallbackEnabled = true;
    }
}
