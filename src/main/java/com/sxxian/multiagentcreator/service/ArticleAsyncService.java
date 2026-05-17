package com.sxxian.multiagentcreator.service;

import com.google.gson.reflect.TypeToken;
import com.sxxian.multiagentcreator.agent.ArticleAgentOrchestrator;
import com.sxxian.multiagentcreator.agent.config.AgentConfig;
import com.sxxian.multiagentcreator.exception.ReviewRejectedException;
import com.sxxian.multiagentcreator.manager.SseEmitterManager;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.dto.review.ImageReviewResult;
import com.sxxian.multiagentcreator.model.dto.review.ReviewResult;
import com.sxxian.multiagentcreator.model.entity.Article;
import com.sxxian.multiagentcreator.model.enums.ArticlePhaseEnum;
import com.sxxian.multiagentcreator.model.enums.ArticleStatusEnum;
import com.sxxian.multiagentcreator.model.enums.SseMessageTypeEnum;
import com.sxxian.multiagentcreator.utils.GsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * 文章异步任务服务
 *
 * 支持两种执行模式：
 * 1. 多智能体编排模式（通过 article.agent.orchestrator.enabled=true 启用）
 * 2. 原有模式（默认或 article.agent.orchestrator.enabled=false）
 *
 */
@Service
@Slf4j
public class ArticleAsyncService {

    @Resource
    private ArticleAgentService articleAgentService;

    @Resource
    private ArticleAgentOrchestrator articleAgentOrchestrator;

    @Resource
    private AgentConfig agentConfig;

    @Resource
    private SseEmitterManager sseEmitterManager;

    @Resource
    private ArticleService articleService;

    /**
     * 阶段1：异步生成标题方案
     *
     * @param taskId 任务ID
     * @param topic  选题
     * @param style  文章风格（可为空）
     * @param wordRange 字数范围（可为空）
     */
    @Async("articleExecutor")
    public void executePhase1(String taskId, String topic, String style, String wordRange) {
        boolean useOrchestrator = agentConfig.isOrchestratorEnabled();
        log.info("阶段1异步任务开始, taskId={}, topic={}, style={}, wordRange={}, 使用多智能体编排={}",
                taskId, topic, style, wordRange, useOrchestrator);

        try {
            // 更新状态和阶段
            articleService.updateArticleStatus(taskId, ArticleStatusEnum.PROCESSING, null);
            articleService.updatePhase(taskId, ArticlePhaseEnum.TITLE_GENERATING);

            // 创建状态对象
            ArticleState state = new ArticleState();
            state.setTaskId(taskId);
            state.setTopic(topic);
            state.setStyle(style);
            state.setWordRange(wordRange);

            // 执行阶段1：生成标题方案（根据配置选择执行方式）
            if (useOrchestrator) {
                articleAgentOrchestrator.executePhase1_GenerateTitles(state, message -> {
                    handleAgentMessage(taskId, message, state);
                });
            } else {
                articleAgentService.executePhase1_GenerateTitles(state, message -> {
                    handleAgentMessage(taskId, message, state);
                });
            }
            sendReviewMessage(taskId, ArticlePhaseEnum.TITLE_REVIEWING, state.getTitleReviewResult());

            // 保存标题方案到数据库
            articleService.saveTitleOptions(taskId, state.getTitleOptions());

            // 更新阶段为等待用户确认标题
            articleService.updatePhase(taskId, ArticlePhaseEnum.TITLE_WAITING_USER_CONFIRM);

            // 推送标题方案生成完成消息
            Map<String, Object> data = new HashMap<>();
            data.put("titleOptions", state.getTitleOptions());
            sendSseMessage(taskId, SseMessageTypeEnum.TITLES_GENERATED, data);

            log.info("阶段1异步任务完成, taskId={}", taskId);
        } catch (ReviewRejectedException e) {
            handleReviewRejected(taskId, e);
        } catch (Exception e) {
            log.error("阶段1异步任务失败, taskId={}", taskId, e);

            // 更新状态为失败
            articleService.updateArticleStatus(taskId, ArticleStatusEnum.FAILED, e.getMessage());
            articleService.updatePhase(taskId, ArticlePhaseEnum.FAILED);

            // 推送错误消息
            sendSseMessage(taskId, SseMessageTypeEnum.ERROR, Map.of("message", e.getMessage()));

            // 完成 SSE 连接
            sseEmitterManager.complete(taskId);
        }
    }

