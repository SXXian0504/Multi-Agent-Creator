package com.sxxian.multiagentcreator.model.dto.rag;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RetrievalDecision {
    private boolean shouldRetrieve;
    private String decisionType;
    private String reason;
    private List<Long> targetKnowledgeBaseIds;
    private List<String> queries;
    private int topK;
}
