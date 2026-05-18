package com.sxxian.multiagentcreator.model.dto.image;

import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.dto.review.ImageReviewResult;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
public class ImageExecutionResult implements Serializable {

    private List<ArticleState.ImageResult> images;
    private List<ImageReviewResult> imageReviewResults;
    private List<ArticleState.ImageExecutionTrace> traces;
    private boolean fallbackUsed;
}