    /**
     * 阶段2：异步生成大纲（用户确认标题后调用）
     *
     * @param taskId 任务ID
     */
    @Async("articleExecutor")
    public void executePhase2(String taskId) {
        boolean useOrchestrator = agentConfig.isOrchestratorEnabled();
        log.info("阶段2异步任务开始, taskId={}, 使用多智能体编排={}", taskId, useOrchestrator);

        try {
            // 获取文章信息
            Article article = articleService.getByTaskId(taskId);
            if (article == null) {
                throw new RuntimeException("文章不存在");
            }

            // 创建状态对象
            articleService.updatePhase(taskId, ArticlePhaseEnum.OUTLINE_GENERATING);
            ArticleState state = new ArticleState();
            state.setTaskId(taskId);
            state.setTopic(article.getTopic());
            state.setStyle(article.getStyle());
            state.setWordRange(article.getWordRange());
            state.setUserDescription(article.getUserDescription());

            // 设置标题
            ArticleState.TitleResult title = new ArticleState.TitleResult();
            title.setMainTitle(article.getMainTitle());
            title.setSubTitle(article.getSubTitle());
            state.setTitle(title);

            // 执行阶段2：生成大纲（根据配置选择执行方式）
            if (useOrchestrator) {
                articleAgentOrchestrator.executePhase2_GenerateOutline(state, message -> {
                    handleAgentMessage(taskId, message, state);
                });
            } else {
                articleAgentService.executePhase2_GenerateOutline(state, message -> {
                    handleAgentMessage(taskId, message, state);
                });
            }
            sendReviewMessage(taskId, ArticlePhaseEnum.OUTLINE_REVIEWING, state.getOutlineReviewResult());

            // 保存大纲到数据库
            Article articleToUpdate = articleService.getByTaskId(taskId);
            articleToUpdate.setOutline(GsonUtils.toJson(state.getOutline().getSections()));
            articleService.updateById(articleToUpdate);

            // 更新阶段为等待用户确认大纲
            articleService.updatePhase(taskId, ArticlePhaseEnum.OUTLINE_WAITING_USER_CONFIRM);

            // 推送大纲生成完成消息
            Map<String, Object> data = new HashMap<>();
            data.put("outline", state.getOutline().getSections());
            sendSseMessage(taskId, SseMessageTypeEnum.OUTLINE_GENERATED, data);

            log.info("阶段2异步任务完成, taskId={}", taskId);
        } catch (ReviewRejectedException e) {
            handleReviewRejected(taskId, e);
        } catch (Exception e) {
            log.error("阶段2异步任务失败, taskId={}", taskId, e);

            // 更新状态为失败
            articleService.updateArticleStatus(taskId, ArticleStatusEnum.FAILED, e.getMessage());
            articleService.updatePhase(taskId, ArticlePhaseEnum.FAILED);

            // 推送错误消息
            sendSseMessage(taskId, SseMessageTypeEnum.ERROR, Map.of("message", e.getMessage()));

            // 完成 SSE 连接
            sseEmitterManager.complete(taskId);
        }
    }

