package com.sxxian.multiagentcreator.rag.ingestion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sxxian.multiagentcreator.config.RagConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class RagEmbeddingService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final RagConfig ragConfig;
    private final OkHttpClient httpClient = new OkHttpClient();

    @Value("${spring.ai.dashscope.api-key:}")
    private String dashScopeApiKey;

    public List<Double> embed(String text) {
        if (dashScopeApiKey == null || dashScopeApiKey.isBlank()) {
            if (ragConfig.getEmbedding().isLocalFallbackEnabled()) {
                log.warn("DashScope API Key 为空，使用本地确定性向量降级，仅适合本地开发");
                return localEmbedding(text);
            }
            throw new IllegalStateException("spring.ai.dashscope.api-key is empty");
        }
        try {
            return remoteEmbedding(text);
        } catch (Exception e) {
            if (ragConfig.getEmbedding().isLocalFallbackEnabled()) {
                log.warn("embedding 远程调用失败，使用本地确定性向量降级, error={}", e.getMessage());
                return localEmbedding(text);
            }
            throw new IllegalStateException("embedding 调用失败: " + e.getMessage(), e);
        }
    }

    private List<Double> remoteEmbedding(String text) throws IOException {
        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("model", ragConfig.getEmbedding().getModel());
        requestJson.addProperty("input", text);
        Request request = new Request.Builder()
                .url(ragConfig.getEmbedding().getEndpoint())
                .addHeader("Authorization", "Bearer " + dashScopeApiKey)
                .post(RequestBody.create(requestJson.toString(), JSON))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("embedding http " + response.code() + ": " + body);
            }
            JsonArray embedding = JsonParser.parseString(body)
                    .getAsJsonObject()
                    .getAsJsonArray("data")
                    .get(0).getAsJsonObject()
                    .getAsJsonArray("embedding");
            List<Double> result = new ArrayList<>(embedding.size());
            embedding.forEach(item -> result.add(item.getAsDouble()));
            return result;
        }
    }

    private List<Double> localEmbedding(String text) {
        int dimension = ragConfig.getEmbedding().getDimension();
        double[] vector = new double[dimension];
        String[] terms = (text == null ? "" : text).split("\\s+|(?<=\\p{IsHan})");
        for (String term : terms) {
            if (term.isBlank()) {
                continue;
            }
            byte[] hash = sha256(term);
            int bucket = ((hash[0] & 0xff) << 8 | (hash[1] & 0xff)) % dimension;
            vector[bucket] += 1.0;
        }
        double norm = 0;
        for (double value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        List<Double> result = new ArrayList<>(dimension);
        for (double value : vector) {
            result.add(norm == 0 ? 0.0 : value / norm);
        }
        return result;
    }

    private byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
