package com.sxxian.multiagentcreator.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sxxian.multiagentcreator.model.dto.skill.ResolvedWritingSkill;
import com.sxxian.multiagentcreator.model.dto.skill.WritingSkillConfig;
import com.sxxian.multiagentcreator.model.dto.skill.WritingSkillOptions;
import com.sxxian.multiagentcreator.service.SkillService;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
@RequiredArgsConstructor
public class SkillServiceImpl implements SkillService {

    private static final String DEFAULT_PLATFORM = "default";
    private static final String AUTO_WORD_RANGE = "auto";

    private final ObjectMapper objectMapper;

    private Map<String, WritingSkillConfig> platforms = new LinkedHashMap<>();
    private Map<String, WritingSkillConfig> contentStyles = new LinkedHashMap<>();

    @PostConstruct
    public void loadWritingSkills() {
        try (InputStream inputStream = new ClassPathResource("skills/writing-skills.json").getInputStream()) {
            WritingSkillFile file = objectMapper.readValue(inputStream, WritingSkillFile.class);
            platforms = index(file.getPlatforms());
            contentStyles = index(file.getContentStyles());
            if (!platforms.containsKey(DEFAULT_PLATFORM)) {
                throw new IllegalStateException("writing skills missing default platform");
            }
            log.info("Writing skills loaded, platforms={}, contentStyles={}", platforms.size(), contentStyles.size());
        } catch (Exception e) {
            throw new IllegalStateException("failed to load writing skills", e);
        }
    }

    @Override
    public WritingSkillOptions listWritingSkills() {
        WritingSkillOptions options = new WritingSkillOptions();
        options.setPlatforms(sortedEnabled(platforms));
        options.setContentStyles(sortedEnabled(contentStyles));
        return options;
    }

    @Override
    public boolean validatePlatform(String platform) {
        return platform == null || platform.isBlank() || platforms.containsKey(platform);
    }

    @Override
    public boolean validateContentStyle(String contentStyle) {
        return contentStyle == null || contentStyle.isBlank() || contentStyles.containsKey(contentStyle);
    }

    @Override
    public ResolvedWritingSkill resolve(String platform, String contentStyle) {
        WritingSkillConfig platformConfig = platforms.get(normalizePlatform(platform));
        WritingSkillConfig styleConfig = isBlank(contentStyle) ? null : contentStyles.get(contentStyle);
        if (platformConfig == null) {
            platformConfig = platforms.get(DEFAULT_PLATFORM);
        }

        ResolvedWritingSkill resolved = new ResolvedWritingSkill();
        resolved.setPlatformId(platformConfig.getId());
        resolved.setPlatformLabel(platformConfig.getLabel());
        resolved.setContentStyleId(styleConfig != null ? styleConfig.getId() : "");
        resolved.setContentStyleLabel(styleConfig != null ? styleConfig.getLabel() : "默认内容风格");
        resolved.setDisplayName(platformConfig.getLabel() + " + " + resolved.getContentStyleLabel());
        resolved.setPromptInstruction(buildPromptBlock(platformConfig, styleConfig));
        resolved.setReviewRubric(buildReviewRubric(platformConfig, styleConfig));
        resolved.setImageGuidance(buildImageGuidance(platformConfig, styleConfig));
        resolved.setWordRangeGuidance("");
        resolved.setConstraints(mergeConstraints(platformConfig, styleConfig));
        return resolved;
    }

    @Override
    public String buildPromptInstruction(String platform, String contentStyle, String wordRange) {
        ResolvedWritingSkill resolved = resolve(platform, contentStyle);
        StringBuilder sb = new StringBuilder("\n\n**写作 Skill 配置**\n");
        sb.append("当前 Skill：").append(resolved.getDisplayName()).append("\n");
        sb.append(resolved.getPromptInstruction()).append("\n");
        String wordRangeGuidance = resolveWordRangeGuidance(platform, contentStyle, wordRange);
        if (!wordRangeGuidance.isBlank()) {
            sb.append("\n**篇幅与结构要求**\n").append(wordRangeGuidance).append("\n");
        }
        if (resolved.getConstraints() != null && !resolved.getConstraints().isEmpty()) {
            sb.append("\n**约束**\n");
            for (String constraint : resolved.getConstraints()) {
                sb.append("- ").append(constraint).append("\n");
            }
        }
        sb.append("\n合并规则：用户补充要求优先级最高；平台 Skill 决定格式、篇幅节奏、标题和互动方式；内容风格 Skill 决定语气、论证和表达方式；冲突时平台格式优先，内容风格语气保留。\n");
        return sb.toString();
    }

