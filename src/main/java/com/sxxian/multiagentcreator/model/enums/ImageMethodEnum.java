package com.sxxian.multiagentcreator.model.enums;

import lombok.Getter;

/**
 * Image source methods.
 */
@Getter
public enum ImageMethodEnum {

    /**
     * Pexels stock image search.
     */
    PEXELS("PEXELS", "Pexels 图库", false, false),

    /**
     * Domestic web image search for Chinese entities, IP, films and hot topics.
     */
    CHINA_IMAGE_SEARCH("CHINA_IMAGE_SEARCH", "国内图库检索", false, false),

    /**
     * Nano Banana AI image generation.
     */
    NANO_BANANA("NANO_BANANA", "Nano Banana AI 生图", true, false),

    /**
     * Mermaid flowchart generation.
     */
    MERMAID("MERMAID", "Mermaid 流程图生成", true, false),

    /**
     * Graphviz DOT flowchart generation.
     */
    GRAPHVIZ("GRAPHVIZ", "Graphviz DOT 流程图生成", true, false),

    /**
     * Iconify icon search.
     */
    ICONIFY("ICONIFY", "Iconify 图标库", false, false),

    /**
     * Meme image search.
     */
    EMOJI_PACK("EMOJI_PACK", "表情包检索", false, false),

    /**
     * SVG concept diagram generation.
     */
    SVG_DIAGRAM("SVG_DIAGRAM", "SVG 概念示意图", true, false),

    /**
     * Qwen text-to-image generation.
     */
    QWEN_IMAGE("QWEN_IMAGE", "百炼文生图（wanx-v1）", true, false),

    /**
     * Picsum fallback image.
     */
    PICSUM("PICSUM", "Picsum 随机图片", false, true);

    private final String value;
    private final String description;
    private final boolean aiGenerated;
    private final boolean fallback;

    ImageMethodEnum(String value, String description, boolean aiGenerated, boolean fallback) {
        this.value = value;
        this.description = description;
        this.aiGenerated = aiGenerated;
        this.fallback = fallback;
    }

    public static ImageMethodEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (ImageMethodEnum methodEnum : values()) {
            if (methodEnum.getValue().equals(value)) {
                return methodEnum;
            }
        }
        return null;
    }

    public static ImageMethodEnum getDefaultSearchMethod() {
        return CHINA_IMAGE_SEARCH;
    }

    public static ImageMethodEnum getDefaultAiMethod() {
        return QWEN_IMAGE;
    }

    public static ImageMethodEnum getFallbackMethod() {
        return PICSUM;
    }
}
