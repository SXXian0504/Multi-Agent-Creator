package com.sxxian.multiagentcreator.utils;

import com.sxxian.multiagentcreator.constant.PromptConstant;
import com.sxxian.multiagentcreator.model.enums.ArticleStyleEnum;

/**
 * 文章 Prompt 片段构建工具。
 */
public final class ArticlePromptUtils {

    private ArticlePromptUtils() {
    }

    public static String getOutlineWordRangePrompt(String wordRange, String style) {
        boolean marketing = ArticleStyleEnum.MARKETING.getValue().equals(style);
        String instruction = switch (wordRange == null ? "" : wordRange) {
            case "short" -> """
                    用户选择短文，目标正文约 200-500 字。大纲必须明显精简：
                    - 建议总章节 2-3 个，不要生成 5 个章节。
                    - 每章只保留 1-2 个核心要点，避免案例、背景、延展分析同时铺开。
                    - 如果是商品营销风格，优先用“痛点/场景 -> 核心卖点/信任支撑 -> 行动引导”的短转化结构。""";
            case "medium" -> """
                    用户选择中等篇幅，目标正文约 800-1500 字。大纲保持适中：
                    - 建议总章节 4-5 个。
                    - 每章 2-3 个要点，覆盖主线即可，避免拆出过细小节。
                    - 营销风格要保留转化路径，技术/教育风格可适当增加方法或案例章节。""";
            case "long" -> """
                    用户选择长文，目标正文约 2000-3500 字。大纲可以充分展开：
                    - 建议总章节 5-7 个。
                    - 每章 2-4 个要点，可以包含背景、方法、案例、对比、总结等层次。
                    - 章节之间要避免重复，保证每章承担不同论述任务。""";
            default -> marketing
                    ? """
                    用户未指定字数范围。当前是商品营销风格，默认采用偏短、转化导向的大纲：
                    - 建议总章节 2-4 个。
                    - 每章 1-2 个要点，围绕痛点、卖点、信任和行动引导组织。
                    - 不要按长篇文章自动扩展为 5 个以上章节。"""
                    : """
                    用户未指定字数范围。请根据标题和风格自行评估大纲篇幅：
                    - 轻量新媒体文章建议 3-4 个章节。
                    - 技术、教育等需要解释充分的主题可扩展到 4-6 个章节。
                    - 不要机械生成固定 5 个章节。""";
        };
        return PromptConstant.WORD_RANGE_PROMPT.replace("{wordRangeInstruction}", instruction);
    }
}
