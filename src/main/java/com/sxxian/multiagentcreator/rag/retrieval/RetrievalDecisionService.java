package com.sxxian.multiagentcreator.rag.retrieval;

import com.sxxian.multiagentcreator.config.RagConfig;
import com.sxxian.multiagentcreator.model.dto.rag.RetrievalDecision;
import com.sxxian.multiagentcreator.model.dto.rag.RetrievalDecisionRequest;
import com.sxxian.multiagentcreator.model.entity.KnowledgeBase;
import com.sxxian.multiagentcreator.model.enums.KnowledgeBaseTypeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RetrievalDecisionService {

    private final RagConfig ragConfig;

    public RetrievalDecision decide(RetrievalDecisionRequest request) {
        if (!ragConfig.isEnabled()) {
            return skip("RAG disabled by config");
        }
        if (!request.isKnowledgeEnhanced()) {
            return skip("用户未开启知识库增强");
        }
        List<KnowledgeBase> available = request.getAvailableKnowledgeBases();
        if (available == null || available.isEmpty()) {
            return skip("用户没有可用知识库");
        }

        List<Long> targetIds = new ArrayList<>();
        if (request.isUseWritingStyleMemory()) {
            addByType(targetIds, available, KnowledgeBaseTypeEnum.STYLE_MEMORY.getValue());
        }

        String text = ((request.getTopic() == null ? "" : request.getTopic()) + " "
                + (request.getUserDescription() == null ? "" : request.getUserDescription()))
                .toLowerCase(Locale.ROOT);
        if (containsAny(text, "参考", "资料", "文档", "知识库", "案例", "数据", "报告", "专业")) {
            addByType(targetIds, available, KnowledgeBaseTypeEnum.DOMAIN_KNOWLEDGE.getValue());
            addByType(targetIds, available, KnowledgeBaseTypeEnum.PROJECT_DOCS.getValue());
            addByType(targetIds, available, KnowledgeBaseTypeEnum.TASK_REFERENCE.getValue());
            addByType(targetIds, available, KnowledgeBaseTypeEnum.PERSONA_MEMORY.getValue());
        }
        if (containsAny(text, "风格", "像我", "历史文章", "表达")) {
            addByType(targetIds, available, KnowledgeBaseTypeEnum.STYLE_MEMORY.getValue());
        }
        if (targetIds.isEmpty() && isDomainStyle(request.getStyle())) {
            addByType(targetIds, available, KnowledgeBaseTypeEnum.DOMAIN_KNOWLEDGE.getValue());
        }
        if (targetIds.isEmpty() && request.getSelectedKnowledgeBaseIds() != null && !request.getSelectedKnowledgeBaseIds().isEmpty()) {
            targetIds.addAll(request.getSelectedKnowledgeBaseIds());
        }

        if (targetIds.isEmpty()) {
            return skip("规则未触发检索");
        }
        List<String> queries = List.of(
                compact(request.getTopic() + " " + nullToEmpty(request.getUserDescription())),
                compact(request.getTopic() + " 案例 数据 观点"),
                compact(request.getTopic() + " 写作风格 标题 表达")
        );
        return RetrievalDecision.builder()
                .shouldRetrieve(true)
                .decisionType("RULE_BASED")
                .reason("用户开启知识库增强，规则命中可用知识库")
                .targetKnowledgeBaseIds(targetIds.stream().distinct().toList())
                .queries(queries)
                .topK(ragConfig.getTopK())
                .build();
    }

    private RetrievalDecision skip(String reason) {
        return RetrievalDecision.builder()
                .shouldRetrieve(false)
                .decisionType("RULE_BASED")
                .reason(reason)
                .targetKnowledgeBaseIds(List.of())
                .queries(List.of())
                .topK(0)
                .build();
    }

    private void addByType(List<Long> targetIds, List<KnowledgeBase> available, String type) {
        available.stream()
                .filter(kb -> type.equals(kb.getType()))
                .map(KnowledgeBase::getId)
                .forEach(targetIds::add);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDomainStyle(String style) {
        return style != null && List.of("tech", "educational", "product").contains(style);
    }

    private String compact(String text) {
        return nullToEmpty(text).replaceAll("\\s+", " ").trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