    /**
     * 阶段3：异步生成正文+配图（用户确认大纲后调用）
     *
     * @param taskId 任务ID
     */
    @Async("articleExecutor")
    public void executePhase3(String taskId) {
        boolean useOrchestrator = agentConfig.isOrchestratorEnabled();
        log.info("阶段3异步任务开始, taskId={}, 使用多智能体编排={}", taskId, useOrchestrator);

        try {
            // 获取文章信息
            Article article = articleService.getByTaskId(taskId);
            if (article == null) {
                throw new RuntimeException("文章不存在");
            }

            // 创建状态对象
            articleService.updatePhase(taskId, ArticlePhaseEnum.CONTENT_GENERATING);
            ArticleState state = new ArticleState();
            state.setTaskId(taskId);
            state.setTopic(article.getTopic());
            state.setStyle(article.getStyle());
            state.setWordRange(article.getWordRange());
            state.setUserDescription(article.getUserDescription());

            // 从数据库获取允许的配图方式
            List<String> enabledMethods = null;
            if (article.getEnabledImageMethods() != null) {
                enabledMethods = GsonUtils.fromJson(
                        article.getEnabledImageMethods(),
                        new TypeToken<List<String>>(){}
                );
            }
            state.setEnabledImageMethods(enabledMethods);

            // 设置标题
            ArticleState.TitleResult title = new ArticleState.TitleResult();
            title.setMainTitle(article.getMainTitle());
            title.setSubTitle(article.getSubTitle());
            state.setTitle(title);

            // 设置大纲
            List<ArticleState.OutlineSection> outlineSections = GsonUtils.fromJson(
                    article.getOutline(),
                    new TypeToken<List<ArticleState.OutlineSection>>(){}
            );
            ArticleState.OutlineResult outlineResult = new ArticleState.OutlineResult();
            outlineResult.setSections(outlineSections);
            state.setOutline(outlineResult);

            // 执行阶段3：生成正文+配图（根据配置选择执行方式）
            // 多智能体编排模式支持配图并行生成
            if (useOrchestrator) {
                articleAgentOrchestrator.executePhase3_GenerateContent(state, message -> {
                    handleAgentMessage(taskId, message, state);
                });
            } else {
                articleAgentService.executePhase3_GenerateContent(state, message -> {
                    handleAgentMessage(taskId, message, state);
                });
            }
            sendReviewMessage(taskId, ArticlePhaseEnum.CONTENT_REVIEWING, state.getContentReviewResult());
            sendReviewMessage(taskId, ArticlePhaseEnum.IMAGE_REVIEWING, state.getImagePlanReviewResult());
            sendImageReviewMessages(taskId, state.getImageReviewResults());

            // 保存完整文章到数据库
            articleService.saveArticleContent(taskId, state);

            // 更新状态为已完成
            articleService.updateArticleStatus(taskId, ArticleStatusEnum.COMPLETED, null);
            articleService.updatePhase(taskId, ArticlePhaseEnum.COMPLETED);

            // 推送完成消息
            sendSseMessage(taskId, SseMessageTypeEnum.ALL_COMPLETE, Map.of("taskId", taskId));

            // 完成 SSE 连接
            sseEmitterManager.complete(taskId);

            log.info("阶段3异步任务完成, taskId={}", taskId);
        } catch (ReviewRejectedException e) {
            handleReviewRejected(taskId, e);
        } catch (Exception e) {
            log.error("阶段3异步任务失败, taskId={}", taskId, e);

            // 更新状态为失败
            articleService.updateArticleStatus(taskId, ArticleStatusEnum.FAILED, e.getMessage());
            articleService.updatePhase(taskId, ArticlePhaseEnum.FAILED);

            // 推送错误消息
            sendSseMessage(taskId, SseMessageTypeEnum.ERROR, Map.of("message", e.getMessage()));

            // 完成 SSE 连接
            sseEmitterManager.complete(taskId);
        }
    }

    /**
     * 处理智能体消息并推送
     */
    private void handleAgentMessage(String taskId, String message, ArticleState state) {
        Map<String, Object> data = buildMessageData(message, state);
        if (data != null) {
            sseEmitterManager.send(taskId, GsonUtils.toJson(data));
        }
    }

