package com.sxxian.multiagentcreator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "tencent.cos")
@Data
public class CosConfig {

    /**
     * Secret ID
     */
    private String secretId;

    /**
     * Secret Key
     */
    private String secretKey;

    /**
     * 地域
     */
    private String region;

    /**
     * 存储桶
     */
    private String bucket;
}
