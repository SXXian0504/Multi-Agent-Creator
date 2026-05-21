package com.sxxian.multiagentcreator.image.adapter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sxxian.multiagentcreator.config.QwenwConfig;
import com.sxxian.multiagentcreator.model.dto.image.ImageData;
import com.sxxian.multiagentcreator.model.dto.image.ImageRequest;
import com.sxxian.multiagentcreator.model.enums.ImageMethodEnum;
import com.sxxian.multiagentcreator.utils.GsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.sxxian.multiagentcreator.constant.ArticleConstant.PICSUM_URL_TEMPLATE;

/**
 * Bailian/DashScope text-to-image service.
 */
@Service
@Slf4j
public class QwenImageService implements ImageSearchService {

    private static final okhttp3.MediaType JSON = okhttp3.MediaType.parse("application/json; charset=utf-8");

    @Resource
    private QwenwConfig qwenwConfig;

    @Value("${spring.ai.dashscope.api-key:}")
    private String springDashScopeApiKey;

    private final OkHttpClient httpClient = new OkHttpClient();

    @Override
    public String searchImage(String keywords) {
        ImageData imageData = generateImageData(keywords);
        return imageData != null ? imageData.getUrl() : null;
    }

    @Override
    public ImageData getImageData(ImageRequest request) {
        String prompt = request.getEffectiveParam(true);
        return generateImageData(prompt);
    }

    public ImageData generateImageData(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            log.warn("QwenImage prompt is empty");
            return null;
        }
        String apiKey = apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("QwenImage API key is empty, skip text-to-image generation");
            return null;
        }

        try {
            String taskId = submitTask(prompt, apiKey);
            if (taskId == null || taskId.isBlank()) {
                return null;
            }
            String imageUrl = pollTaskResult(taskId, apiKey);
            if (imageUrl == null || imageUrl.isBlank()) {
                return null;
            }
            log.info("QwenImage generated image successfully, model={}, taskId={}, url={}",
                    qwenwConfig.getModel(), taskId, imageUrl);
            return ImageData.fromUrl(imageUrl);
        } catch (Exception e) {
            log.error("QwenImage generation failed, model={}, prompt={}", qwenwConfig.getModel(), prompt, e);
            return null;
        }
    }

    private String submitTask(String prompt, String apiKey) throws IOException {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("size", qwenwConfig.getSize());
        parameters.put("n", qwenwConfig.getN());
        if (qwenwConfig.getStyle() != null && !qwenwConfig.getStyle().isBlank()) {
            parameters.put("style", qwenwConfig.getStyle());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", qwenwConfig.getModel());
        payload.put("input", Map.of("prompt", prompt));
        payload.put("parameters", parameters);

        Request request = new Request.Builder()
                .url(qwenwConfig.getSubmitEndpoint())
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-DashScope-Async", "enable")
                .post(RequestBody.create(GsonUtils.toJson(payload), JSON))
                .build();

        log.info("QwenImage submit task, model={}, size={}, prompt={}",
                qwenwConfig.getModel(), qwenwConfig.getSize(), prompt);
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IllegalStateException("submit image task failed: HTTP " + response.code() + " - " + body);
            }
            JsonObject root = GsonUtils.fromJson(body, JsonObject.class);
            JsonObject output = root != null ? root.getAsJsonObject("output") : null;
            if (output == null || !output.has("task_id")) {
                throw new IllegalStateException("submit image task response missing output.task_id: " + body);
            }
            return output.get("task_id").getAsString();
        }
    }

    private String pollTaskResult(String taskId, String apiKey) throws InterruptedException, IOException {
        int maxAttempts = qwenwConfig.getMaxPollAttempts() != null ? qwenwConfig.getMaxPollAttempts() : 24;
        long pollInterval = qwenwConfig.getPollInterval() != null ? qwenwConfig.getPollInterval() : 5000L;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            TaskStatus taskStatus = queryTask(taskId, apiKey);
            if ("SUCCEEDED".equalsIgnoreCase(taskStatus.status())) {
                return taskStatus.imageUrl();
            }
            if ("FAILED".equalsIgnoreCase(taskStatus.status())
                    || "UNKNOWN".equalsIgnoreCase(taskStatus.status())) {
                throw new IllegalStateException("image task failed: taskId=" + taskId
                        + ", status=" + taskStatus.status() + ", message=" + taskStatus.message());
            }
            log.info("QwenImage task pending, taskId={}, status={}, attempt={}/{}",
                    taskId, taskStatus.status(), attempt, maxAttempts);
            Thread.sleep(pollInterval);
        }
        throw new IllegalStateException("image task timeout: taskId=" + taskId);
    }

    private TaskStatus queryTask(String taskId, String apiKey) throws IOException {
        Request request = new Request.Builder()
                .url(qwenwConfig.getTaskEndpoint() + "/" + taskId)
                .addHeader("Authorization", "Bearer " + apiKey)
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IllegalStateException("query image task failed: HTTP " + response.code() + " - " + body);
            }
            JsonObject root = GsonUtils.fromJson(body, JsonObject.class);
            JsonObject output = root != null ? root.getAsJsonObject("output") : null;
            String status = getString(output, "task_status", "UNKNOWN");
            String imageUrl = extractImageUrl(output);
            String message = getString(output, "message", body);
            return new TaskStatus(status, imageUrl, message);
        }
    }

    private String extractImageUrl(JsonObject output) {
        if (output == null) {
            return null;
        }
        JsonArray results = output.getAsJsonArray("results");
        if (results != null && !results.isEmpty()) {
            JsonElement first = results.get(0);
            if (first.isJsonObject()) {
                String url = getString(first.getAsJsonObject(), "url", null);
                if (url != null && !url.isBlank()) {
                    return url;
                }
            }
        }
        return getString(output, "url", null);
    }

    private String getString(JsonObject object, String key, String defaultValue) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        return object.get(key).getAsString();
    }

    private String apiKey() {
        if (qwenwConfig.getApiKey() != null && !qwenwConfig.getApiKey().isBlank()) {
            return qwenwConfig.getApiKey();
        }
        return springDashScopeApiKey;
    }

    @Override
    public ImageMethodEnum getMethod() {
        return ImageMethodEnum.QWEN_IMAGE;
    }

    @Override
    public String getFallbackImage(int position) {
        return String.format(PICSUM_URL_TEMPLATE, position);
    }

    @Override
    public boolean isAvailable() {
        String apiKey = apiKey();
        return apiKey != null && !apiKey.isBlank();
    }

    private record TaskStatus(String status, String imageUrl, String message) {
    }
}
