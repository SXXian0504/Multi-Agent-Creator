package com.sxxian.multiagentcreator.rag.retrieval;

import com.sxxian.multiagentcreator.model.dto.rag.KnowledgeChunk;
import com.sxxian.multiagentcreator.model.dto.rag.RetrievalDecision;
import com.sxxian.multiagentcreator.rag.ingestion.RagEmbeddingService;
import com.sxxian.multiagentcreator.rag.persistence.PgVectorKnowledgeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KnowledgeRetriever {

    private final RagEmbeddingService embeddingService;
    private final PgVectorKnowledgeRepository repository;

    public List<KnowledgeChunk> retrieve(Long userId, RetrievalDecision decision) {
        Map<Long, KnowledgeChunk> chunksById = new LinkedHashMap<>();
        for (String query : decision.getQueries()) {
            List<Double> embedding = embeddingService.embed(query);
            List<KnowledgeChunk> chunks = repository.search(
                    userId,
                    decision.getTargetKnowledgeBaseIds(),
                    embedding,
                    decision.getTopK());
            for (KnowledgeChunk chunk : chunks) {
                chunksById.putIfAbsent(chunk.getId(), chunk);
            }
        }
        return chunksById.values().stream()
                .sorted(Comparator.comparing(KnowledgeChunk::getDistance, Comparator.nullsLast(Double::compareTo)))
                .limit(decision.getTopK())
                .toList();
    }
}
