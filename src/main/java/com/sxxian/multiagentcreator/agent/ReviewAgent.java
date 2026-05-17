package com.sxxian.multiagentcreator.agent;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.sxxian.multiagentcreator.annotation.AgentExecution;
import com.sxxian.multiagentcreator.constant.PromptConstant;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.dto.review.ImageReviewResult;
import com.sxxian.multiagentcreator.model.dto.review.ReviewResult;
import com.sxxian.multiagentcreator.model.enums.ArticleStyleEnum;
import com.sxxian.multiagentcreator.model.enums.StructuredOutputTypeEnum;
import com.sxxian.multiagentcreator.service.JsonStructuredOutputService;
import com.sxxian.multiagentcreator.utils.GsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.content.Media;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
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
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewAgent {

    private static final int IMAGE_FETCH_CONNECT_TIMEOUT_MS = 3000;
    private static final int IMAGE_FETCH_READ_TIMEOUT_MS = 5000;
    private static final int MAX_IMAGE_REVIEW_BYTES = 10 * 1024 * 1024;

    private final DashScopeChatModel chatModel;
    private final JsonStructuredOutputService jsonStructuredOutputService;

    @Value("${article.review.text-model:qwen-max}")
    private String textReviewModel;

    @Value("${article.review.image-model:${article.review.model:qwen-vl-plus-latest}}")
    private String imageReviewModel;

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
        log.info("图片结果评审完成, taskId={}, position={}, score={}, approved={}",
                state.getTaskId(),
                imageResult != null ? imageResult.getPosition() : null,
                result.getScore(),
                result.getApproved());
        return result;
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
                .replace("{styleName}", getStyleName(state.getStyle()))
                .replace("{topic}", nullToEmpty(state.getTopic()))
                .replace("{mainTitle}", state.getTitle() != null ? nullToEmpty(state.getTitle().getMainTitle()) : "")
                .replace("{subTitle}", state.getTitle() != null ? nullToEmpty(state.getTitle().getSubTitle()) : "")
                .replace("{userDescription}", nullToEmpty(state.getUserDescription()))
                .replace("{contentProfile}", buildContentProfile(stageName, state, content))
                .replace("{content}", nullToEmpty(content))
                .replace("{styleRubric}", styleRubric(state.getStyle()))
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
        List<Media> media = imageMedia(imageResult);
        if (media.isEmpty()) {
            return callReviewLlm(prompt, List.of(), textReviewModel);
        }
        try {
            return callReviewLlm(prompt, media, imageReviewModel);
        } catch (RuntimeException e) {
            if (!isImageUrlError(e)) {
                throw e;
            }
            log.warn("图片评审多模态调用失败，降级为纯文本评审, model={}, fallbackModel={}, url={}, reason={}",
                    imageReviewModel,
                    textReviewModel,
                    imageResult != null ? imageResult.getUrl() : null,
                    e.getMessage());
            return callReviewLlm(prompt, List.of(), textReviewModel);
        }
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

    private List<Media> imageMedia(ArticleState.ImageResult imageResult) {
        if (imageResult == null || imageResult.getUrl() == null || imageResult.getUrl().isBlank()) {
            return List.of();
        }
        try {
            URI uri = URI.create(imageResult.getUrl());
            if (!isHttpUrl(uri)) {
                log.warn("图片评审跳过多模态输入, 非 HTTP 图片 URL: {}", imageResult.getUrl());
                return List.of();
            }
            Media media = downloadImageAsMedia(uri);
            return media == null ? List.of() : List.of(media);
        } catch (Exception e) {
            log.warn("图片评审无法构建多模态输入, url={}", imageResult.getUrl(), e);
            return List.of();
        }
    }

    private Media downloadImageAsMedia(URI uri) {
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
            ByteArrayResource resource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return "review-image" + imageExtension(mimeType);
                }
            };
            log.info("图片评审使用本地下载后的图片字节作为多模态输入, size={} bytes, mimeType={}, url={}",
                    bytes.length, mimeType, uri);
            return new Media(mimeType, resource);
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

    private String getStyleName(String style) {
        ArticleStyleEnum styleEnum = ArticleStyleEnum.getEnumByValue(style);
        return styleEnum != null ? styleEnum.getText() : "默认风格";
    }

    private String styleRubric(String style) {
        ArticleStyleEnum styleEnum = ArticleStyleEnum.getEnumByValue(style);
        if (styleEnum == null) {
            return """
                    - 目标读者匹配 15：是否符合常见新媒体文章读者预期。
                    - 内容价值 10：是否提供观点、信息或启发。
                    - 表达一致 10：语气是否稳定，不突兀切换。
                    - 阅读动力 10：是否能让读者持续读完。
                    """;
        }
        return switch (styleEnum) {
            case MARKETING -> """
                    - 转化路径 15：是否从痛点到方案到行动引导形成闭环。
                    - 卖点清晰 10：核心利益、差异化和适用人群是否明确。
                    - 信任感 10：是否有合理证据、场景、案例或风险说明支撑。
                    - 行动引导 10：CTA 是否自然、具体，不过度硬广。
                    """;
            case EDUCATIONAL -> """
                    - 知识准确性 15：概念、因果和结论是否严谨，不能把推测写成事实。
                    - 解释能力 10：是否深入浅出，有定义、例子或类比。
                    - 逻辑递进 10：是否从基础到进阶，读者能跟上。
                    - 边界意识 10：是否说明适用范围、不确定性或必要前提。
                    """;
            case EMOTIONAL -> """
                    - 共鸣强度 15：是否触达具体情绪和真实处境。
                    - 叙事感染力 10：是否有细节、节奏和画面感。
                    - 情绪克制 10：避免空泛鸡汤、过度煽情或价值绑架。
                    - 观点落点 10：是否有清晰启发、陪伴感或情绪出口。
                    """;
            case TECH -> """
                    - 专业严谨 15：术语、架构、趋势判断是否可信。
                    - 信息密度 10：是否有技术细节、场景和取舍。
                    - 客观分析 10：避免营销化空话和无依据结论。
                    - 实践价值 10：是否能给出方法、方案或工程启发。
                    """;
            case HUMOROUS -> """
                    - 趣味性 15：是否有轻松表达、节奏和记忆点。
                    - 信息不失真 10：幽默不牺牲主题和事实。
                    - 分寸感 10：不冒犯、不低俗、不喧宾夺主。
                    - 可读性 10：梗和表达自然，不堆砌。
                    """;
        };
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
        sb.append("- 文章风格：").append(getStyleName(state.getStyle())).append("\n");
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

    private record ImagePlanReviewPayload(String contentWithPlaceholders,
                                          List<ArticleState.ImageRequirement> imageRequirements) {
    }
}
