package com.sxxian.multiagentcreator.model.dto.article;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class ImageRevisionConfirmRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;

    private Integer position;

    private String placeholderId;

    private String revisionId;

    private Boolean approved;

    private String userPrompt;
}
