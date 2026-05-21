package com.sxxian.multiagentcreator.image.planning;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.sxxian.multiagentcreator.annotation.AgentExecution;
import com.sxxian.multiagentcreator.article.review.ReviewAgent;
import com.sxxian.multiagentcreator.constant.PromptConstant;
import com.sxxian.multiagentcreator.exception.ReviewRejectedException;
import com.sxxian.multiagentcreator.exception.StructuredOutputException;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.dto.image.ImageObservation;
import com.sxxian.multiagentcreator.model.dto.review.ReviewResult;
import com.sxxian.multiagentcreator.model.enums.ArticleStyleEnum;
import com.sxxian.multiagentcreator.model.enums.ImageMethodEnum;
import com.sxxian.multiagentcreator.model.enums.StructuredOutputTypeEnum;
import com.sxxian.multiagentcreator.service.JsonStructuredOutputService;
import com.sxxian.multiagentcreator.service.SkillService;
import com.sxxian.multiagentcreator.utils.GsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ImageAgent implements NodeAction {

    public static final String INPUT_TASK_ID = "taskId";
    public static final String INPUT_TOPIC = "topic";
    public static final String INPUT_MAIN_TITLE = "mainTitle";
    public static final String INPUT_SUB_TITLE = "subTitle";
    public static final String INPUT_PLATFORM = "platform";
    public static final String INPUT_STYLE = "style";
    public static final String INPUT_WORD_RANGE = "wordRange";
    public static final String INPUT_USER_DESCRIPTION = "userDescription";
    public static final String INPUT_OUTLINE = "outline";
    public static final String INPUT_CONTENT = "content";
    public static final String INPUT_ENABLED_IMAGE_METHODS = "enabledImageMethods";
    public static final String OUTPUT_CONTENT_WITH_PLACEHOLDERS = "contentWithPlaceholders";
    public static final String OUTPUT_IMAGE_REQUIREMENTS = "imageRequirements";
    public static final String OUTPUT_IMAGE_PLAN_REVIEW_RESULT = "imagePlanReviewResult";
    public static final String OUTPUT_IMAGE_EXECUTION_TRACES = "imageExecutionTraces";

    private final DashScopeChatModel chatModel;
    private final JsonStructuredOutputService jsonStructuredOutputService;
    private final ReviewAgent reviewAgent;
    private final SkillService skillService;

    @Override
    @AgentExecution(value = "image_agent_plan", description = "ImageAgent plan images from outline", phase = "IMAGE_PLANNING")
    public Map<String, Object> apply(OverAllState state) {
        ArticleState reviewState = buildState(state);
        long startedAt = System.currentTimeMillis();
        try {
            ArticleState.Agent4Result plan = planImagesFromOutline(reviewState);
            long endedAt = System.currentTimeMillis();
            return Map.of(
                    OUTPUT_IMAGE_REQUIREMENTS, plan.getImageRequirements(),
                    OUTPUT_IMAGE_PLAN_REVIEW_RESULT, reviewState.getImagePlanReviewResult(),
                    OUTPUT_IMAGE_EXECUTION_TRACES, List.of(planTrace("outline", startedAt, endedAt, "PLAN_SUCCEEDED", null))
            );
        } catch (Exception e) {
            long endedAt = System.currentTimeMillis();
            log.warn("ImageAgent outline image planning failed, taskId={}, fallbackToContentPlan=true, error={}",
                    reviewState.getTaskId(), e.getMessage(), e);
            return Map.of(
                    OUTPUT_IMAGE_REQUIREMENTS, List.of(),
                    OUTPUT_IMAGE_EXECUTION_TRACES, List.of(planTrace("outline", startedAt, endedAt, "PLAN_FAILED", e.getMessage()))
            );
        }
    }

    public ArticleState.Agent4Result planImagesFromOutline(ArticleState state) {
        int estimatedContentLength = estimateContentLengthFromOutline(state.getWordRange(), state.getOutline());
        List<String> enabledMethods = state.getEnabledImageMethods();
        String prompt = PromptConstant.AGENT4_OUTLINE_IMAGE_REQUIREMENTS_PROMPT
                .replace("{topic}", nullToEmpty(state.getTopic()))
                .replace("{mainTitle}", state.getTitle() != null ? nullToEmpty(state.getTitle().getMainTitle()) : "")
                .replace("{subTitle}", state.getTitle() != null ? nullToEmpty(state.getTitle().getSubTitle()) : "")
                .replace("{userDescription}", nullToEmpty(state.getUserDescription()))
                .replace("{style}", skillService.resolve(state.getPlatform(), state.getStyle()).getDisplayName())
                .replace("{wordRange}", nullToEmpty(state.getWordRange()))
                .replace("{outline}", GsonUtils.toJson(state.getOutline()))
                .replace("{imageCountGuide}", buildImageCountGuide(state.getPlatform(), state.getWordRange(), state.getStyle(), estimatedContentLength))
                .replace("{availableMethods}", buildAvailableMethodsDescription(enabledMethods))
                .replace("{methodUsageGuide}", buildMethodUsageGuide(enabledMethods)
                        + "\n写作 Skill 图片建议：\n"
                        + skillService.resolve(state.getPlatform(), state.getStyle()).getImageGuidance());

        ArticleState.Agent4Result result = generateOutlineImagePlan(prompt);
        List<ArticleState.ImageRequirement> requirements = validateAndFilterImageRequirements(
                result.getImageRequirements(), enabledMethods, state.getPlatform(), state.getWordRange(), state.getStyle(), estimatedContentLength);
        normalizeOutlineRequirements(requirements);
        result.setImageRequirements(requirements);

        ArticleState reviewState = copyReviewState(state, buildOutlineReviewContent(state), requirements);
        ReviewResult reviewResult = reviewAgent.reviewImagePlan(reviewState);
        if (!reviewResult.isApprovedByThreshold()) {
            ArticleState.Agent4Result revised = generateOutlineImagePlan(prompt + reviewAgent.buildRevisionAdvice(reviewResult));
            requirements = validateAndFilterImageRequirements(
                    revised.getImageRequirements(), enabledMethods, state.getPlatform(), state.getWordRange(), state.getStyle(), estimatedContentLength);
            normalizeOutlineRequirements(requirements);
            revised.setImageRequirements(requirements);
            result = revised;
            reviewState = copyReviewState(state, buildOutlineReviewContent(state), requirements);
            reviewResult = reviewAgent.reviewImagePlan(reviewState);
            if (!reviewResult.isApprovedByThreshold()) {
                throw new ReviewRejectedException("IMAGE_REVIEWING", reviewResult);
            }
        }

        state.setImageRequirements(requirements);
        state.setImagePlanReviewResult(reviewResult);
        log.info("ImageAgent outline image planning completed, taskId={}, estimatedContentLength={}, wordRange={}, style={}, imageCount={}",
                state.getTaskId(), estimatedContentLength, state.getWordRange(), state.getStyle(), requirements.size());
        return result;
    }

    public ArticleState.Agent4Result planImages(ArticleState state) {
        String content = state.getContent();
        int contentLength = content == null ? 0 : content.length();
        List<String> enabledMethods = state.getEnabledImageMethods();
        String prompt = PromptConstant.AGENT4_IMAGE_REQUIREMENTS_PROMPT
                .replace("{mainTitle}", state.getTitle() != null ? nullToEmpty(state.getTitle().getMainTitle()) : "")
                .replace("{style}", skillService.resolve(state.getPlatform(), state.getStyle()).getDisplayName())
                .replace("{wordRange}", nullToEmpty(state.getWordRange()))
                .replace("{contentLength}", String.valueOf(contentLength))
                .replace("{content}", nullToEmpty(content))
                .replace("{imageCountGuide}", buildImageCountGuide(state.getPlatform(), state.getWordRange(), state.getStyle(), contentLength))
                .replace("{availableMethods}", buildAvailableMethodsDescription(enabledMethods))
                .replace("{methodUsageGuide}", buildMethodUsageGuide(enabledMethods)
                        + "\n写作 Skill 图片建议：\n"
                        + skillService.resolve(state.getPlatform(), state.getStyle()).getImageGuidance());

        ArticleState.Agent4Result result = generateImagePlan(prompt);
        List<ArticleState.ImageRequirement> requirements = validateAndFilterImageRequirements(
                result.getImageRequirements(), enabledMethods, state.getPlatform(), state.getWordRange(), state.getStyle(), contentLength);
        result.setImageRequirements(requirements);

        ArticleState reviewState = copyReviewState(state, result.getContentWithPlaceholders(), requirements);
        ReviewResult reviewResult = reviewAgent.reviewImagePlan(reviewState);
        if (!reviewResult.isApprovedByThreshold()) {
            ArticleState.Agent4Result revised = generateImagePlan(prompt + reviewAgent.buildRevisionAdvice(reviewResult));
            requirements = validateAndFilterImageRequirements(
                    revised.getImageRequirements(), enabledMethods, state.getPlatform(), state.getWordRange(), state.getStyle(), contentLength);
            revised.setImageRequirements(requirements);
            result = revised;
            reviewState = copyReviewState(state, result.getContentWithPlaceholders(), requirements);
            reviewResult = reviewAgent.reviewImagePlan(reviewState);
            if (!reviewResult.isApprovedByThreshold()) {
                throw new ReviewRejectedException("IMAGE_REVIEWING", reviewResult);
            }
        }

        state.setContent(result.getContentWithPlaceholders());
        state.setImageRequirements(requirements);
        state.setImagePlanReviewResult(reviewResult);
        log.info("ImageAgent content image planning completed, taskId={}, contentLength={}, wordRange={}, style={}, imageCount={}",
                state.getTaskId(), contentLength, state.getWordRange(), state.getStyle(), requirements.size());
        return result;
    }

    @AgentExecution(value = "image_agent_replan_single", description = "ImageAgent replan single image from observation", phase = "IMAGE_REPLANNING")
    public ArticleState.ImageRequirement replanSingleImage(ImageObservation observation, List<String> enabledMethods) {
        ArticleState.ImageRequirement original = observation.getRequirement();
        String prompt = PromptConstant.IMAGE_REPLAN_PROMPT
                .replace("{topic}", nullToEmpty(observation.getTopic()))
                .replace("{mainTitle}", nullToEmpty(observation.getMainTitle()))
                .replace("{sectionTitle}", nullToEmpty(observation.getSectionTitle()))
                .replace("{availableMethods}", buildAvailableMethodsDescription(enabledMethods))
                .replace("{imageRequirement}", safeJson(original))
                .replace("{imageResult}", safeJson(observation.getImageResult()))
                .replace("{observation}", nullToEmpty(buildObservationText(observation)))
                .replace("{revisionAdvice}", observation.getReviewResult() != null
                        ? nullToEmpty(observation.getReviewResult().getRevisionAdvice())
                        : nullToEmpty(observation.getToolError()));

        ArticleState.ImageRequirement replanned = parseImageRequirement(callLlm(prompt));
        normalizeReplannedRequirement(original, replanned, enabledMethods);
        return replanned;
    }

    public static int maxImageCount(String wordRange, String style, int contentLength) {
        return maxImageCount(null, wordRange, style, contentLength);
    }

    public static int maxImageCount(String platform, String wordRange, String style, int contentLength) {
        if ("weibo".equals(platform)) {
            return 1;
        }
        if ("xiaohongshu".equals(platform) && !"long".equals(wordRange)) {
            return contentLength < 600 ? 2 : 4;
        }
        boolean marketing = ArticleStyleEnum.MARKETING.getValue().equals(style);
        if ("short".equals(wordRange) || contentLength < 600 || marketing && contentLength < 1000) {
            return marketing ? 1 : 2;
        }
        if ("long".equals(wordRange) || contentLength > 1800) {
            return 5;
        }
        return 3;
    }

    private ArticleState.Agent4Result generateImagePlan(String prompt) {
        return jsonStructuredOutputService.parse(callLlm(prompt), ArticleState.Agent4Result.class,
                StructuredOutputTypeEnum.IMAGE_PLAN, () -> callLlm(prompt), 1);
    }

    private ArticleState.Agent4Result generateOutlineImagePlan(String prompt) {
        return jsonStructuredOutputService.parse(callLlm(prompt), ArticleState.Agent4Result.class,
                StructuredOutputTypeEnum.OUTLINE_IMAGE_PLAN, () -> callLlm(prompt), 1);
    }

    private ArticleState.ImageRequirement parseImageRequirement(String raw) {
        try {
            JsonElement json = JsonParser.parseString(extractObject(raw));
            ArticleState.ImageRequirement requirement = GsonUtils.fromJson(json.toString(), ArticleState.ImageRequirement.class);
            if (requirement == null || isBlank(requirement.getImageSource()) || isBlank(requirement.getReason())) {
                throw new StructuredOutputException("replanned image requirement requires imageSource and reason");
            }
            return requirement;
        } catch (Exception e) {
            if (e instanceof StructuredOutputException structuredOutputException) {
                throw structuredOutputException;
            }
            throw new StructuredOutputException("failed to parse replanned image requirement: " + e.getMessage(), e);
        }
    }

    private String extractObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new StructuredOutputException("replanned image requirement cannot be empty");
        }
        String content = raw.trim();
        if (content.startsWith("```")) {
            int firstLineEnd = content.indexOf('\n');
            int lastFence = content.lastIndexOf("```");
            if (firstLineEnd >= 0 && lastFence > firstLineEnd) {
                content = content.substring(firstLineEnd + 1, lastFence).trim();
            }
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new StructuredOutputException("replanned image requirement JSON object not found");
        }
        return content.substring(start, end + 1);
    }

    private void normalizeReplannedRequirement(ArticleState.ImageRequirement original,
                                               ArticleState.ImageRequirement replanned,
                                               List<String> enabledMethods) {
        replanned.setPosition(original.getPosition());
        replanned.setType(isBlank(replanned.getType()) ? original.getType() : replanned.getType());
        replanned.setSectionTitle(isBlank(replanned.getSectionTitle()) ? original.getSectionTitle() : replanned.getSectionTitle());
        replanned.setPlaceholderId(original.getPlaceholderId());
        replanned.setRetryCount(original.getRetryCount() == null ? 1 : original.getRetryCount() + 1);
        if (!isAllowed(replanned.getImageSource(), enabledMethods)) {
            replanned.setImageSource(firstAllowedMethod(enabledMethods));
        }
        ImageMethodEnum method = ImageMethodEnum.getByValue(replanned.getImageSource());
        if (method != null && method.isAiGenerated() && isBlank(replanned.getPrompt())) {
            replanned.setPrompt(original.getPrompt());
        }
        if (method != null && !method.isAiGenerated() && !method.isFallback() && isBlank(replanned.getKeywords())) {
            replanned.setKeywords(original.getKeywords());
        }
    }

    private List<ArticleState.ImageRequirement> validateAndFilterImageRequirements(
            List<ArticleState.ImageRequirement> requirements,
            List<String> enabledMethods,
            String platform,
            String wordRange,
            String style,
            int contentLength) {
        if (requirements == null || requirements.isEmpty()) {
            return List.of();
        }
        int maxCount = maxImageCount(platform, wordRange, style, contentLength);
        List<ArticleState.ImageRequirement> validated = new ArrayList<>();
        for (ArticleState.ImageRequirement requirement : requirements) {
            if (validated.size() >= maxCount) {
                break;
            }
            if (!isAllowed(requirement.getImageSource(), enabledMethods)) {
                requirement.setImageSource(firstAllowedMethod(enabledMethods));
            }
            if (isBlank(requirement.getReason())) {
                requirement.setReason("Image reason auto-filled from article length, section position, and image method");
            }
            if (requirement.getRetryCount() == null) {
                requirement.setRetryCount(0);
            }
            validated.add(requirement);
        }
        return validated;
    }

    private void normalizeOutlineRequirements(List<ArticleState.ImageRequirement> requirements) {
        if (requirements == null) {
            return;
        }
        int imagePlaceholderIndex = 1;
        int iconPlaceholderIndex = 1;
        for (ArticleState.ImageRequirement requirement : requirements) {
            if (requirement.getPosition() == null || requirement.getPosition() <= 1) {
                requirement.setPlaceholderId("");
                continue;
            }
            if (isBlank(requirement.getPlaceholderId())) {
                if ("ICONIFY".equals(requirement.getImageSource())) {
                    requirement.setPlaceholderId("{{ICON_PLACEHOLDER_" + iconPlaceholderIndex++ + "}}");
                } else {
                    requirement.setPlaceholderId("{{IMAGE_PLACEHOLDER_" + imagePlaceholderIndex++ + "}}");
                }
            }
        }
    }

    private int estimateContentLengthFromOutline(String wordRange, ArticleState.OutlineResult outline) {
        if ("short".equals(wordRange)) {
            return 500;
        }
        if ("long".equals(wordRange)) {
            return 2200;
        }
        if ("medium".equals(wordRange)) {
            return 1200;
        }
        int sections = outline == null || outline.getSections() == null ? 0 : outline.getSections().size();
        if (sections <= 3) {
            return 500;
        }
        if (sections >= 6) {
            return 2200;
        }
        return 1200;
    }

    private String buildOutlineReviewContent(ArticleState state) {
        return "title=" + (state.getTitle() != null ? nullToEmpty(state.getTitle().getMainTitle()) : "")
                + "\noutline=" + GsonUtils.toJson(state.getOutline());
    }

    private ArticleState.ImageExecutionTrace planTrace(String plannedFrom, long startedAt, long endedAt,
                                                       String status, String observation) {
        ArticleState.ImageExecutionTrace trace = new ArticleState.ImageExecutionTrace();
        trace.setPlannedFrom(plannedFrom);
        trace.setPlanStartedAt(startedAt);
        trace.setPlanEndedAt(endedAt);
        trace.setFinalStatus(status);
        trace.setObservation(observation);
        return trace;
    }

    private ArticleState buildState(OverAllState state) {
        ArticleState articleState = new ArticleState();
        state.value(INPUT_TASK_ID).ifPresent(v -> articleState.setTaskId(v.toString()));
        state.value(INPUT_TOPIC).ifPresent(v -> articleState.setTopic(v.toString()));
        state.value(INPUT_PLATFORM).ifPresent(v -> articleState.setPlatform(v.toString()));
        state.value(INPUT_STYLE).ifPresent(v -> articleState.setStyle(v.toString()));
        state.value(INPUT_WORD_RANGE).ifPresent(v -> articleState.setWordRange(v.toString()));
        state.value(INPUT_USER_DESCRIPTION).ifPresent(v -> articleState.setUserDescription(v.toString()));
        state.value(INPUT_CONTENT).ifPresent(v -> articleState.setContent(v.toString()));
        state.value(INPUT_OUTLINE).ifPresent(v -> {
            if (v instanceof ArticleState.OutlineResult outlineResult) {
                articleState.setOutline(outlineResult);
            } else {
                articleState.setOutline(GsonUtils.fromJson(GsonUtils.toJson(v), ArticleState.OutlineResult.class));
            }
        });
        @SuppressWarnings("unchecked")
        List<String> enabledMethods = state.value(INPUT_ENABLED_IMAGE_METHODS)
                .map(v -> v instanceof List ? (List<String>) v : null)
                .orElse(null);
        articleState.setEnabledImageMethods(enabledMethods);
        ArticleState.TitleResult title = new ArticleState.TitleResult();
        state.value(INPUT_MAIN_TITLE).ifPresent(v -> title.setMainTitle(v.toString()));
        state.value(INPUT_SUB_TITLE).ifPresent(v -> title.setSubTitle(v.toString()));
        articleState.setTitle(title);
        return articleState;
    }

    private ArticleState copyReviewState(ArticleState source, String content,
                                         List<ArticleState.ImageRequirement> requirements) {
        ArticleState reviewState = new ArticleState();
        reviewState.setTaskId(source.getTaskId());
        reviewState.setTopic(source.getTopic());
        reviewState.setPlatform(source.getPlatform());
        reviewState.setStyle(source.getStyle());
        reviewState.setWordRange(source.getWordRange());
        reviewState.setTitle(source.getTitle());
        reviewState.setOutline(source.getOutline());
        reviewState.setUserDescription(source.getUserDescription());
        reviewState.setContent(content);
        reviewState.setImageRequirements(requirements);
        return reviewState;
    }

    private String buildImageCountGuide(String platform, String wordRange, String style, int contentLength) {
        int maxCount = maxImageCount(platform, wordRange, style, contentLength);
        if ("weibo".equals(platform)) {
            return "Weibo content should default to 0-1 image. Generate only one cover or information image when it clearly improves sharing.";
        }
        if ("xiaohongshu".equals(platform)) {
            return "Xiaohongshu notes may use 1-4 useful images, prioritizing cover, scene, steps, and checklist visuals. Do not add low-value filler images.";
        }
        if (maxCount <= 1) {
            return "Short or marketing-oriented content should default to one cover image. Add a section image only for a clear high-value conversion section.";
        }
        if (maxCount == 2) {
            return "Short content should generate one cover image and at most one key section image. Total images must not exceed 2.";
        }
        if (maxCount == 3) {
            return "Medium-length content should generate 2-3 images, prioritizing the cover and the most important sections. Total images must not exceed 3.";
        }
        return "Long content may generate 3-5 images, prioritizing the cover, key structure diagrams, and high-value sections. Total images must not exceed 5.";
    }

    private String buildAvailableMethodsDescription(List<String> enabledMethods) {
        List<String> methods = enabledMethods == null || enabledMethods.isEmpty()
                ? List.of("CHINA_IMAGE_SEARCH", "PEXELS", "QWEN_IMAGE", "GRAPHVIZ", "MERMAID", "ICONIFY", "EMOJI_PACK", "SVG_DIAGRAM")
                : enabledMethods;
        StringBuilder sb = new StringBuilder();
        for (String method : methods) {
            ImageMethodEnum methodEnum = ImageMethodEnum.getByValue(method);
            if (methodEnum != null && !methodEnum.isFallback()) {
                sb.append("- ").append(methodEnum.getValue()).append(": ")
                        .append(methodEnum.getDescription()).append("\n");
            }
        }
        return sb.toString();
    }

    private String buildMethodUsageGuide(List<String> enabledMethods) {
        List<String> methods = enabledMethods == null || enabledMethods.isEmpty()
                ? List.of("CHINA_IMAGE_SEARCH", "PEXELS", "QWEN_IMAGE", "GRAPHVIZ", "MERMAID", "ICONIFY", "EMOJI_PACK", "SVG_DIAGRAM")
                : enabledMethods;
        StringBuilder sb = new StringBuilder();
        for (String method : methods) {
            sb.append("- ").append(method).append(": ");
            ImageMethodEnum methodEnum = ImageMethodEnum.getByValue(method);
            if (methodEnum == null) {
                sb.append("Unknown method. Avoid using it.\n");
            } else if (ImageMethodEnum.CHINA_IMAGE_SEARCH == methodEnum) {
                sb.append("Use Chinese keywords. Prefer this for Chinese movies, animation/IP characters, celebrities, brands, products, locations, hot topics, official posters, stills, screenshots, or any concrete real-world entity. Include the exact entity name plus words such as official poster, still, character image, release, or scene. Prompt may be empty.\n");
            } else if (ImageMethodEnum.GRAPHVIZ == methodEnum) {
                sb.append("Use prompt with complete Graphviz DOT code only. Prefer this for flowcharts and architecture diagrams. Use digraph, rankdir=LR, no markdown fence, no prose. Keep labels concise, split long Chinese labels with \\n, avoid more than 8 nodes, avoid huge blank space, and make the diagram readable as an article-width SVG. Keywords may be empty.\n");
            } else if (ImageMethodEnum.MERMAID == methodEnum) {
                sb.append("Use prompt with complete Mermaid code. Use as fallback when Graphviz is unavailable or unsuitable. Keywords may be empty.\n");
            } else if (methodEnum.isAiGenerated()) {
                sb.append("Use prompt. Keywords may be empty.\n");
            } else {
                sb.append("Use keywords. Prompt may be empty.\n");
            }
        }
        return sb.toString();
    }

    private String buildObservationText(ImageObservation observation) {
        if (observation.getReviewResult() != null) {
            return nullToEmpty(observation.getReviewResult().getObservation());
        }
        return nullToEmpty(observation.getToolError());
    }

    private String firstAllowedMethod(List<String> enabledMethods) {
        if (enabledMethods != null && !enabledMethods.isEmpty()) {
            return enabledMethods.get(0);
        }
        return ImageMethodEnum.getDefaultSearchMethod().getValue();
    }

    private boolean isAllowed(String imageSource, List<String> enabledMethods) {
        if (ImageMethodEnum.getByValue(imageSource) == null) {
            return false;
        }
        return enabledMethods == null || enabledMethods.isEmpty() || enabledMethods.contains(imageSource);
    }

    private String callLlm(String prompt) {
        ChatResponse response = chatModel.call(new Prompt(new UserMessage(prompt)));
        return response.getResult().getOutput().getText();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String safeJson(Object value) {
        if (value == null) {
            return "{}";
        }
        return nullToEmpty(GsonUtils.toJson(value));
    }
}
