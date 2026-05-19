package com.sxxian.multiagentcreator.model.dto.skill;

import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
public class WritingSkillConfig implements Serializable {

    private String id;

    private String type;

    private String label;

    private String description;

    private Boolean enabled;

    private Integer sort;

    private String promptInstruction;

    private String reviewRubric;

    private String imageGuidance;

    private Map<String, String> wordRangeGuidance;

    private List<String> constraints;

    private static final long serialVersionUID = 1L;
}
