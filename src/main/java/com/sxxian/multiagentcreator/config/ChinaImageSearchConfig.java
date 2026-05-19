package com.sxxian.multiagentcreator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static com.sxxian.multiagentcreator.constant.ArticleConstant.BING_IMAGE_SEARCH_URL;

@Configuration
@ConfigurationProperties(prefix = "china-image-search")
@Data
public class ChinaImageSearchConfig {

    private String searchUrl = BING_IMAGE_SEARCH_URL;

    private String suffix = "官方 海报 剧照 角色图";

    private Integer timeout = 10000;

    private Integer maxCandidates = 8;
}
