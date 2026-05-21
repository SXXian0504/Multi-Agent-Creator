package com.sxxian.multiagentcreator.service;

import com.mybatisflex.core.service.IService;
import com.sxxian.multiagentcreator.model.entity.KnowledgeDocument;

import java.util.List;

public interface KnowledgeDocumentService extends IService<KnowledgeDocument> {

    List<KnowledgeDocument> listByKnowledgeBaseId(Long knowledgeBaseId, Long userId);
}
