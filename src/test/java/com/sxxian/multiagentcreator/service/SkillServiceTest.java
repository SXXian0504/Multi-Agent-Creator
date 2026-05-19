package com.sxxian.multiagentcreator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sxxian.multiagentcreator.model.dto.skill.ResolvedWritingSkill;
import com.sxxian.multiagentcreator.service.impl.SkillServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillServiceTest {

    private SkillService skillService;

    @BeforeEach
    void setUp() {
        SkillServiceImpl service = new SkillServiceImpl(new ObjectMapper());
        service.loadWritingSkills();
        skillService = service;
    }

    @Test
    void loadWritingSkillsShouldExposePlatformAndContentStyleOptions() {
        var options = skillService.listWritingSkills();

        assertTrue(options.getPlatforms().stream().anyMatch(skill -> "wechat_official".equals(skill.getId())));
        assertTrue(options.getPlatforms().stream().anyMatch(skill -> "xiaohongshu".equals(skill.getId())));
        assertTrue(options.getContentStyles().stream().anyMatch(skill -> "tech".equals(skill.getId())));
        assertTrue(options.getContentStyles().stream().anyMatch(skill -> "marketing".equals(skill.getId())));
    }

    @Test
    void validateShouldAllowBlankAndRejectUnknownValues() {
        assertTrue(skillService.validatePlatform(null));
        assertTrue(skillService.validatePlatform(""));
        assertTrue(skillService.validatePlatform("weibo"));
        assertFalse(skillService.validatePlatform("unknown_platform"));

        assertTrue(skillService.validateContentStyle(null));
        assertTrue(skillService.validateContentStyle(""));
        assertTrue(skillService.validateContentStyle("tech"));
        assertFalse(skillService.validateContentStyle("unknown_style"));
    }

    @Test
    void resolveShouldMergePlatformAndContentStyle() {
        ResolvedWritingSkill skill = skillService.resolve("xiaohongshu", "tech");

        assertTrue(skill.getDisplayName().contains("小红书"));
        assertTrue(skill.getDisplayName().contains("科技风格"));
        assertTrue(skill.getPromptInstruction().contains("小红书笔记"));
        assertTrue(skill.getPromptInstruction().contains("科技风格"));
        assertTrue(skill.getReviewRubric().contains("笔记感"));
        assertTrue(skill.getReviewRubric().contains("专业严谨"));
        assertTrue(skill.getImageGuidance().contains("小红书"));
        assertTrue(skill.getImageGuidance().contains("科技内容"));
    }

    @Test
    void buildPromptInstructionShouldUseDefaultPlatformAndWordRangeGuidance() {
        String prompt = skillService.buildPromptInstruction(null, "marketing", "short");

        assertTrue(prompt.contains("默认"));
        assertTrue(prompt.contains("商品营销"));
        assertTrue(prompt.contains("短文"));
        assertTrue(prompt.contains("用户补充要求优先级最高"));
    }
}
