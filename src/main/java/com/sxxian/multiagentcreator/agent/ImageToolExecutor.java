package com.sxxian.multiagentcreator.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.sxxian.multiagentcreator.agent.context.StreamHandlerContext;
import com.sxxian.multiagentcreator.agent.tools.ImageGenerationTool;
import com.sxxian.multiagentcreator.annotation.AgentExecution;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.dto.image.ImageExecutionResult;
import com.sxxian.multiagentcreator.model.dto.image.ImageObservation;
import com.sxxian.multiagentcreator.model.dto.review.ImageReviewResult;
import com.sxxian.multiagentcreator.model.enums.ImageMethodEnum;
import com.sxxian.multiagentcreator.model.enums.SseMessageTypeEnum;
import com.sxxian.multiagentcreator.utils.GsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
@Slf4j
@RequiredArgsConstructor
public class ImageToolExecutor implements NodeAction {

    public static final String INPUT_IMAGE_REQUIREMENTS = "imageRequirements";
    public static final String OUTPUT_IMAGES = "images";
    public static final String OUTPUT_IMAGE_REVIEW_RESULTS = "imageReviewResults";
    public static final String OUTPUT_IMAGE_EXECUTION_TRACES = "imageExecutionTraces";

    private static final int MAX_REPLAN_ATTEMPTS = 2;

    private final ImageGenerationTool imageGenerationTool;
    private final ReviewAgent reviewAgent;
    private final ImageAgent imageAgent;

    @Override
    @AgentExecution(value = "image_tool_executor", description = "执行图片工具并支持单图重规划", phase = "IMAGE_EXECUTING")
    public Map<String, Object> apply(OverAllState state) {
        List<ArticleState.ImageRequirement> requirements = readRequirements(state);
        ArticleState reviewState = buildReviewState(state);
        ImageExecutionResult result = execute(requirements, reviewState, StreamHandlerContext.get());
        List<ArticleState.ImageExecutionTrace> traces = readTraces(state);
        traces.addAll(result.getTraces());
        return Map.of(
                OUTPUT_IMAGES, result.getImages(),
                OUTPUT_IMAGE_REVIEW_RESULTS, result.getImageReviewResults(),
                OUTPUT_IMAGE_EXECUTION_TRACES, traces
        );
    }

    public ImageExecutionResult execute(List<ArticleState.ImageRequirement> requirements,
                                        ArticleState reviewState,
                                        Consumer<String> streamHandler) {
        if (requirements == null || requirements.isEmpty()) {
            return ImageExecutionResult.builder()
                    .images(new ArrayList<>())
                    .imageReviewResults(new ArrayList<>())
                    .traces(new ArrayList<>())
                    .fallbackUsed(false)
                    .build();
        }

        CopyOnWriteArrayList<ArticleState.ImageResult> images = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<ImageReviewResult> reviewResults = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<ArticleState.ImageExecutionTrace> traces = new CopyOnWriteArrayList<>();

        List<CompletableFuture<Boolean>> futures = requirements.stream()
                .map(requirement -> CompletableFuture.supplyAsync(() ->
                        executeOne(requirement, reviewState, streamHandler, images, reviewResults, traces)))
                .toList();

        boolean fallbackUsed = futures.stream()
                .map(CompletableFuture::join)
                .reduce(false, Boolean::logicalOr);

        images.sort(Comparator.comparing(image -> image.getPosition() == null ? 0 : image.getPosition()));
        return ImageExecutionResult.builder()
                .images(new ArrayList<>(images))
                .imageReviewResults(new ArrayList<>(reviewResults))
                .traces(new ArrayList<>(traces))
                .fallbackUsed(fallbackUsed)
                .build();
    }

