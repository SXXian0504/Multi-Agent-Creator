package com.sxxian.multiagentcreator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "iconify")
@Data
public class IconifyConfig {

    /**
     * Iconify API 地址
     */
    private String apiUrl = "https://api.iconify.design";

    /**
     * 搜索结果限制数量
     */
    private Integer searchLimit = 10;

    /**
     * 默认图标高度（像素）
     */
    private Integer defaultHeight = 64;

    /**
     * 默认图标颜色（留空使用 currentColor，或设置如 "#000000"）
     */
    private String defaultColor = "";
}
