package com.sxxian.multiagentcreator.agent;

import com.sxxian.multiagentcreator.agent.agents.ContentMergerAgent;
import com.sxxian.multiagentcreator.agent.config.AgentConfig;
import com.sxxian.multiagentcreator.agent.context.StreamHandlerContext;
import com.sxxian.multiagentcreator.annotation.AgentExecution;
import com.sxxian.multiagentcreator.exception.ReviewRejectedException;
import com.sxxian.multiagentcreator.model.dto.article.ArticleContext;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.dto.image.ImageExecutionResult;
import com.sxxian.multiagentcreator.model.enums.SseMessageTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

@Service
@Slf4j
public class ArticleAgentOrchestrator {

    @Resource
    private AgentConfig agentConfig;

    @Resource
    private ArticleAgent articleAgent;

    @Resource
    private ImageAgent imageAgent;

    @Resource
    private ImageToolExecutor imageToolExecutor;

    @Resource
    private ContentMergerAgent contentMergerAgent;

    @AgentExecution(value = "orchestrator_phase1_generate_titles", description = "orchestrator generate title options", phase = "TITLE_GENERATING")
    public void executePhase1_GenerateTitles(ArticleState state, Consumer<String> streamHandler) {
        try {
            ArticleContext context = ArticleContext.fromState(state);
            articleAgent.generateTitles(context).applyToState(state);
            streamHandler.accept(SseMessageTypeEnum.AGENT1_COMPLETE.getValue());
            log.info("orchestrator phase1 completed, taskId={}, titleCount={}",
                    state.getTaskId(), state.getTitleOptions() == null ? 0 : state.getTitleOptions().size());
        } catch (Exception e) {
            rethrowReviewRejected(e);
            log.error("orchestrator phase1 failed, taskId={}", state.getTaskId(), e);
            throw new RuntimeException("title generation failed: " + e.getMessage(), e);
        }
    }

    @AgentExecution(value = "orchestrator_phase2_generate_outline", description = "orchestrator generate outline", phase = "OUTLINE_GENERATING")
    public void executePhase2_GenerateOutline(ArticleState state, Consumer<String> streamHandler) {
        StreamHandlerContext.set(streamHandler);
        try {
            ArticleContext context = ArticleContext.fromState(state);
            articleAgent.generateOutline(context, streamHandler).applyToState(state);
            streamHandler.accept(SseMessageTypeEnum.AGENT2_COMPLETE.getValue());
            log.info("orchestrator phase2 completed, taskId={}, sectionCount={}",
                    state.getTaskId(),
                    state.getOutline() == null || state.getOutline().getSections() == null
                            ? 0 : state.getOutline().getSections().size());
        } catch (Exception e) {
            rethrowReviewRejected(e);
            log.error("orchestrator phase2 failed, taskId={}", state.getTaskId(), e);
            throw new RuntimeException("outline generation failed: " + e.getMessage(), e);
        } finally {
            StreamHandlerContext.clear();
        }
    }

    @AgentExecution(value = "orchestrator_phase3_generate_content", description = "orchestrator generate content and images in parallel", phase = "CONTENT_GENERATING")
    public void executePhase3_GenerateContent(ArticleState state, Consumer<String> streamHandler) {
        StreamHandlerContext.set(streamHandler);
        try {
            executePhase3ParallelFromOutline(state, streamHandler);
        } catch (Exception e) {
            rethrowReviewRejected(e);
            log.error("orchestrator phase3 failed, taskId={}", state.getTaskId(), e);
            throw new RuntimeException("content and image generation failed: " + e.getMessage(), e);
        } finally {
            StreamHandlerContext.clear();
        }
    }