    private boolean executeOne(ArticleState.ImageRequirement initialRequirement,
                               ArticleState reviewState,
                               Consumer<String> streamHandler,
                               List<ArticleState.ImageResult> images,
                               List<ImageReviewResult> reviewResults,
                               List<ArticleState.ImageExecutionTrace> traces) {
        ArticleState.ImageRequirement current = initialRequirement;
        ArticleState.ImageResult bestImage = null;
        ImageReviewResult bestReview = null;
        boolean fallbackUsed = false;

        for (int attempt = 0; attempt <= MAX_REPLAN_ATTEMPTS; attempt++) {
            ImageGenerationTool.ImageGenerationResult generationResult = generateWithOneRetry(current);
            ArticleState.ImageExecutionTrace trace = buildTrace(current, generationResult, attempt);

            if (isDiagramToolFallback(current, generationResult)) {
                String error = current.getImageSource() + " rendering failed and fell back to "
                        + ImageMethodEnum.getFallbackMethod().getValue();
                trace.setToolSuccess(false);
                trace.setToolError(error);
                trace.setFinalStatus("TOOL_FAILED");
                traces.add(trace);
                current = replanAfterToolFailure(current, reviewState, buildDiagramReplanError(current, error), attempt);
                if (current == null) {
                    break;
                }
                continue;
            }

            if (!generationResult.isSuccess()) {
                trace.setFinalStatus("TOOL_FAILED");
                traces.add(trace);
                current = replanAfterToolFailure(current, reviewState, generationResult.getError(), attempt);
                if (current == null) {
                    break;
                }
                continue;
            }

            ArticleState.ImageResult imageResult = convertToImageResult(generationResult);
            ImageReviewResult reviewResult = reviewAgent.reviewImageResult(reviewState, current, imageResult);
            applyReviewTrace(trace, reviewResult);
            traces.add(trace);
            reviewResults.add(reviewResult);

            if (bestReview == null || reviewResult.getScore() != null
                    && (bestReview.getScore() == null || reviewResult.getScore() > bestReview.getScore())) {
                bestImage = imageResult;
                bestReview = reviewResult;
            }

            if (reviewResult.isApprovedByThreshold()) {
                images.add(imageResult);
                pushImageComplete(streamHandler, imageResult);
                trace.setFinalStatus("APPROVED");
                log.info("图片评审通过，使用当前配图: taskId={}, position={}, attempt={}, score={}",
                        reviewState.getTaskId(), current.getPosition(), attempt, reviewResult.getScore());
                return fallbackUsed;
            }

            log.info("图片评审未通过，准备重规划: taskId={}, position={}, attempt={}, score={}, nextAction={}",
                    reviewState.getTaskId(), current.getPosition(), attempt, reviewResult.getScore(), reviewResult.getNextAction());
            current = replanAfterReview(current, reviewState, imageResult, reviewResult, attempt);
            if (current == null) {
                break;
            }
        }

        if (bestImage != null) {
            images.add(bestImage);
            pushImageComplete(streamHandler, bestImage);
            log.warn("图片重试后仍未通过评审，使用评审分最高的候选图: taskId={}, position={}, bestScore={}, maxReviewRetries={}, url={}",
                    reviewState.getTaskId(),
                    initialRequirement.getPosition(),
                    bestReview != null ? bestReview.getScore() : null,
                    MAX_REPLAN_ATTEMPTS,
                    bestImage.getUrl());
            markTraceForImage(traces, bestImage, "BEST_EFFORT_AFTER_RETRIES");
            return true;
        }

        ArticleState.ImageResult fallbackImage = fallbackImage(initialRequirement);
        images.add(fallbackImage);
        pushImageComplete(streamHandler, fallbackImage);
        traces.add(fallbackTrace(initialRequirement, fallbackImage));
        return true;
    }

    private ImageGenerationTool.ImageGenerationResult generateWithOneRetry(ArticleState.ImageRequirement requirement) {
        ImageGenerationTool.ImageGenerationResult result = generate(requirement);
        if (result.isSuccess()) {
            return result;
        }
        log.warn("图片工具执行失败，使用同参数重试一次, position={}, source={}, error={}",
                requirement.getPosition(), requirement.getImageSource(), result.getError());
        return generate(requirement);
    }

