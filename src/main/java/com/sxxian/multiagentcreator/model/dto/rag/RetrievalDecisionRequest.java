package com.sxxian.multiagentcreator.model.dto.rag;

import com.sxxian.multiagentcreator.model.entity.KnowledgeBase;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RetrievalDecisionRequest {
    private Long userId;
    private boolean knowledgeEnhanced;
    private boolean useWritingStyleMemory;
    private String topic;
    private String platform;
    private String style;
    private String userDescription;
    private List<Long> selectedKnowledgeBaseIds;
    private List<KnowledgeBase> availableKnowledgeBases;
}
