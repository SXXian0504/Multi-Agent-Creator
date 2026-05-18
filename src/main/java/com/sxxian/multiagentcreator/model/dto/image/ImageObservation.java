package com.sxxian.multiagentcreator.model.dto.image;

import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.dto.review.ImageReviewResult;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

@Data
@Builder
public class ImageObservation implements Serializable {

    private String taskId;
    private String topic;
    private String mainTitle;
    private String sectionTitle;
    private ArticleState.ImageRequirement requirement;
    private ArticleState.ImageResult imageResult;
    private ImageReviewResult reviewResult;
    private String toolError;
    private Integer attempt;
}