    private ImageGenerationTool.ImageGenerationResult generate(ArticleState.ImageRequirement requirement) {
        return imageGenerationTool.generateImageDirect(
                requirement.getImageSource(),
                requirement.getKeywords(),
                requirement.getPrompt(),
                requirement.getPosition(),
                requirement.getType(),
                requirement.getSectionTitle(),
                requirement.getPlaceholderId()
        );
    }

    private boolean isDiagramToolFallback(ArticleState.ImageRequirement requirement,
                                          ImageGenerationTool.ImageGenerationResult result) {
        if (requirement == null || result == null || !result.isSuccess()) {
            return false;
        }
        boolean diagramMethod = ImageMethodEnum.GRAPHVIZ.getValue().equals(requirement.getImageSource())
                || ImageMethodEnum.MERMAID.getValue().equals(requirement.getImageSource());
        return diagramMethod && ImageMethodEnum.getFallbackMethod().getValue().equals(result.getMethod());
    }

    private String buildDiagramReplanError(ArticleState.ImageRequirement requirement, String error) {
        if (ImageMethodEnum.GRAPHVIZ.getValue().equals(requirement.getImageSource())) {
            return error + ". Replan by switching imageSource to MERMAID and rewrite prompt as complete Mermaid code.";
        }
        if (ImageMethodEnum.MERMAID.getValue().equals(requirement.getImageSource())) {
            return error + ". Replan by switching imageSource to GRAPHVIZ and rewrite prompt as complete Graphviz DOT code.";
        }
        return error;
    }

    private ArticleState.ImageRequirement replanAfterToolFailure(ArticleState.ImageRequirement requirement,
                                                                ArticleState reviewState,
                                                                String error,
                                                                int attempt) {
        if (attempt >= MAX_REPLAN_ATTEMPTS) {
            return null;
        }
        return imageAgent.replanSingleImage(ImageObservation.builder()
                .taskId(reviewState.getTaskId())
                .topic(reviewState.getTopic())
                .mainTitle(reviewState.getTitle() != null ? reviewState.getTitle().getMainTitle() : null)
                .sectionTitle(requirement.getSectionTitle())
                .requirement(requirement)
                .toolError(error)
                .attempt(attempt)
                .build(), reviewState.getEnabledImageMethods());
    }

    private ArticleState.ImageRequirement replanAfterReview(ArticleState.ImageRequirement requirement,
                                                           ArticleState reviewState,
                                                           ArticleState.ImageResult imageResult,
                                                           ImageReviewResult reviewResult,
                                                           int attempt) {
        if (attempt >= MAX_REPLAN_ATTEMPTS || shouldFallback(reviewResult)) {
            return null;
        }
        ArticleState.ImageRequirement replanned = imageAgent.replanSingleImage(ImageObservation.builder()
                .taskId(reviewState.getTaskId())
                .topic(reviewState.getTopic())
                .mainTitle(reviewState.getTitle() != null ? reviewState.getTitle().getMainTitle() : null)
                .sectionTitle(requirement.getSectionTitle())
                .requirement(requirement)
                .imageResult(imageResult)
                .reviewResult(reviewResult)
                .attempt(attempt)
                .build(), reviewState.getEnabledImageMethods());
        log.info("ImageAgent 已根据图片评审建议重规划单图: taskId={}, position={}, attempt={}, nextAttempt={}, imageSource={}",
                reviewState.getTaskId(), requirement.getPosition(), attempt, attempt + 1, replanned.getImageSource());
        return replanned;
    }

    private boolean shouldFallback(ImageReviewResult reviewResult) {
        return reviewResult != null && "FALLBACK".equalsIgnoreCase(reviewResult.getNextAction());
    }

    private ArticleState.ImageResult fallbackImage(ArticleState.ImageRequirement requirement) {
        ImageGenerationTool.ImageGenerationResult result = imageGenerationTool.generateImageDirect(
                ImageMethodEnum.getFallbackMethod().getValue(),
                "fallback image",
                "",
                requirement.getPosition(),
                requirement.getType(),
                requirement.getSectionTitle(),
                requirement.getPlaceholderId()
        );
        return convertToImageResult(result);
    }

