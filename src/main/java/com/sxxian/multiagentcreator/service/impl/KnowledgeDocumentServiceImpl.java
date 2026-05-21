package com.sxxian.multiagentcreator.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.sxxian.multiagentcreator.mapper.KnowledgeDocumentMapper;
import com.sxxian.multiagentcreator.model.entity.KnowledgeDocument;
import com.sxxian.multiagentcreator.service.KnowledgeDocumentService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KnowledgeDocumentServiceImpl extends ServiceImpl<KnowledgeDocumentMapper, KnowledgeDocument>
        implements KnowledgeDocumentService {

    @Override
    public List<KnowledgeDocument> listByKnowledgeBaseId(Long knowledgeBaseId, Long userId) {
        return this.list(QueryWrapper.create()
                .eq("knowledgeBaseId", knowledgeBaseId)
                .eq("userId", userId)
                .eq("isDelete", 0)
                .orderBy("createTime", false));
    }
}
