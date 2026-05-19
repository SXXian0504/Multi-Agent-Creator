package com.sxxian.multiagentcreator.service;

import com.sxxian.multiagentcreator.model.dto.skill.ResolvedWritingSkill;
import com.sxxian.multiagentcreator.model.dto.skill.WritingSkillOptions;

public interface SkillService {

    WritingSkillOptions listWritingSkills();

    boolean validatePlatform(String platform);

    boolean validateContentStyle(String contentStyle);

    ResolvedWritingSkill resolve(String platform, String contentStyle);

    String buildPromptInstruction(String platform, String contentStyle, String wordRange);
}
