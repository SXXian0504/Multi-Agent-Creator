package com.sxxian.multiagentcreator.model.dto.review;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
public class ImageReviewResult extends ReviewResult {

    @Serial
    private static final long serialVersionUID = 1L;

    private String observation;

    private String revisionAdvice;
}
