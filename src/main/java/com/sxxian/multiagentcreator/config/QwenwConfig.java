package com.sxxian.multiagentcreator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 千问文生图配置
 */
@Configuration
@ConfigurationProperties(prefix = "qwen.image")
@Data
public class QwenwConfig {

    /**
     * DashScope API Key
     */
    private String apiKey;

    /**
     * 文生图模型
     */
    private String model = "qwen-image-plus";

    /**
     * 图片宽度
     */
    private String width = "1024";

    /**
     * 图片高度
     */
    private String height = "1024";

    /**
     * 生成图片数量
     */
    private Integer n = 1;

    /**
     * 图片质量 (standard, high, premium)
     */
    private String quality = "standard";

    /**
     * 图片风格
     */
    private String style = "<auto>";

    /**
     * 超时时间（毫秒）
     */
    private Long timeout = 60000L;

    /**
     * 轮询间隔（毫秒）
     */
    private Long pollInterval = 5000L;

    /**
     * 获取图片尺寸格式
     */
    public String getSize() {
        return width + "*" + height;
    }
}
