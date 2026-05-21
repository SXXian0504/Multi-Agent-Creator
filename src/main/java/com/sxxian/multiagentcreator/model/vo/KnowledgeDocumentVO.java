package com.sxxian.multiagentcreator.model.vo;

import com.sxxian.multiagentcreator.model.entity.KnowledgeDocument;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class KnowledgeDocumentVO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long knowledgeBaseId;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String parseStatus;
    private Integer chunkCount;
    private String errorMessage;
    private LocalDateTime createTime;

    public static KnowledgeDocumentVO fromEntity(KnowledgeDocument entity) {
        KnowledgeDocumentVO vo = new KnowledgeDocumentVO();
        vo.setId(entity.getId());
        vo.setKnowledgeBaseId(entity.getKnowledgeBaseId());
        vo.setFileName(entity.getFileName());
        vo.setFileType(entity.getFileType());
        vo.setFileSize(entity.getFileSize());
        vo.setParseStatus(entity.getParseStatus());
        vo.setChunkCount(entity.getChunkCount());
        vo.setErrorMessage(entity.getErrorMessage());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }
}