    private String resolveWordRangeGuidance(String platform, String contentStyle, String wordRange) {
        String key = isBlank(wordRange) ? AUTO_WORD_RANGE : wordRange;
        WritingSkillConfig platformConfig = platforms.get(normalizePlatform(platform));
        WritingSkillConfig styleConfig = isBlank(contentStyle) ? null : contentStyles.get(contentStyle);
        List<String> parts = new ArrayList<>();
        String platformGuidance = wordGuidance(platformConfig, key);
        if (!platformGuidance.isBlank()) {
            parts.add("平台：" + platformGuidance);
        }
        String styleGuidance = wordGuidance(styleConfig, key);
        if (!styleGuidance.isBlank()) {
            parts.add("内容风格：" + styleGuidance);
        }
        return String.join("\n", parts);
    }

    private Map<String, WritingSkillConfig> index(List<WritingSkillConfig> configs) {
        Map<String, WritingSkillConfig> result = new LinkedHashMap<>();
        if (configs == null) {
            return result;
        }
        configs.stream()
                .filter(config -> config.getId() != null && Boolean.TRUE.equals(config.getEnabled()))
                .sorted(Comparator.comparing(config -> Objects.requireNonNullElse(config.getSort(), 0)))
                .forEach(config -> result.put(config.getId(), config));
        return result;
    }

    private List<WritingSkillConfig> sortedEnabled(Map<String, WritingSkillConfig> source) {
        return source.values().stream()
                .filter(config -> Boolean.TRUE.equals(config.getEnabled()))
                .sorted(Comparator.comparing(config -> Objects.requireNonNullElse(config.getSort(), 0)))
                .toList();
    }

    private String normalizePlatform(String platform) {
        return isBlank(platform) ? DEFAULT_PLATFORM : platform;
    }

    private String buildPromptBlock(WritingSkillConfig platform, WritingSkillConfig style) {
        StringBuilder sb = new StringBuilder();
        sb.append("平台要求：").append(nullToEmpty(platform.getPromptInstruction())).append("\n");
        if (style != null) {
            sb.append("内容风格要求：").append(nullToEmpty(style.getPromptInstruction())).append("\n");
        }
        return sb.toString();
    }

    private String buildReviewRubric(WritingSkillConfig platform, WritingSkillConfig style) {
        StringBuilder sb = new StringBuilder();
        sb.append("平台匹配：\n").append(nullToEmpty(platform.getReviewRubric())).append("\n");
        if (style != null) {
            sb.append("内容风格匹配：\n").append(nullToEmpty(style.getReviewRubric())).append("\n");
        }
        return sb.toString();
    }

    private String buildImageGuidance(WritingSkillConfig platform, WritingSkillConfig style) {
        StringBuilder sb = new StringBuilder();
        sb.append("平台图片建议：").append(nullToEmpty(platform.getImageGuidance())).append("\n");
        if (style != null) {
            sb.append("内容风格图片建议：").append(nullToEmpty(style.getImageGuidance())).append("\n");
        }
        return sb.toString();
    }

    private List<String> mergeConstraints(WritingSkillConfig platform, WritingSkillConfig style) {
        List<String> constraints = new ArrayList<>();
        if (platform != null && platform.getConstraints() != null) {
            constraints.addAll(platform.getConstraints());
        }
        if (style != null && style.getConstraints() != null) {
            constraints.addAll(style.getConstraints());
        }
        return constraints;
    }

    private String wordGuidance(WritingSkillConfig config, String key) {
        if (config == null || config.getWordRangeGuidance() == null) {
            return "";
        }
        return nullToEmpty(config.getWordRangeGuidance().getOrDefault(key, config.getWordRangeGuidance().get(AUTO_WORD_RANGE)));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Data
    private static class WritingSkillFile {
        private List<WritingSkillConfig> platforms;
        private List<WritingSkillConfig> contentStyles;
    }
}
