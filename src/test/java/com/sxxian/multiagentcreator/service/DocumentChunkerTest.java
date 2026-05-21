package com.sxxian.multiagentcreator.rag.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentChunkerTest {

    @Test
    void chunkSplitsLongMarkdownAndKeepsMetadata() {
        DocumentChunker chunker = new DocumentChunker();
        String text = "# 标题\n\n" + "这是一段测试内容。".repeat(180);

        List<DocumentChunker.Chunk> chunks = chunker.chunk(text, "style.md", "style_memory");

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.get(0).getMetadata().contains("style.md"));
        assertTrue(chunks.get(0).getMetadata().contains("style_memory"));
    }
}
