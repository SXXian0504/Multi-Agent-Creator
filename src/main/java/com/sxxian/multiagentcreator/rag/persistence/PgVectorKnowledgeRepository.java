package com.sxxian.multiagentcreator.rag.persistence;

import com.sxxian.multiagentcreator.config.RagConfig;
import com.sxxian.multiagentcreator.model.dto.rag.KnowledgeChunk;
import com.sxxian.multiagentcreator.rag.ingestion.DocumentChunker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Repository
@Slf4j
@RequiredArgsConstructor
public class PgVectorKnowledgeRepository {

    private final RagConfig ragConfig;

    public void ensureTable() {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS knowledge_chunk (
                      id BIGSERIAL PRIMARY KEY,
                      user_id BIGINT NOT NULL,
                      knowledge_base_id BIGINT NOT NULL,
                      document_id BIGINT NOT NULL,
                      chunk_index INT NOT NULL,
                      content TEXT NOT NULL,
                      content_hash VARCHAR(64),
                      token_count INT,
                      metadata JSONB,
                      embedding VECTOR(%d),
                      create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """.formatted(ragConfig.getEmbedding().getDimension()));
            statement.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_scope ON knowledge_chunk(user_id, knowledge_base_id, document_id)");
        } catch (SQLException e) {
            throw new IllegalStateException("初始化 pgvector 表失败: " + e.getMessage(), e);
        }
    }

    public void deleteByDocumentId(Long documentId) {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM knowledge_chunk WHERE document_id = ?")) {
            statement.setLong(1, documentId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除旧 chunk 失败: " + e.getMessage(), e);
        }
    }

    public void insert(Long userId, Long knowledgeBaseId, Long documentId, DocumentChunker.Chunk chunk, List<Double> embedding) {
        String sql = """
                INSERT INTO knowledge_chunk
                (user_id, knowledge_base_id, document_id, chunk_index, content, content_hash, token_count, metadata, embedding)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector)
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setLong(2, knowledgeBaseId);
            statement.setLong(3, documentId);
            statement.setInt(4, chunk.getIndex());
            statement.setString(5, chunk.getContent());
            statement.setString(6, chunk.getContentHash());
            statement.setInt(7, chunk.getTokenCount());
            statement.setString(8, chunk.getMetadata());
            statement.setString(9, toVectorLiteral(embedding));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("写入 pgvector chunk 失败: " + e.getMessage(), e);
        }
    }

    public List<KnowledgeChunk> search(Long userId, List<Long> knowledgeBaseIds, List<Double> queryEmbedding, int topK) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }
        String placeholders = knowledgeBaseIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = """
                SELECT id, user_id, knowledge_base_id, document_id, chunk_index, content, metadata::text,
                       embedding <=> ?::vector AS distance
                FROM knowledge_chunk
                WHERE user_id = ? AND knowledge_base_id IN (%s)
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """.formatted(placeholders);
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            String vector = toVectorLiteral(queryEmbedding);
            int index = 1;
            statement.setString(index++, vector);
            statement.setLong(index++, userId);
            for (Long knowledgeBaseId : knowledgeBaseIds) {
                statement.setLong(index++, knowledgeBaseId);
            }
            statement.setString(index++, vector);
            statement.setInt(index, topK);
            try (ResultSet rs = statement.executeQuery()) {
                List<KnowledgeChunk> chunks = new ArrayList<>();
                while (rs.next()) {
                    KnowledgeChunk chunk = new KnowledgeChunk();
                    chunk.setId(rs.getLong(1));
                    chunk.setUserId(rs.getLong(2));
                    chunk.setKnowledgeBaseId(rs.getLong(3));
                    chunk.setDocumentId(rs.getLong(4));
                    chunk.setChunkIndex(rs.getInt(5));
                    chunk.setContent(rs.getString(6));
                    chunk.setMetadata(rs.getString(7));
                    chunk.setDistance(rs.getDouble(8));
                    chunks.add(chunk);
                }
                return chunks;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("pgvector 检索失败: " + e.getMessage(), e);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                ragConfig.getDatasource().getUrl(),
                ragConfig.getDatasource().getUsername(),
                ragConfig.getDatasource().getPassword());
    }

    private String toVectorLiteral(List<Double> embedding) {
        return embedding.stream()
                .map(value -> String.format(java.util.Locale.US, "%.8f", value))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
