package com.sxxian.multiagentcreator.model.dto.article;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 创建文章请求
 */
@Data
public class ArticleCreateRequest implements Serializable {

    /**
     * 选题
     */
    private String topic;

    /**
     * 文章风格：tech/marketing/emotional/educational/humorous，可为空
     */
    private String style;

    /**
     * 字数范围：short/medium/long，可为空，为空时由 Agent 根据风格和主题自行评估
     */
    private String wordRange;

    /**
     * 允许的配图方式列表（为空或 null 表示支持所有方式）
     * 可选值：PEXELS, NANO_BANANA, MERMAID, ICONIFY, EMOJI_PACK, SVG_DIAGRAM
     */
    private List<String> enabledImageMethods;

    private static final long serialVersionUID = 1L;
}
