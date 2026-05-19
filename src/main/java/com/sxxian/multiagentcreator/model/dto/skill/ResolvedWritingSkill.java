package com.sxxian.multiagentcreator.model.dto.skill;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ResolvedWritingSkill implements Serializable {

    private String platformId;

    private String platformLabel;

    private String contentStyleId;

    private String contentStyleLabel;

    private String displayName;

    private String promptInstruction;

    private String reviewRubric;

    private String imageGuidance;

    private String wordRangeGuidance;

    private List<String> constraints;

    private static final long serialVersionUID = 1L;
}
