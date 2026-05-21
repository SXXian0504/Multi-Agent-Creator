package com.sxxian.multiagentcreator.rag.ingestion;

import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
public class DocumentChunker {

    private static final int TARGET_SIZE = 700;
    private static final int OVERLAP = 120;

    public List<Chunk> chunk(String text, String fileName, String knowledgeType) {
        String normalized = normalize(text);
        List<String> blocks = splitBlocks(normalized);
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String block : blocks) {
            if (current.length() + block.length() > TARGET_SIZE && !current.isEmpty()) {
                addChunk(chunks, current.toString(), fileName, knowledgeType);
                current = new StringBuilder(tail(current.toString()));
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(block);
        }
        if (!current.isEmpty()) {
            addChunk(chunks, current.toString(), fileName, knowledgeType);
        }
        return chunks;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private List<String> splitBlocks(String text) {
        List<String> result = new ArrayList<>();
        for (String block : text.split("\\n\\s*\\n")) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() <= TARGET_SIZE) {
                result.add(trimmed);
                continue;
            }
            for (int i = 0; i < trimmed.length(); i += TARGET_SIZE) {
                result.add(trimmed.substring(i, Math.min(i + TARGET_SIZE, trimmed.length())));
            }
        }
        return result;
    }

    private void addChunk(List<Chunk> chunks, String content, String fileName, String knowledgeType) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        chunks.add(Chunk.builder()
                .index(chunks.size())
                .content(trimmed)
                .contentHash(sha256(trimmed))
                .tokenCount(Math.max(1, trimmed.length() / 2))
                .metadata("{\"fileName\":\"" + escape(fileName) + "\",\"knowledgeType\":\""
                        + escape(knowledgeType) + "\",\"chunkIndex\":" + chunks.size() + ",\"source\":\"user_upload\"}")
                .build());
    }

    private String tail(String text) {
        if (text.length() <= OVERLAP) {
            return text;
        }
        return text.substring(text.length() - OVERLAP);
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Data
    @Builder
    public static class Chunk {
        private int index;
        private String content;
        private String contentHash;
        private int tokenCount;
        private String metadata;
    }
}
