package com.sxxian.multiagentcreator.article.application;

import com.sxxian.multiagentcreator.manager.SseEmitterManager;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.dto.review.ImageReviewResult;
import com.sxxian.multiagentcreator.model.dto.review.ReviewResult;
import com.sxxian.multiagentcreator.model.enums.ArticlePhaseEnum;
import com.sxxian.multiagentcreator.model.enums.SseMessageTypeEnum;
import com.sxxian.multiagentcreator.service.ArticleService;
import com.sxxian.multiagentcreator.utils.GsonUtils;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ArticleGenerationEventPublisher {

    @Resource
    private SseEmitterManager sseEmitterManager;

    @Resource
    private ArticleService articleService;

    public void publishAgentMessage(String taskId, String message, ArticleState state) {
        Map<String, Object> data = buildMessageData(message, state);
        if (data != null) {
            sseEmitterManager.send(taskId, GsonUtils.toJson(data));
        }
    }

    public void publishSseMessage(String taskId, SseMessageTypeEnum type, Map<String, Object> additionalData) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", type.getValue());
        data.putAll(additionalData);
        sseEmitterManager.send(taskId, GsonUtils.toJson(data));
    }

    public void publishReviewMessage(String taskId, ArticlePhaseEnum phase, ReviewResult reviewResult) {
        if (reviewResult == null) {
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("phase", phase.getValue());
        data.put("reviewResult", reviewResult);
        publishSseMessage(taskId, SseMessageTypeEnum.REVIEW_COMPLETE, data);
    }

    public void publishImageReviewMessages(String taskId, List<ImageReviewResult> imageReviewResults) {
        if (imageReviewResults == null || imageReviewResults.isEmpty()) {
            return;
        }
        for (ImageReviewResult reviewResult : imageReviewResults) {
            publishReviewMessage(taskId, ArticlePhaseEnum.IMAGE_REVIEWING, reviewResult);
        }
    }

    public void complete(String taskId) {
        sseEmitterManager.complete(taskId);
    }

    private Map<String, Object> buildMessageData(String message, ArticleState state) {
        String streamingPrefix2 = SseMessageTypeEnum.AGENT2_STREAMING.getStreamingPrefix();
        String streamingPrefix3 = SseMessageTypeEnum.AGENT3_STREAMING.getStreamingPrefix();
        String imageCompletePrefix = SseMessageTypeEnum.IMAGE_COMPLETE.getStreamingPrefix();

        if (message.startsWith(streamingPrefix2)) {
            return buildStreamingData(SseMessageTypeEnum.AGENT2_STREAMING, message.substring(streamingPrefix2.length()));
        }

        if (message.startsWith(streamingPrefix3)) {
            return buildStreamingData(SseMessageTypeEnum.AGENT3_STREAMING, message.substring(streamingPrefix3.length()));
        }

        if (message.startsWith(imageCompletePrefix)) {
            return buildImageCompleteData(message.substring(imageCompletePrefix.length()));
        }

        return buildCompleteMessageData(message, state);
    }

    private Map<String, Object> buildStreamingData(SseMessageTypeEnum type, String content) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", type.getValue());
        data.put("content", content);
        return data;
    }

    private Map<String, Object> buildImageCompleteData(String imageJson) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", SseMessageTypeEnum.IMAGE_COMPLETE.getValue());
        data.put("image", GsonUtils.fromJson(imageJson, ArticleState.ImageResult.class));
        return data;
    }

    private Map<String, Object> buildCompleteMessageData(String message, ArticleState state) {
        Map<String, Object> data = new HashMap<>();

        if (SseMessageTypeEnum.AGENT1_COMPLETE.getValue().equals(message)) {
            data.put("type", SseMessageTypeEnum.AGENT1_COMPLETE.getValue());
            data.put("title", state.getTitle());
            data.put("reviewResult", state.getTitleReviewResult());
        } else if (SseMessageTypeEnum.AGENT2_COMPLETE.getValue().equals(message)) {
            data.put("type", SseMessageTypeEnum.AGENT2_COMPLETE.getValue());
            data.put("outline", state.getOutline().getSections());
            data.put("reviewResult", state.getOutlineReviewResult());
        } else if (SseMessageTypeEnum.AGENT3_COMPLETE.getValue().equals(message)) {
            articleService.updatePhase(state.getTaskId(), ArticlePhaseEnum.IMAGE_PLANNING);
            data.put("type", SseMessageTypeEnum.AGENT3_COMPLETE.getValue());
            data.put("reviewResult", state.getContentReviewResult());
        } else if (SseMessageTypeEnum.AGENT4_COMPLETE.getValue().equals(message)) {
            articleService.updatePhase(state.getTaskId(), ArticlePhaseEnum.IMAGE_EXECUTING);
            data.put("type", SseMessageTypeEnum.AGENT4_COMPLETE.getValue());
            data.put("content", state.getContent());
            data.put("imageRequirements", state.getImageRequirements());
            data.put("reviewResult", state.getImagePlanReviewResult());
        } else if (SseMessageTypeEnum.AGENT5_COMPLETE.getValue().equals(message)) {
            articleService.updatePhase(state.getTaskId(), ArticlePhaseEnum.MERGING);
            data.put("type", SseMessageTypeEnum.AGENT5_COMPLETE.getValue());
            data.put("images", state.getImages());
            data.put("imageReviewResults", state.getImageReviewResults());
            data.put("imageExecutionTraces", state.getImageExecutionTraces());
        } else if (SseMessageTypeEnum.MERGE_COMPLETE.getValue().equals(message)) {
            data.put("type", SseMessageTypeEnum.MERGE_COMPLETE.getValue());
            data.put("fullContent", state.getFullContent());
            data.put("images", state.getImages());
            data.put("coverImage", state.getCoverImage());
        } else {
            return null;
        }

        return data;
    }
}
