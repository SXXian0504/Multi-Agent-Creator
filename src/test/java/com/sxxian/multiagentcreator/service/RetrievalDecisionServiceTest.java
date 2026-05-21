package com.sxxian.multiagentcreator.rag.retrieval;

import com.sxxian.multiagentcreator.config.RagConfig;
import com.sxxian.multiagentcreator.model.dto.rag.RetrievalDecision;
import com.sxxian.multiagentcreator.model.dto.rag.RetrievalDecisionRequest;
import com.sxxian.multiagentcreator.model.entity.KnowledgeBase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalDecisionServiceTest {

    @Test
    void skipWhenEnhancementDisabled() {
        RetrievalDecisionService service = new RetrievalDecisionService(new RagConfig());

        RetrievalDecision decision = service.decide(RetrievalDecisionRequest.builder()
                .knowledgeEnhanced(false)
                .availableKnowledgeBases(List.of(kb(1L, "style_memory")))
                .build());

        assertFalse(decision.isShouldRetrieve());
    }

    @Test
    void retrieveStyleMemoryWhenUserSelectedWritingStyle() {
        RetrievalDecisionService service = new RetrievalDecisionService(new RagConfig());

        RetrievalDecision decision = service.decide(RetrievalDecisionRequest.builder()
                .knowledgeEnhanced(true)
                .useWritingStyleMemory(true)
                .topic("程序员如何提升系统设计能力")
                .availableKnowledgeBases(List.of(kb(1L, "style_memory"), kb(2L, "domain_knowledge")))
                .build());

        assertTrue(decision.isShouldRetrieve());
        assertTrue(decision.getTargetKnowledgeBaseIds().contains(1L));
    }

    private KnowledgeBase kb(Long id, String type) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setType(type);
        return kb;
    }
}
