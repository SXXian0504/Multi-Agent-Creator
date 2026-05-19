package com.sxxian.multiagentcreator.model.dto.skill;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class WritingSkillOptions implements Serializable {

    private List<WritingSkillConfig> platforms;

    private List<WritingSkillConfig> contentStyles;

    private static final long serialVersionUID = 1L;
}
