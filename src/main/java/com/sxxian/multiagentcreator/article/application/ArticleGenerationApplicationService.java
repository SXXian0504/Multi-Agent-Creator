package com.sxxian.multiagentcreator.article.application;

import com.google.gson.reflect.TypeToken;
import com.sxxian.multiagentcreator.agent.config.AgentConfig;
import com.sxxian.multiagentcreator.article.workflow.OrchestratedArticleWorkflow;
import com.sxxian.multiagentcreator.article.workflow.legacy.LegacyArticleWorkflow;
import com.sxxian.multiagentcreator.exception.ReviewRejectedException;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.entity.Article;
import com.sxxian.multiagentcreator.model.enums.ArticlePhaseEnum;
import com.sxxian.multiagentcreator.model.enums.ArticleStatusEnum;
import com.sxxian.multiagentcreator.model.enums.SseMessageTypeEnum;
import com.sxxian.multiagentcreator.rag.retrieval.RagService;
import com.sxxian.multiagentcreator.service.ArticleService;
import com.sxxian.multiagentcreator.utils.GsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ArticleGenerationApplicationService {

    @Resource
    private LegacyArticleWorkflow legacyArticleWorkflow;

    @Resource
    private OrchestratedArticleWorkflow orchestratedArticleWorkflow;

    @Resource
    private AgentConfig agentConfig;

    @Resource
    private ArticleService articleService;

    @Resource
    private RagService ragService;

    @Resource
    private ArticleGenerationEventPublisher eventPublisher;

    /**
     * 阶段 1：异步生成标题方案。
     *
     * @param taskId    任务 ID
     * @param topic     选题
     * @param platform  发布平台，可为空
     * @param style     文章风格，可为空
     * @param wordRange 字数范围，可为空
     */
    @Async("articleExecutor")
    public void executePhase1(String taskId, String topic, String platform, String style, String wordRange) {
        boolean useOrchestrator = agentConfig.isOrchestratorEnabled();
        log.info("阶段 1 异步任务开始, taskId={}, topic={}, platform={}, style={}, wordRange={}, useOrchestrator={}",
                taskId, topic, platform, style, wordRange, useOrchestrator);

        try {
            articleService.updateArticleStatus(taskId, ArticleStatusEnum.PROCESSING, null);
            articleService.updatePhase(taskId, ArticlePhaseEnum.TITLE_GENERATING);

            ArticleState state = new ArticleState();
            state.setTaskId(taskId);
            state.setTopic(topic);
            state.setPlatform(platform);
            state.setStyle(style);
            state.setWordRange(wordRange);
            state.setRetrievedContext(ragService.buildRetrievedContext(articleService.getByTaskId(taskId)));

            if (useOrchestrator) {
                orchestratedArticleWorkflow.executePhase1_GenerateTitles(
                        state,
                        message -> eventPublisher.publishAgentMessage(taskId, message, state));
            } else {
                legacyArticleWorkflow.executePhase1_GenerateTitles(
                        state,
                        message -> eventPublisher.publishAgentMessage(taskId, message, state));
            }
            eventPublisher.publishReviewMessage(taskId, ArticlePhaseEnum.TITLE_REVIEWING, state.getTitleReviewResult());

            articleService.saveTitleOptions(taskId, state.getTitleOptions());
            articleService.updatePhase(taskId, ArticlePhaseEnum.TITLE_WAITING_USER_CONFIRM);

            Map<String, Object> data = new HashMap<>();
            data.put("titleOptions", state.getTitleOptions());
            eventPublisher.publishSseMessage(taskId, SseMessageTypeEnum.TITLES_GENERATED, data);

            log.info("阶段 1 异步任务完成, taskId={}", taskId);
        } catch (ReviewRejectedException e) {
            handleReviewRejected(taskId, e);
        } catch (Exception e) {
            handleFailure(taskId, ArticlePhaseEnum.FAILED, "阶段 1 异步任务失败", e);
        }
    }

    /**
     * 阶段 2：用户确认标题后，异步生成大纲。
     *
     * @param taskId 任务 ID
     */
    @Async("articleExecutor")
    public void executePhase2(String taskId) {
        boolean useOrchestrator = agentConfig.isOrchestratorEnabled();
        log.info("阶段 2 异步任务开始, taskId={}, useOrchestrator={}", taskId, useOrchestrator);

        try {
            Article article = articleService.getByTaskId(taskId);
            if (article == null) {
                throw new RuntimeException("文章不存在");
            }

            articleService.updatePhase(taskId, ArticlePhaseEnum.OUTLINE_GENERATING);

            ArticleState state = new ArticleState();
            state.setTaskId(taskId);
            state.setTopic(article.getTopic());
            state.setPlatform(article.getPlatform());
            state.setStyle(article.getStyle());
            state.setWordRange(article.getWordRange());
            state.setUserDescription(article.getUserDescription());
            state.setRetrievedContext(ragService.buildRetrievedContext(article));

            ArticleState.TitleResult title = new ArticleState.TitleResult();
            title.setMainTitle(article.getMainTitle());
            title.setSubTitle(article.getSubTitle());
            state.setTitle(title);

            if (useOrchestrator) {
                orchestratedArticleWorkflow.executePhase2_GenerateOutline(
                        state,
                        message -> eventPublisher.publishAgentMessage(taskId, message, state));
            } else {
                legacyArticleWorkflow.executePhase2_GenerateOutline(
                        state,
                        message -> eventPublisher.publishAgentMessage(taskId, message, state));
            }
            eventPublisher.publishReviewMessage(taskId, ArticlePhaseEnum.OUTLINE_REVIEWING, state.getOutlineReviewResult());

            Article articleToUpdate = articleService.getByTaskId(taskId);
            articleToUpdate.setOutline(GsonUtils.toJson(state.getOutline().getSections()));
            articleService.updateById(articleToUpdate);
            articleService.updatePhase(taskId, ArticlePhaseEnum.OUTLINE_WAITING_USER_CONFIRM);

            Map<String, Object> data = new HashMap<>();
            data.put("outline", state.getOutline().getSections());
            eventPublisher.publishSseMessage(taskId, SseMessageTypeEnum.OUTLINE_GENERATED, data);

            log.info("阶段 2 异步任务完成, taskId={}", taskId);
        } catch (ReviewRejectedException e) {
            handleReviewRejected(taskId, e);
        } catch (Exception e) {
            handleFailure(taskId, ArticlePhaseEnum.FAILED, "阶段 2 异步任务失败", e);
        }
    }

    /**
     * 阶段 3：用户确认大纲后，异步生成正文和配图。
     *
     * @param taskId 任务 ID
     */
    @Async("articleExecutor")
    public void executePhase3(String taskId) {
        boolean useOrchestrator = agentConfig.isOrchestratorEnabled();
        log.info("阶段 3 异步任务开始, taskId={}, useOrchestrator={}", taskId, useOrchestrator);

        try {
            Article article = articleService.getByTaskId(taskId);
            if (article == null) {
                throw new RuntimeException("文章不存在");
            }

            articleService.updatePhase(taskId, ArticlePhaseEnum.CONTENT_GENERATING);

            ArticleState state = new ArticleState();
            state.setTaskId(taskId);
            state.setTopic(article.getTopic());
            state.setPlatform(article.getPlatform());
            state.setStyle(article.getStyle());
            state.setWordRange(article.getWordRange());
            state.setUserDescription(article.getUserDescription());
            state.setRetrievedContext(ragService.buildRetrievedContext(article));

            List<String> enabledMethods = null;
            if (article.getEnabledImageMethods() != null) {
                enabledMethods = GsonUtils.fromJson(
                        article.getEnabledImageMethods(),
                        new TypeToken<List<String>>() {
                        });
            }
            state.setEnabledImageMethods(enabledMethods);

            ArticleState.TitleResult title = new ArticleState.TitleResult();
            title.setMainTitle(article.getMainTitle());
            title.setSubTitle(article.getSubTitle());
            state.setTitle(title);

            List<ArticleState.OutlineSection> outlineSections = GsonUtils.fromJson(
                    article.getOutline(),
                    new TypeToken<List<ArticleState.OutlineSection>>() {
                    });
            ArticleState.OutlineResult outlineResult = new ArticleState.OutlineResult();
            outlineResult.setSections(outlineSections);
            state.setOutline(outlineResult);

            if (useOrchestrator) {
                orchestratedArticleWorkflow.executePhase3_GenerateContent(
                        state,
                        message -> eventPublisher.publishAgentMessage(taskId, message, state));
            } else {
                legacyArticleWorkflow.executePhase3_GenerateContent(
                        state,
                        message -> eventPublisher.publishAgentMessage(taskId, message, state));
            }
            eventPublisher.publishReviewMessage(taskId, ArticlePhaseEnum.CONTENT_REVIEWING, state.getContentReviewResult());
            eventPublisher.publishReviewMessage(taskId, ArticlePhaseEnum.IMAGE_REVIEWING, state.getImagePlanReviewResult());
            eventPublisher.publishImageReviewMessages(taskId, state.getImageReviewResults());

            articleService.saveArticleContent(taskId, state);
            articleService.updateArticleStatus(taskId, ArticleStatusEnum.COMPLETED, null);
            articleService.updatePhase(taskId, ArticlePhaseEnum.COMPLETED);

            eventPublisher.publishSseMessage(taskId, SseMessageTypeEnum.ALL_COMPLETE, Map.of("taskId", taskId));
            eventPublisher.complete(taskId);

            log.info("阶段 3 异步任务完成, taskId={}", taskId);
        } catch (ReviewRejectedException e) {
            handleReviewRejected(taskId, e);
        } catch (Exception e) {
            handleFailure(taskId, ArticlePhaseEnum.FAILED, "阶段 3 异步任务失败", e);
        }
    }

    private void handleReviewRejected(String taskId, ReviewRejectedException e) {
        log.warn("阶段评审未通过, taskId={}, phase={}, score={}", taskId, e.getPhase(),
                e.getReviewResult() != null ? e.getReviewResult().getScore() : null);
        ArticlePhaseEnum phase = ArticlePhaseEnum.getByValue(e.getPhase());
        eventPublisher.publishReviewMessage(taskId, phase != null ? phase : ArticlePhaseEnum.FAILED, e.getReviewResult());
        articleService.updateArticleStatus(taskId, ArticleStatusEnum.FAILED, e.getMessage());
        articleService.updatePhase(taskId, ArticlePhaseEnum.FAILED);
        eventPublisher.publishSseMessage(taskId, SseMessageTypeEnum.ERROR, Map.of("message", e.getMessage()));
        eventPublisher.complete(taskId);
    }

    private void handleFailure(String taskId, ArticlePhaseEnum phase, String logMessage, Exception e) {
        log.error("{}, taskId={}", logMessage, taskId, e);
        articleService.updateArticleStatus(taskId, ArticleStatusEnum.FAILED, e.getMessage());
        articleService.updatePhase(taskId, phase);
        eventPublisher.publishSseMessage(taskId, SseMessageTypeEnum.ERROR, Map.of("message", e.getMessage()));
        eventPublisher.complete(taskId);
    }
}
