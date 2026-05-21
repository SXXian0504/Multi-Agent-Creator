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
     * 发布平台：default/wechat_official/xiaohongshu/weibo，可为空
     */
    private String platform;

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
     * 可选值：PEXELS, QWEN_IMAGE, NANO_BANANA, GRAPHVIZ, MERMAID, ICONIFY, EMOJI_PACK, SVG_DIAGRAM
     */
    private List<String> enabledImageMethods;

    /**
     * 是否允许使用个人知识库增强。
     */
    private Boolean knowledgeEnhanced;

    /**
     * 是否优先使用写作风格记忆。
     */
    private Boolean useWritingStyleMemory;

    /**
     * 本次创作允许使用的知识库 ID 列表；为空时由规则服务在用户可用知识库中选择。
     */
    private List<Long> knowledgeBaseIds;

    private static final long serialVersionUID = 1L;
}