    private ArticleState.ImageExecutionTrace fallbackTrace(ArticleState.ImageRequirement requirement,
                                                          ArticleState.ImageResult imageResult) {
        ArticleState.ImageExecutionTrace trace = new ArticleState.ImageExecutionTrace();
        trace.setPosition(requirement.getPosition());
        trace.setPlaceholderId(requirement.getPlaceholderId());
        trace.setAttempt(MAX_REPLAN_ATTEMPTS + 1);
        trace.setImageSource(ImageMethodEnum.getFallbackMethod().getValue());
        trace.setToolSuccess(true);
        trace.setUrl(imageResult.getUrl());
        trace.setMethod(imageResult.getMethod());
        trace.setFallbackUsed(true);
        trace.setFinalStatus("FALLBACK");
        trace.setPlannedFrom("fallback");
        return trace;
    }

    private ArticleState.ImageExecutionTrace buildTrace(ArticleState.ImageRequirement requirement,
                                                       ImageGenerationTool.ImageGenerationResult result,
                                                       int attempt) {
        ArticleState.ImageExecutionTrace trace = new ArticleState.ImageExecutionTrace();
        trace.setPosition(requirement.getPosition());
        trace.setPlaceholderId(requirement.getPlaceholderId());
        trace.setAttempt(attempt);
        trace.setImageSource(requirement.getImageSource());
        trace.setKeywords(requirement.getKeywords());
        trace.setPrompt(requirement.getPrompt());
        trace.setReason(requirement.getReason());
        trace.setToolSuccess(result.isSuccess());
        trace.setToolError(result.getError());
        trace.setUrl(result.getUrl());
        trace.setMethod(result.getMethod());
        trace.setFallbackUsed(ImageMethodEnum.getFallbackMethod().getValue().equals(result.getMethod()));
        trace.setFinalStatus(result.isSuccess() ? "TOOL_SUCCESS" : "TOOL_FAILED");
        trace.setPlannedFrom("outline");
        return trace;
    }

    private void applyReviewTrace(ArticleState.ImageExecutionTrace trace, ImageReviewResult reviewResult) {
        trace.setReviewScore(reviewResult.getScore());
        trace.setReviewApproved(reviewResult.getApproved());
        trace.setNextAction(reviewResult.getNextAction());
        trace.setObservation(reviewResult.getObservation());
        trace.setRevisionAdvice(reviewResult.getRevisionAdvice());
        trace.setFinalStatus(Boolean.TRUE.equals(reviewResult.getApproved()) ? "APPROVED" : "REPLAN_REQUIRED");
    }

    private void markLastTrace(List<ArticleState.ImageExecutionTrace> traces, Integer position, String status) {
        for (int i = traces.size() - 1; i >= 0; i--) {
            ArticleState.ImageExecutionTrace trace = traces.get(i);
            if (position == null || position.equals(trace.getPosition())) {
                trace.setFinalStatus(status);
                return;
            }
        }
    }

    private void markTraceForImage(List<ArticleState.ImageExecutionTrace> traces,
                                   ArticleState.ImageResult image,
                                   String status) {
        for (int i = traces.size() - 1; i >= 0; i--) {
            ArticleState.ImageExecutionTrace trace = traces.get(i);
            if ((image.getUrl() == null || image.getUrl().equals(trace.getUrl()))
                    && (image.getPlaceholderId() == null || image.getPlaceholderId().equals(trace.getPlaceholderId()))
                    && (image.getPosition() == null || image.getPosition().equals(trace.getPosition()))) {
                trace.setFinalStatus(status);
                return;
            }
        }
        markLastTrace(traces, image.getPosition(), status);
    }

    private void pushImageComplete(Consumer<String> streamHandler, ArticleState.ImageResult imageResult) {
        if (streamHandler != null && imageResult != null) {
            streamHandler.accept(SseMessageTypeEnum.IMAGE_COMPLETE.getStreamingPrefix() + GsonUtils.toJson(imageResult));
        }
    }

