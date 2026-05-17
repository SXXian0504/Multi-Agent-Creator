package com.sxxian.multiagentcreator.model.dto.review;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
public class ReviewResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Boolean approved;

    private Integer score;

    private Map<String, Integer> dimensionScores;

    private List<String> problems;

    private List<String> suggestions;

    private String nextAction;

    public boolean isApprovedByThreshold() {
        return Boolean.TRUE.equals(approved) && score != null && score >= 80;
    }
}
