package com.sxxian.multiagentcreator.rag.retrieval;

import com.google.gson.reflect.TypeToken;
import com.sxxian.multiagentcreator.model.dto.rag.KnowledgeChunk;
import com.sxxian.multiagentcreator.model.dto.rag.RetrievalDecision;
import com.sxxian.multiagentcreator.model.dto.rag.RetrievalDecisionRequest;
import com.sxxian.multiagentcreator.model.entity.Article;
import com.sxxian.multiagentcreator.model.entity.KnowledgeBase;
import com.sxxian.multiagentcreator.service.KnowledgeBaseService;
import com.sxxian.multiagentcreator.utils.GsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class RagService {

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Resource
    private RetrievalDecisionService retrievalDecisionService;

    @Resource
    private KnowledgeRetriever knowledgeRetriever;

    @Resource
    private RagContextBuilder ragContextBuilder;

    public String buildRetrievedContext(Article article) {
        if (article == null || article.getKnowledgeEnhanced() == null || article.getKnowledgeEnhanced() == 0) {
            return "";
        }
        try {
            List<Long> selectedIds = parseKnowledgeBaseIds(article.getKnowledgeBaseIds());
            List<KnowledgeBase> available = knowledgeBaseService.listAvailableKnowledgeBases(article.getUserId(), selectedIds);
            RetrievalDecision decision = retrievalDecisionService.decide(RetrievalDecisionRequest.builder()
                    .userId(article.getUserId())
                    .knowledgeEnhanced(article.getKnowledgeEnhanced() != null && article.getKnowledgeEnhanced() == 1)
                    .useWritingStyleMemory(article.getUseWritingStyleMemory() != null && article.getUseWritingStyleMemory() == 1)
                    .topic(article.getTopic())
                    .platform(article.getPlatform())
                    .style(article.getStyle())
                    .userDescription(article.getUserDescription())
                    .selectedKnowledgeBaseIds(selectedIds)
                    .availableKnowledgeBases(available)
                    .build());
            if (!decision.isShouldRetrieve()) {
                log.info("RAG 检索跳过, taskId={}, reason={}", article.getTaskId(), decision.getReason());
                return "";
            }
            List<KnowledgeChunk> chunks = knowledgeRetriever.retrieve(article.getUserId(), decision);
            String context = ragContextBuilder.build(chunks);
            log.info("RAG 检索完成, taskId={}, chunkCount={}, knowledgeBaseIds={}",
                    article.getTaskId(), chunks.size(), decision.getTargetKnowledgeBaseIds());
            return context;
        } catch (Exception e) {
            log.warn("RAG 检索失败，降级为普通创作, taskId={}, error={}", article.getTaskId(), e.getMessage());
            log.debug("RAG failure stacktrace, taskId={}", article.getTaskId(), e);
            return "";
        }
    }

    private List<Long> parseKnowledgeBaseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return GsonUtils.fromJson(raw, new TypeToken<List<Long>>() {});
    }
}
