package com.sxxian.multiagentcreator.model.dto.article;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class OutlineImageIntentRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;

    private String intentId;

    private Integer sectionIndex;

    private Integer pointIndex;

    private String sectionTitle;

    private String anchorType;

    private String mode;

    private String uploadedImageUrl;

    private String uploadedImageObjectKey;

    private String userPrompt;

    private String altText;

    private String reason;

    private Boolean forceUse;
}
