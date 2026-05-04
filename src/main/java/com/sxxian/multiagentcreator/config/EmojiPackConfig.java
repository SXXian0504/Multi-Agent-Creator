package com.sxxian.multiagentcreator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static com.sxxian.multiagentcreator.constant.ArticleConstant.BING_IMAGE_SEARCH_URL;
import static com.sxxian.multiagentcreator.constant.ArticleConstant.EMOJI_PACK_SUFFIX;

@Configuration
@ConfigurationProperties(prefix = "emoji-pack")
@Data
public class EmojiPackConfig {

    /**
     * Bing 图片搜索地址
     */
    private String searchUrl = BING_IMAGE_SEARCH_URL;

    /**
     * 表情包关键词后缀（程序固定拼接，不依赖 AI 返回）
     */
    private String suffix = EMOJI_PACK_SUFFIX;

    /**
     * 请求超时时间（毫秒）
     */
    private Integer timeout = 10000;
}