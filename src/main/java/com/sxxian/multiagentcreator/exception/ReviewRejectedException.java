package com.sxxian.multiagentcreator.exception;

import com.sxxian.multiagentcreator.model.dto.review.ReviewResult;
import lombok.Getter;

@Getter
public class ReviewRejectedException extends RuntimeException {

    private final String phase;

    private final ReviewResult reviewResult;

    public ReviewRejectedException(String phase, ReviewResult reviewResult) {
        super(buildMessage(phase, reviewResult));
        this.phase = phase;
        this.reviewResult = reviewResult;
    }

    private static String buildMessage(String phase, ReviewResult reviewResult) {
        Integer score = reviewResult != null ? reviewResult.getScore() : null;
        String problems = reviewResult != null && reviewResult.getProblems() != null
                ? String.join("；", reviewResult.getProblems())
                : "评审未通过";
        return "阶段评审未通过: phase=" + phase + ", score=" + score + ", problems=" + problems;
    }
}
