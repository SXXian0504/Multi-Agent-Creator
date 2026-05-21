package com.sxxian.multiagentcreator.service;

import com.mybatisflex.core.service.IService;
import com.sxxian.multiagentcreator.model.dto.rag.KnowledgeBaseCreateRequest;
import com.sxxian.multiagentcreator.model.entity.KnowledgeBase;
import com.sxxian.multiagentcreator.model.entity.KnowledgeDocument;
import com.sxxian.multiagentcreator.model.entity.User;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface KnowledgeBaseService extends IService<KnowledgeBase> {

    KnowledgeBase createKnowledgeBase(KnowledgeBaseCreateRequest request, User loginUser);

    List<KnowledgeBase> listMyKnowledgeBases(User loginUser);

    List<KnowledgeBase> listAvailableKnowledgeBases(Long userId, List<Long> selectedIds);

    KnowledgeDocument uploadDocument(Long knowledgeBaseId, MultipartFile file, User loginUser);
}
