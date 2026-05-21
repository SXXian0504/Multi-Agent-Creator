package com.sxxian.multiagentcreator.article.application;

import com.sxxian.multiagentcreator.agent.config.AgentConfig;
import com.sxxian.multiagentcreator.article.workflow.OrchestratedArticleWorkflow;
import com.sxxian.multiagentcreator.article.workflow.legacy.LegacyArticleWorkflow;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.enums.ArticleStatusEnum;
import com.sxxian.multiagentcreator.rag.retrieval.RagService;
import com.sxxian.multiagentcreator.service.ArticleService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleGenerationApplicationServiceTest {

    @Test
    void phase1UsesOrchestratedWorkflowWhenEnabled() {
        TestContext context = new TestContext(true);

        context.service.executePhase1("task-1", "topic", "platform", "style", "1000");

        verify(context.orchestratedArticleWorkflow).executePhase1_GenerateTitles(any(ArticleState.class), any());
        verify(context.legacyArticleWorkflow, never()).executePhase1_GenerateTitles(any(ArticleState.class), any());
        verify(context.articleService).updateArticleStatus("task-1", ArticleStatusEnum.PROCESSING, null);
    }

    @Test
    void phase1UsesLegacyWorkflowWhenDisabled() {
        TestContext context = new TestContext(false);

        context.service.executePhase1("task-1", "topic", "platform", "style", "1000");

        verify(context.legacyArticleWorkflow).executePhase1_GenerateTitles(any(ArticleState.class), any());
        verify(context.orchestratedArticleWorkflow, never()).executePhase1_GenerateTitles(any(ArticleState.class), any());
        verify(context.articleService).saveTitleOptions(eq("task-1"), any());
    }

    private static class TestContext {
        private final ArticleGenerationApplicationService service = new ArticleGenerationApplicationService();
        private final LegacyArticleWorkflow legacyArticleWorkflow = mock(LegacyArticleWorkflow.class);
        private final OrchestratedArticleWorkflow orchestratedArticleWorkflow = mock(OrchestratedArticleWorkflow.class);
        private final ArticleService articleService = mock(ArticleService.class);

        private TestContext(boolean orchestratorEnabled) {
            AgentConfig agentConfig = mock(AgentConfig.class);
            RagService ragService = mock(RagService.class);
            ArticleGenerationEventPublisher eventPublisher = mock(ArticleGenerationEventPublisher.class);

            when(agentConfig.isOrchestratorEnabled()).thenReturn(orchestratorEnabled);
            when(ragService.buildRetrievedContext(any())).thenReturn("");

            doAnswer(invocation -> {
                ArticleState state = invocation.getArgument(0);
                state.setTitleOptions(List.of(new ArticleState.TitleOption()));
                Consumer<String> streamHandler = invocation.getArgument(1);
                streamHandler.accept("agent1_complete");
                return null;
            }).when(legacyArticleWorkflow).executePhase1_GenerateTitles(any(ArticleState.class), any());

            doAnswer(invocation -> {
                ArticleState state = invocation.getArgument(0);
                state.setTitleOptions(List.of(new ArticleState.TitleOption()));
                Consumer<String> streamHandler = invocation.getArgument(1);
                streamHandler.accept("agent1_complete");
                return null;
            }).when(orchestratedArticleWorkflow).executePhase1_GenerateTitles(any(ArticleState.class), any());

            ReflectionTestUtils.setField(service, "legacyArticleWorkflow", legacyArticleWorkflow);
            ReflectionTestUtils.setField(service, "orchestratedArticleWorkflow", orchestratedArticleWorkflow);
            ReflectionTestUtils.setField(service, "agentConfig", agentConfig);
            ReflectionTestUtils.setField(service, "articleService", articleService);
            ReflectionTestUtils.setField(service, "ragService", ragService);
            ReflectionTestUtils.setField(service, "eventPublisher", eventPublisher);
        }
    }
}