    /**
     * 构建消息数据
     *
     * @param message 原始消息
     * @param state   文章状态
     * @return 消息数据，如果消息无效返回 null
     */
    private Map<String, Object> buildMessageData(String message, ArticleState state) {
        // 处理流式消息（带冒号分隔符）
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
            String imageJson = message.substring(imageCompletePrefix.length());
            return buildImageCompleteData(imageJson);
        }

        // 处理完成消息（枚举值）
        return buildCompleteMessageData(message, state);
    }

    /**
     * 构建流式输出数据
     */
    private Map<String, Object> buildStreamingData(SseMessageTypeEnum type, String content) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", type.getValue());
        data.put("content", content);
        return data;
    }

    /**
     * 构建图片完成数据
     */
    private Map<String, Object> buildImageCompleteData(String imageJson) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", SseMessageTypeEnum.IMAGE_COMPLETE.getValue());
        data.put("image", GsonUtils.fromJson(imageJson, ArticleState.ImageResult.class));
        return data;
    }

    /**
     * 构建完成消息数据
     */
    private Map<String, Object> buildCompleteMessageData(String message, ArticleState state) {
        Map<String, Object> data = new HashMap<>();

        // 使用枚举值匹配
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
            data.put("imageRequirements", state.getImageRequirements());
            data.put("reviewResult", state.getImagePlanReviewResult());
        } else if (SseMessageTypeEnum.AGENT5_COMPLETE.getValue().equals(message)) {
            articleService.updatePhase(state.getTaskId(), ArticlePhaseEnum.MERGING);
            data.put("type", SseMessageTypeEnum.AGENT5_COMPLETE.getValue());
            data.put("images", state.getImages());
            data.put("imageReviewResults", state.getImageReviewResults());
        } else if (SseMessageTypeEnum.MERGE_COMPLETE.getValue().equals(message)) {
            data.put("type", SseMessageTypeEnum.MERGE_COMPLETE.getValue());
            data.put("fullContent", state.getFullContent());
        } else {
            return null;
        }

        return data;
    }

    /**
     * 发送 SSE 消息
     */
    private void sendSseMessage(String taskId, SseMessageTypeEnum type, Map<String, Object> additionalData) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", type.getValue());
        data.putAll(additionalData);
        sseEmitterManager.send(taskId, GsonUtils.toJson(data));
    }

    private void sendReviewMessage(String taskId, ArticlePhaseEnum phase, ReviewResult reviewResult) {
        if (reviewResult == null) {
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("phase", phase.getValue());
        data.put("reviewResult", reviewResult);
        sendSseMessage(taskId, SseMessageTypeEnum.REVIEW_COMPLETE, data);
    }

    private void sendImageReviewMessages(String taskId, List<ImageReviewResult> imageReviewResults) {
        if (imageReviewResults == null || imageReviewResults.isEmpty()) {
            return;
        }
        for (ImageReviewResult reviewResult : imageReviewResults) {
            sendReviewMessage(taskId, ArticlePhaseEnum.IMAGE_REVIEWING, reviewResult);
        }
    }

    private void handleReviewRejected(String taskId, ReviewRejectedException e) {
        log.warn("阶段评审未通过, taskId={}, phase={}, score={}", taskId, e.getPhase(),
                e.getReviewResult() != null ? e.getReviewResult().getScore() : null);
        ArticlePhaseEnum phase = ArticlePhaseEnum.getByValue(e.getPhase());
        sendReviewMessage(taskId, phase != null ? phase : ArticlePhaseEnum.FAILED, e.getReviewResult());
        articleService.updateArticleStatus(taskId, ArticleStatusEnum.FAILED, e.getMessage());
        articleService.updatePhase(taskId, ArticlePhaseEnum.FAILED);
        sendSseMessage(taskId, SseMessageTypeEnum.ERROR, Map.of("message", e.getMessage()));
        sseEmitterManager.complete(taskId);
    }
}