    private ArticleState.ImageResult convertToImageResult(ImageGenerationTool.ImageGenerationResult genResult) {
        ArticleState.ImageResult imageResult = new ArticleState.ImageResult();
        imageResult.setPosition(genResult.getPosition());
        imageResult.setUrl(genResult.getUrl());
        imageResult.setMethod(genResult.getMethod());
        imageResult.setKeywords(genResult.getKeywords());
        imageResult.setSectionTitle(genResult.getSectionTitle());
        imageResult.setDescription(genResult.getDescription());
        imageResult.setPlaceholderId(genResult.getPlaceholderId());
        return imageResult;
    }

    @SuppressWarnings("unchecked")
    private List<ArticleState.ImageRequirement> readRequirements(OverAllState state) {
        return state.value(INPUT_IMAGE_REQUIREMENTS)
                .map(v -> {
                    if (!(v instanceof List<?> list)) {
                        return new ArrayList<ArticleState.ImageRequirement>();
                    }
                    if (list.isEmpty()) {
                        return new ArrayList<ArticleState.ImageRequirement>();
                    }
                    if (list.get(0) instanceof ArticleState.ImageRequirement) {
                        return (List<ArticleState.ImageRequirement>) v;
                    }
                    List<ArticleState.ImageRequirement> converted = new ArrayList<>();
                    for (Object item : list) {
                        converted.add(GsonUtils.fromJson(GsonUtils.toJson(item), ArticleState.ImageRequirement.class));
                    }
                    return converted;
                })
                .orElse(new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    private ArticleState buildReviewState(OverAllState state) {
        ArticleState reviewState = new ArticleState();
        state.value("taskId").ifPresent(v -> reviewState.setTaskId(v.toString()));
        state.value("topic").ifPresent(v -> reviewState.setTopic(v.toString()));
        state.value("platform").ifPresent(v -> reviewState.setPlatform(v.toString()));
        state.value("style").ifPresent(v -> reviewState.setStyle(v.toString()));
        state.value("wordRange").ifPresent(v -> reviewState.setWordRange(v.toString()));
        state.value("content").ifPresent(v -> reviewState.setContent(v.toString()));
        state.value("outline").ifPresent(v -> {
            if (v instanceof ArticleState.OutlineResult outlineResult) {
                reviewState.setOutline(outlineResult);
            } else {
                reviewState.setOutline(GsonUtils.fromJson(GsonUtils.toJson(v), ArticleState.OutlineResult.class));
            }
        });
        state.value("enabledImageMethods").ifPresent(v -> {
            if (v instanceof List) {
                reviewState.setEnabledImageMethods((List<String>) v);
            }
        });
        ArticleState.TitleResult title = new ArticleState.TitleResult();
        state.value("mainTitle").ifPresent(v -> title.setMainTitle(v.toString()));
        state.value("subTitle").ifPresent(v -> title.setSubTitle(v.toString()));
        reviewState.setTitle(title);
        return reviewState;
    }

    @SuppressWarnings("unchecked")
    private List<ArticleState.ImageExecutionTrace> readTraces(OverAllState state) {
        return state.value(OUTPUT_IMAGE_EXECUTION_TRACES)
                .map(v -> {
                    if (!(v instanceof List<?> list)) {
                        return new ArrayList<ArticleState.ImageExecutionTrace>();
                    }
                    if (list.isEmpty()) {
                        return new ArrayList<ArticleState.ImageExecutionTrace>();
                    }
                    if (list.get(0) instanceof ArticleState.ImageExecutionTrace) {
                        return new ArrayList<>((List<ArticleState.ImageExecutionTrace>) v);
                    }
                    List<ArticleState.ImageExecutionTrace> converted = new ArrayList<>();
                    for (Object item : list) {
                        converted.add(GsonUtils.fromJson(GsonUtils.toJson(item), ArticleState.ImageExecutionTrace.class));
                    }
                    return converted;
                })
                .orElse(new ArrayList<>());
    }
}
