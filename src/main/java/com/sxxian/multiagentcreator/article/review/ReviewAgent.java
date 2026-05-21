package com.sxxian.multiagentcreator.article.review;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sxxian.multiagentcreator.annotation.AgentExecution;
import com.sxxian.multiagentcreator.constant.PromptConstant;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.dto.review.ImageReviewResult;
import com.sxxian.multiagentcreator.model.dto.review.ReviewResult;
import com.sxxian.multiagentcreator.model.enums.StructuredOutputTypeEnum;
import com.sxxian.multiagentcreator.service.JsonStructuredOutputService;
import com.sxxian.multiagentcreator.service.SkillService;
import com.sxxian.multiagentcreator.utils.GsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.ai.content.Media;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewAgent {

    private static final int IMAGE_FETCH_CONNECT_TIMEOUT_MS = 3000;
    private static final int IMAGE_FETCH_READ_TIMEOUT_MS = 5000;
    private static final int MAX_IMAGE_REVIEW_BYTES = 10 * 1024 * 1024;

    private final DashScopeChatModel chatModel;
    private final JsonStructuredOutputService jsonStructuredOutputService;
    private final SkillService skillService;
    private final OkHttpClient httpClient = new OkHttpClient();

    @Value("${spring.ai.dashscope.api-key:}")
    private String dashScopeApiKey;

    @Value("${article.review.vision-endpoint:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions}")
    private String visionEndpoint;

    @Value("${article.review.text-model:qwen-max}")
    private String textReviewModel;

    @Value("${article.review.image-model:${article.review.model:qwen-vl-plus}}")
    private String imageReviewModel;

    @Value("${article.review.image-fallback-models:qwen3.5-plus,qwen3.5-omni-plus}")
    private String imageFallbackModels;

    @AgentExecution(value = "review_titles", description = "评审标题候选", phase = "TITLE_REVIEWING")
    public ReviewResult reviewTitles(ArticleState state) {
        return reviewText("标题候选", state, GsonUtils.toJson(state.getTitleOptions()), titleRubric());
    }

    @AgentExecution(value = "review_outline", description = "评审文章大纲", phase = "OUTLINE_REVIEWING")
    public ReviewResult reviewOutline(ArticleState state) {
        return reviewText("文章大纲", state, GsonUtils.toJson(state.getOutline()), outlineRubric());
    }

    @AgentExecution(value = "review_content", description = "评审文章正文", phase = "CONTENT_REVIEWING")
    public ReviewResult reviewContent(ArticleState state) {
        return reviewText("文章正文", state, state.getContent(), contentRubric());
    }

    @AgentExecution(value = "review_image_plan", description = "评审配图计划", phase = "IMAGE_REVIEWING")
    public ReviewResult reviewImagePlan(ArticleState state) {
        String content = GsonUtils.toJson(new ImagePlanReviewPayload(state.getContent(), state.getImageRequirements()));
        return reviewText("配图计划", state, content, imagePlanRubric());
    }

    @AgentExecution(value = "review_image_result", description = "评审图片结果", phase = "IMAGE_REVIEWING")
    public ImageReviewResult reviewImageResult(ArticleState state,
                                               ArticleState.ImageRequirement requirement,
                                               ArticleState.ImageResult imageResult) {
        String prompt = PromptConstant.IMAGE_REVIEW_PROMPT
                .replace("{topic}", nullToEmpty(state.getTopic()))
                .replace("{mainTitle}", state.getTitle() != null ? nullToEmpty(state.getTitle().getMainTitle()) : "")
                .replace("{sectionTitle}", requirement != null ? nullToEmpty(requirement.getSectionTitle()) : "")
                .replace("{imageRequirement}", GsonUtils.toJson(requirement))
                .replace("{imageResult}", GsonUtils.toJson(imageResult));

        String content = callImageReviewLlmWithFallback(prompt, imageResult);
        ImageReviewResult result = jsonStructuredOutputService.parse(
                content,
                ImageReviewResult.class,
                StructuredOutputTypeEnum.IMAGE_REVIEW_RESULT,
                () -> callImageReviewLlmWithFallback(prompt, imageResult),
                1
        );
        normalizeApproval(result);
        normalizeImageRevisionAdvice(result);
        log.info("图片结果评审完成, taskId={}, position={}, score={}, approved={}",
                state.getTaskId(),
                imageResult != null ? imageResult.getPosition() : null,
                result.getScore(),
                result.getApproved());
        return result;
    }

    private void normalizeImageRevisionAdvice(ImageReviewResult result) {
        if (result == null || !isBlank(result.getRevisionAdvice())) {
            return;
        }
        StringBuilder advice = new StringBuilder();
        if (!isBlank(result.getObservation())) {
            advice.append("根据图片观察修正：").append(result.getObservation()).append("\n");
        }
        appendList(advice, "需要解决的问题", result.getProblems());
        appendList(advice, "重规划建议", result.getSuggestions());
        if (!isBlank(result.getNextAction())) {
            advice.append("建议动作：").append(result.getNextAction()).append("\n");
        }
        result.setRevisionAdvice(advice.toString().trim());
    }

    public String buildRevisionAdvice(ReviewResult reviewResult) {
        if (reviewResult == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n【上一轮 ReviewAgent 评审未通过，请按以下意见重写当前阶段，不要重跑其他阶段】\n");
        if (reviewResult.getScore() != null) {
            sb.append("评分：").append(reviewResult.getScore()).append("/100\n");
        }
        appendList(sb, "问题", reviewResult.getProblems());
        appendList(sb, "改进方向", reviewResult.getSuggestions());
        if (reviewResult.getNextAction() != null) {
            sb.append("建议动作：").append(reviewResult.getNextAction()).append("\n");
        }
        return sb.toString();
    }

    private ReviewResult reviewText(String stageName, ArticleState state, String content, String stageRubric) {
        String prompt = PromptConstant.REVIEW_PROMPT
                .replace("{stageName}", stageName)
                .replace("{styleName}", getStyleName(state.getPlatform(), state.getStyle()))
                .replace("{topic}", nullToEmpty(state.getTopic()))
                .replace("{mainTitle}", state.getTitle() != null ? nullToEmpty(state.getTitle().getMainTitle()) : "")
                .replace("{subTitle}", state.getTitle() != null ? nullToEmpty(state.getTitle().getSubTitle()) : "")
                .replace("{userDescription}", nullToEmpty(state.getUserDescription()))
                .replace("{contentProfile}", buildContentProfile(stageName, state, content))
                .replace("{content}", nullToEmpty(content))
                .replace("{styleRubric}", styleRubric(state.getPlatform(), state.getStyle()))
                .replace("{stageRubric}", stageRubric);

        String raw = callReviewLlm(prompt);
        ReviewResult result = jsonStructuredOutputService.parse(
                raw,
                ReviewResult.class,
                StructuredOutputTypeEnum.REVIEW_RESULT,
                () -> callReviewLlm(prompt),
                1
        );
        normalizeApproval(result);
        log.info("{}评审完成, taskId={}, model={}, score={}, approved={}, dimensionScores={}",
                stageName, state.getTaskId(), textReviewModel, result.getScore(), result.getApproved(),
                result.getDimensionScores());
        return result;
    }

    private void normalizeApproval(ReviewResult result) {
        if (result == null || result.getScore() == null) {
            return;
        }
        result.setApproved(result.getScore() >= 80);
    }

    private String callReviewLlm(String prompt) {
        return callReviewLlm(prompt, List.of(), textReviewModel);
    }

    private String callReviewLlm(String prompt, List<Media> media) {
        String model = media == null || media.isEmpty() ? textReviewModel : imageReviewModel;
        return callReviewLlm(prompt, media, model);
    }

    private String callImageReviewLlmWithFallback(String prompt, ArticleState.ImageResult imageResult) {
        ImageReviewInput imageInput = imageReviewInput(imageResult);
        if (imageInput == null) {
            return callReviewLlm(prompt, List.of(), textReviewModel);
        }
        List<String> models = imageReviewModels();
        RuntimeException lastException = null;
        for (String model : models) {
            try {
                String content = callVisionReviewLlm(prompt, imageInput, model);
                log.info("图片评审多模态调用成功, model={}, url={}",
                        model,
                        imageResult != null ? imageResult.getUrl() : null);
                return content;
            } catch (RuntimeException e) {
                lastException = e;
                log.warn("图片评审多模态调用失败, model={}, url={}, reason={}",
                        model,
                        imageResult != null ? imageResult.getUrl() : null,
                        e.getMessage());
            }
        }
        log.warn("图片评审所有多模态模型均失败，降级为纯文本评审, models={}, fallbackModel={}, url={}, lastReason={}",
                models,
                textReviewModel,
                imageResult != null ? imageResult.getUrl() : null,
                lastException != null ? lastException.getMessage() : "");
        return callReviewLlm(prompt, List.of(), textReviewModel);
    }

    private String callReviewLlm(String prompt, List<Media> media, String model) {
        log.info("ReviewAgent 调用模型, model={}, mediaCount={}", model, media == null ? 0 : media.size());
        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .withModel(model)
                .withTemperature(0.0)
                .build();
        UserMessage message = media == null || media.isEmpty()
                ? new UserMessage(prompt)
                : UserMessage.builder().text(prompt).media(media).build();
        ChatResponse response = chatModel.call(new Prompt(message, options));
        return response.getResult().getOutput().getText();
    }

    private List<String> imageReviewModels() {
        List<String> models = new ArrayList<>();
        if (imageReviewModel != null && !imageReviewModel.isBlank()) {
            models.add(imageReviewModel.trim());
        }
        if (imageFallbackModels != null && !imageFallbackModels.isBlank()) {
            Arrays.stream(imageFallbackModels.split(","))
                    .map(String::trim)
                    .filter(model -> !model.isBlank())
                    .filter(model -> models.stream().noneMatch(existing -> existing.equalsIgnoreCase(model)))
                    .forEach(models::add);
        }
        return models.isEmpty() ? List.of("qwen3.5-plus") : models;
    }

    private String callVisionReviewLlm(String prompt, ImageReviewInput imageInput, String model) {
        if (dashScopeApiKey == null || dashScopeApiKey.isBlank()) {
            throw new IllegalStateException("spring.ai.dashscope.api-key is empty");
        }
        String dataUrl = "data:" + imageInput.mimeType() + ";base64,"
                + Base64.getEncoder().encodeToString(imageInput.bytes());
        Map<String, Object> payload = Map.of(
                "model", model,
                "temperature", 0,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of(
                                        "type", "image_url",
                                        "image_url", Map.of("url", dataUrl)
                                ),
                                Map.of(
                                        "type", "text",
                                        "text", prompt
                                )
                        )
                ))
        );
        String requestBody = GsonUtils.toJson(payload);
        log.info("ReviewAgent 调用视觉模型, model={}, imageBytes={}, mimeType={}",
                model, imageInput.bytes().length, imageInput.mimeType());
        Request request = new Request.Builder()
                .url(visionEndpoint)
                .addHeader("Authorization", "Bearer " + dashScopeApiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, okhttp3.MediaType.parse("application/json; charset=utf-8")))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP " + response.code() + " - " + body);
            }
            return extractVisionResponseText(body);
        } catch (IOException e) {
            throw new IllegalStateException("call DashScope vision review failed", e);
        }
    }

    private String extractVisionResponseText(String body) {
        JsonObject root = GsonUtils.fromJson(body, JsonObject.class);
        JsonArray choices = root != null ? root.getAsJsonArray("choices") : null;
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("DashScope vision response missing choices");
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject message = choice.getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            throw new IllegalStateException("DashScope vision response missing message.content");
        }
        JsonElement content = message.get("content");
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        return content.toString();
    }

    private ImageReviewInput imageReviewInput(ArticleState.ImageResult imageResult) {
        if (imageResult == null || imageResult.getUrl() == null || imageResult.getUrl().isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(imageResult.getUrl());
            if (!isHttpUrl(uri)) {
                log.warn("图片评审跳过多模态输入, 非 HTTP 图片 URL: {}", imageResult.getUrl());
                return null;
            }
            return downloadImageForReview(uri);
        } catch (Exception e) {
            log.warn("图片评审无法构建多模态输入, url={}", imageResult.getUrl(), e);
            return null;
        }
    }

    private ImageReviewInput downloadImageForReview(URI uri) {
        try {
            URLConnection connection = uri.toURL().openConnection();
            connection.setConnectTimeout(IMAGE_FETCH_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(IMAGE_FETCH_READ_TIMEOUT_MS);

            if (connection instanceof HttpURLConnection httpConnection) {
                httpConnection.setInstanceFollowRedirects(true);
                int statusCode = httpConnection.getResponseCode();
                if (statusCode < 200 || statusCode >= 300) {
                    log.warn("图片评审跳过多模态输入, 图片 URL 请求失败, status={}, url={}", statusCode, uri);
                    return null;
                }
            }

            int contentLength = connection.getContentLength();
            if (contentLength > MAX_IMAGE_REVIEW_BYTES) {
                log.warn("图片评审跳过多模态输入, 图片过大, size={} bytes, url={}", contentLength, uri);
                return null;
            }

            byte[] bytes;
            try (InputStream inputStream = connection.getInputStream()) {
                bytes = readWithLimit(inputStream, MAX_IMAGE_REVIEW_BYTES);
            }
            if (bytes.length == 0) {
                log.warn("图片评审跳过多模态输入, 图片内容为空, url={}", uri);
                return null;
            }

            MimeType mimeType = resolveImageMimeType(connection.getContentType(), uri);
            log.info("图片评审使用下载后的图片字节构造 base64 多模态输入, size={} bytes, mimeType={}, url={}",
                    bytes.length, mimeType, uri);
            return new ImageReviewInput(bytes, mimeType.toString());
        } catch (Exception e) {
            log.warn("图片评审下载图片失败，降级为纯文本评审, url={}, reason={}", uri, e.getMessage());
            return null;
        }
    }

    private byte[] readWithLimit(InputStream inputStream, int maxBytes) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("图片超过多模态评审大小限制: " + maxBytes + " bytes");
            }
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    private MimeType resolveImageMimeType(String contentType, URI uri) {
        if (contentType != null && !contentType.isBlank()) {
            String normalizedContentType = contentType.split(";", 2)[0].trim();
            try {
                MimeType mimeType = MimeTypeUtils.parseMimeType(normalizedContentType);
                if ("image".equalsIgnoreCase(mimeType.getType())) {
                    return mimeType;
                }
                log.warn("图片评审 URL Content-Type 不是图片, contentType={}, url={}", contentType, uri);
            } catch (Exception e) {
                log.warn("图片评审无法解析 Content-Type, contentType={}, url={}", contentType, uri);
            }
        }
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
        if (path.endsWith(".png")) {
            return MimeTypeUtils.IMAGE_PNG;
        }
        if (path.endsWith(".gif")) {
            return MimeTypeUtils.IMAGE_GIF;
        }
        return MimeTypeUtils.IMAGE_JPEG;
    }

    private String imageExtension(MimeType mimeType) {
        String subtype = mimeType.getSubtype();
        if ("png".equalsIgnoreCase(subtype)) {
            return ".png";
        }
        if ("gif".equalsIgnoreCase(subtype)) {
            return ".gif";
        }
        if ("webp".equalsIgnoreCase(subtype)) {
            return ".webp";
        }
        return ".jpg";
    }

    private boolean isHttpUrl(URI uri) {
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private boolean isImageUrlError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lowerMessage = message.toLowerCase();
                if (lowerMessage.contains("url error")
                        || lowerMessage.contains("invalidparameter")
                        || lowerMessage.contains("please check url")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String getStyleName(String platform, String style) {
        return skillService.resolve(platform, style).getDisplayName();
    }

    private String styleRubric(String platform, String style) {
        return skillService.resolve(platform, style).getReviewRubric();
    }

    private String titleRubric() {
        return "- 标题吸引力、差异化、风格匹配、是否承接正文方向。";
    }

    private String outlineRubric() {
        return "- 大纲结构覆盖、章节边界、可写性、风格路径。";
    }

    private String contentRubric() {
        return "- 正文大纲覆盖、风格一致、信息/情绪/转化目标达成。";
    }

    private String imagePlanRubric() {
        return "- 配图位置、工具选择、关键词或 prompt 质量、占位符匹配。";
    }

    private String buildContentProfile(String stageName, ArticleState state, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 阶段：").append(stageName).append("\n");
        sb.append("- 写作 Skill：").append(getStyleName(state.getPlatform(), state.getStyle())).append("\n");
        sb.append("- 字数范围：").append(nullToEmpty(state.getWordRange())).append("\n");
        sb.append("- 待评审内容字符数：").append(content == null ? 0 : content.length()).append("\n");

        if (state.getTitleOptions() != null) {
            sb.append("- 标题候选数量：").append(state.getTitleOptions().size()).append("\n");
        }
        if (state.getTitle() != null) {
            sb.append("- 已选标题：")
                    .append(nullToEmpty(state.getTitle().getMainTitle()))
                    .append(" / ")
                    .append(nullToEmpty(state.getTitle().getSubTitle()))
                    .append("\n");
        }
        if (state.getOutline() != null && state.getOutline().getSections() != null) {
            sb.append("- 大纲章节数：").append(state.getOutline().getSections().size()).append("\n");
            sb.append("- 大纲章节标题：");
            for (ArticleState.OutlineSection section : state.getOutline().getSections()) {
                sb.append(section.getSection()).append(".").append(nullToEmpty(section.getTitle())).append("；");
            }
            sb.append("\n");
        }
        if (content != null && !content.isBlank()) {
            sb.append("- Markdown 二级标题数量：").append(countMarkdownHeadings(content)).append("\n");
            sb.append("- 内容开头片段：").append(firstChars(content, 180)).append("\n");
        }
        return sb.toString();
    }

    private int countMarkdownHeadings(String content) {
        int count = 0;
        for (String line : content.split("\\R")) {
            if (line.trim().startsWith("## ")) {
                count++;
            }
        }
        return count;
    }

    private String firstChars(String content, int maxLength) {
        String compact = content.replaceAll("\\s+", " ").trim();
        if (compact.length() <= maxLength) {
            return compact;
        }
        return compact.substring(0, maxLength) + "...";
    }

    private void appendList(StringBuilder sb, String title, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        sb.append(title).append("：\n");
        for (String value : values) {
            sb.append("- ").append(value).append("\n");
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record ImagePlanReviewPayload(String contentWithPlaceholders,
                                          List<ArticleState.ImageRequirement> imageRequirements) {
    }

    private record ImageReviewInput(byte[] bytes, String mimeType) {
    }
}
