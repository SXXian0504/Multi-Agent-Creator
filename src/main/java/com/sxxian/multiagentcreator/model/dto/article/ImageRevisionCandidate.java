package com.sxxian.multiagentcreator.model.dto.article;

import com.sxxian.multiagentcreator.model.dto.review.ImageReviewResult;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class ImageRevisionCandidate implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String revisionId;

    private Integer position;

    private String placeholderId;

    private String sourceImageUrl;

    private String revisedImageUrl;

    private String method;

    private String userPrompt;

    private String composedPrompt;

    private ArticleState.ImageRequirement originalRequirement;

    private ArticleState.ImageResult originalImage;

    private ImageReviewResult previousReviewResult;

    private ImageReviewResult revisedReviewResult;

    private String status;

    private Long createdAt;

    private Long confirmedAt;
}