    private void executePhase3ParallelFromOutline(ArticleState state, Consumer<String> streamHandler) {
        ArticleState imageState = copyImagePlanningState(state);

        CompletableFuture<Void> contentFuture = CompletableFuture.runAsync(() -> {
            try {
                ArticleContext context = ArticleContext.fromState(state);
                articleAgent.generateContent(context, streamHandler).applyToState(state);
                streamHandler.accept(SseMessageTypeEnum.AGENT3_COMPLETE.getValue());
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });

        CompletableFuture<ImageExecutionResult> imageFuture = CompletableFuture.supplyAsync(() -> runOutlineImageBranch(imageState, streamHandler));

        contentFuture.join();
        ImageExecutionResult imageExecutionResult = imageFuture.join();

        state.setImageRequirements(imageState.getImageRequirements());
        state.setImagePlanReviewResult(imageState.getImagePlanReviewResult());
        state.setImages(imageExecutionResult.getImages());
        state.setImageReviewResults(imageExecutionResult.getImageReviewResults());
        state.setImageExecutionTraces(imageState.getImageExecutionTraces());
        if (state.getImageExecutionTraces() == null) {
            state.setImageExecutionTraces(new ArrayList<>());
        }
        applyCoverImage(state);

        if (state.getImages() == null || state.getImages().isEmpty()) {
            runContentImageFallback(state, streamHandler);
        } else {
            state.setContent(contentMergerAgent.insertPlaceholdersIntoContent(
                    state.getContent(), state.getImageRequirements()));
            streamHandler.accept(SseMessageTypeEnum.AGENT4_COMPLETE.getValue());
            streamHandler.accept(SseMessageTypeEnum.AGENT5_COMPLETE.getValue());
            state.setFullContent(contentMergerAgent.mergeImagesIntoContent(state.getContent(), state.getImages()));
        }

        streamHandler.accept(SseMessageTypeEnum.MERGE_COMPLETE.getValue());
        log.info("orchestrator phase3 parallel completed, taskId={}, orchestratorEnabled={}, contentLength={}, imageCount={}",
                state.getTaskId(), agentConfig.isOrchestratorEnabled(),
                state.getContent() == null ? 0 : state.getContent().length(),
                state.getImages() == null ? 0 : state.getImages().size());
    }

    private ImageExecutionResult runOutlineImageBranch(ArticleState imageState, Consumer<String> streamHandler) {
        long startedAt = System.currentTimeMillis();
        try {
            ArticleState.Agent4Result plan = imageAgent.planImagesFromOutline(imageState);
            long endedAt = System.currentTimeMillis();
            imageState.setImageExecutionTraces(new ArrayList<>(List.of(planTrace("outline", startedAt, endedAt, "PLAN_SUCCEEDED", null))));
            streamHandler.accept(SseMessageTypeEnum.AGENT4_COMPLETE.getValue());

            ImageExecutionResult result = imageToolExecutor.execute(plan.getImageRequirements(), imageState, streamHandler);
            imageState.getImageExecutionTraces().addAll(result.getTraces());
            streamHandler.accept(SseMessageTypeEnum.AGENT5_COMPLETE.getValue());
            return result;
        } catch (Exception e) {
            long endedAt = System.currentTimeMillis();
            imageState.setImageExecutionTraces(new ArrayList<>(List.of(planTrace("outline", startedAt, endedAt, "PLAN_FAILED", e.getMessage()))));
            log.warn("outline image branch failed, taskId={}, fallbackToContentPlan=true, error={}",
                    imageState.getTaskId(), e.getMessage(), e);
            return ImageExecutionResult.builder()
                    .images(new ArrayList<>())
                    .imageReviewResults(new ArrayList<>())
                    .traces(new ArrayList<>())
                    .fallbackUsed(false)
                    .build();
        }
    }

    private void runContentImageFallback(ArticleState state, Consumer<String> streamHandler) {
        try {
            long startedAt = System.currentTimeMillis();
            ArticleState.Agent4Result plan = imageAgent.planImages(state);
            long endedAt = System.currentTimeMillis();
            state.getImageExecutionTraces().add(planTrace("content", startedAt, endedAt, "PLAN_SUCCEEDED", null));
            streamHandler.accept(SseMessageTypeEnum.AGENT4_COMPLETE.getValue());

            ImageExecutionResult imageExecutionResult = imageToolExecutor.execute(plan.getImageRequirements(), state, streamHandler);
            state.setImages(imageExecutionResult.getImages());
            state.setImageReviewResults(imageExecutionResult.getImageReviewResults());
            state.getImageExecutionTraces().addAll(imageExecutionResult.getTraces());
            applyCoverImage(state);
            streamHandler.accept(SseMessageTypeEnum.AGENT5_COMPLETE.getValue());
            state.setFullContent(contentMergerAgent.mergeImagesIntoContent(state.getContent(), state.getImages()));
        } catch (Exception e) {
            state.getImageExecutionTraces().add(planTrace("content", System.currentTimeMillis(), System.currentTimeMillis(), "PLAN_FAILED", e.getMessage()));
            log.warn("content image fallback failed, keep text only, taskId={}, error={}", state.getTaskId(), e.getMessage(), e);
            state.setFullContent(state.getContent());
        }
    }

    private ArticleState copyImagePlanningState(ArticleState source) {
        ArticleState copy = new ArticleState();
        copy.setTaskId(source.getTaskId());
        copy.setTopic(source.getTopic());
        copy.setPlatform(source.getPlatform());
        copy.setTitle(source.getTitle());
        copy.setOutline(source.getOutline());
        copy.setStyle(source.getStyle());
        copy.setWordRange(source.getWordRange());
        copy.setUserDescription(source.getUserDescription());
        copy.setEnabledImageMethods(source.getEnabledImageMethods());
        return copy;
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

    private void applyCoverImage(ArticleState state) {
        if (state.getImages() == null) {
            return;
        }
        state.getImages().stream()
                .filter(image -> image.getPosition() != null && image.getPosition() == 1)
                .findFirst()
                .ifPresent(image -> state.setCoverImage(image.getUrl()));
    }

    private void rethrowReviewRejected(Exception e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof ReviewRejectedException reviewRejectedException) {
                throw reviewRejectedException;
            }
            current = current.getCause();
        }
    }
}
