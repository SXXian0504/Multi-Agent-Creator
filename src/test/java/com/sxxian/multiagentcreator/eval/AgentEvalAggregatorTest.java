package com.sxxian.multiagentcreator.eval;

import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.dto.review.ImageReviewResult;
import com.sxxian.multiagentcreator.model.dto.review.ReviewResult;
import com.sxxian.multiagentcreator.model.entity.AgentLog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEvalAggregatorTest {

    private final AgentEvalAggregator aggregator = new AgentEvalAggregator();
    private final AgentEvalReportGenerator reportGenerator = new AgentEvalReportGenerator();

    @Test
    void summarizeAndGenerateReportWithComparisonTables() {
        AgentEvalRunRecord baseline = record(AgentEvalMode.BASELINE, "topic-a", 70, 72, false);
        AgentEvalRunRecord experiment = record(AgentEvalMode.EXPERIMENT, "topic-a", 84, 88, true);

        AgentEvalSummary summary = aggregator.summarize(
                "eval-test",
                List.of(baseline, experiment),
                "2026-05-20 10:00:00",
                "2026-05-20 10:10:00",
                "abc1234",
                "qwen-max",
                "qwen-vl-plus"
        );

        assertEquals(1, summary.getTopicCount());
        assertEquals(100.0d, summary.getExperiment().getTaskSuccessRate());
        assertTrue(summary.getExperiment().getScoresByStage().get("正文评分").getAvg()
                > summary.getBaseline().getScoresByStage().get("正文评分").getAvg());
        assertTrue(summary.getFailureCases().stream().anyMatch(AgentEvalSummary.FailureCase::isFallbackUsed));

        String report = reportGenerator.generate(summary);
        assertTrue(report.contains("baseline vs experiment 差异表"));
        assertTrue(report.contains("指标对比总表"));
        assertTrue(report.contains("文本分析报告"));
        assertTrue(report.contains("| 指标 | baseline | experiment | 变化 | 变化率 | 结论 |"));
    }

    private AgentEvalRunRecord record(AgentEvalMode mode, String topic, int contentScore, int imageScore, boolean fallbackUsed) {
        ArticleState state = new ArticleState();
        state.setTaskId(mode.value() + "-task");
        state.setTopic(topic);
        state.setTitleReviewResult(review(contentScore));
        state.setOutlineReviewResult(review(contentScore));
        state.setContentReviewResult(review(contentScore));
        state.setImagePlanReviewResult(review(imageScore));

        ImageReviewResult imageReviewResult = new ImageReviewResult();
        imageReviewResult.setScore(imageScore);
        imageReviewResult.setApproved(imageScore >= 80);
        state.setImageReviewResults(List.of(imageReviewResult));

        ArticleState.ImageExecutionTrace trace = new ArticleState.ImageExecutionTrace();
        trace.setToolSuccess(true);
        trace.setAttempt(fallbackUsed ? 1 : 0);
        trace.setFallbackUsed(fallbackUsed);
        trace.setFinalStatus(fallbackUsed ? "BEST_EFFORT_AFTER_RETRIES" : "APPROVED");
        state.setImageExecutionTraces(List.of(trace));

        return AgentEvalRunRecord.builder()
                .evalRunId("eval-test")
                .taskId(mode.value() + "-task")
                .topic(topic)
                .mode(mode)
                .success(true)
                .startedAt(0L)
                .endedAt(mode == AgentEvalMode.BASELINE ? 1000L : 1500L)
                .state(state)
                .logs(List.of(log("CONTENT_GENERATING", 500), log("IMAGE_EXECUTING", 500)))
                .build();
    }

    private ReviewResult review(int score) {
        ReviewResult result = new ReviewResult();
        result.setScore(score);
        result.setApproved(score >= 80);
        return result;
    }

    private AgentLog log(String phase, int durationMs) {
        AgentLog log = new AgentLog();
        log.setPhase(phase);
        log.setStatus("SUCCESS");
        log.setDurationMs(durationMs);
        log.setMetadata("""
                {"structuredOutputMetrics":[{"parseSuccess":true,"schemaValid":true,"repairCount":0,"retryCount":0}]}
                """);
        return log;
    }
}
