package com.sxxian.multiagentcreator.model.dto.rag;

import lombok.Data;

@Data
public class KnowledgeChunk {
    private Long id;
    private Long userId;
    private Long knowledgeBaseId;
    private Long documentId;
    private Integer chunkIndex;
    private String content;
    private String metadata;
    private Double distance;
}
