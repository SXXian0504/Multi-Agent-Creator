package com.sxxian.multiagentcreator.rag.ingestion;

import com.sxxian.multiagentcreator.model.entity.KnowledgeDocument;
import com.sxxian.multiagentcreator.model.entity.KnowledgeIngestionJob;
import com.sxxian.multiagentcreator.model.enums.KnowledgeStatusEnum;
import com.sxxian.multiagentcreator.rag.persistence.PgVectorKnowledgeRepository;
import com.sxxian.multiagentcreator.service.KnowledgeDocumentService;
import com.sxxian.multiagentcreator.service.KnowledgeIngestionJobService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class DocumentIngestionService {

    @Resource
    private KnowledgeDocumentService knowledgeDocumentService;

    @Resource
    private KnowledgeIngestionJobService ingestionJobService;

    @Resource
    private DocumentParserService documentParserService;

    @Resource
    private DocumentChunker documentChunker;

    @Resource
    private RagEmbeddingService embeddingService;

    @Resource
    private PgVectorKnowledgeRepository pgVectorKnowledgeRepository;

    @Async("articleExecutor")
    public void ingestAsync(KnowledgeDocument document, String knowledgeType) {
        KnowledgeIngestionJob job = new KnowledgeIngestionJob();
        job.setDocumentId(document.getId());
        job.setStatus(KnowledgeStatusEnum.PARSING.getValue());
        job.setStartedAt(LocalDateTime.now());
        ingestionJobService.save(job);

        try {
            updateDocumentStatus(document, KnowledgeStatusEnum.PARSING.getValue(), null, 0);
            pgVectorKnowledgeRepository.ensureTable();

            String text = documentParserService.parse(document.getStorageUrl(), document.getFileType());
            List<DocumentChunker.Chunk> chunks = documentChunker.chunk(text, document.getFileName(), knowledgeType);

            pgVectorKnowledgeRepository.deleteByDocumentId(document.getId());
            for (DocumentChunker.Chunk chunk : chunks) {
                List<Double> embedding = embeddingService.embed(chunk.getContent());
                pgVectorKnowledgeRepository.insert(
                        document.getUserId(),
                        document.getKnowledgeBaseId(),
                        document.getId(),
                        chunk,
                        embedding);
            }

            updateDocumentStatus(document, KnowledgeStatusEnum.COMPLETED.getValue(), null, chunks.size());
            job.setStatus(KnowledgeStatusEnum.COMPLETED.getValue());
            job.setFinishedAt(LocalDateTime.now());
            ingestionJobService.updateById(job);
            log.info("RAG 文档索引完成, documentId={}, chunkCount={}", document.getId(), chunks.size());
        } catch (Exception e) {
            log.error("RAG 文档索引失败, documentId={}", document.getId(), e);
            updateDocumentStatus(document, KnowledgeStatusEnum.FAILED.getValue(), e.getMessage(), 0);
            job.setStatus(KnowledgeStatusEnum.FAILED.getValue());
            job.setErrorMessage(e.getMessage());
            job.setFinishedAt(LocalDateTime.now());
            ingestionJobService.updateById(job);
        }
    }

    private void updateDocumentStatus(KnowledgeDocument document, String status, String errorMessage, int chunkCount) {
        KnowledgeDocument update = new KnowledgeDocument();
        update.setId(document.getId());
        update.setParseStatus(status);
        update.setErrorMessage(errorMessage);
        update.setChunkCount(chunkCount);
        knowledgeDocumentService.updateById(update);
    }
}
