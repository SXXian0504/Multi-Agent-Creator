package com.sxxian.multiagentcreator.eval;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class AgentEvalSummary {

    private String evalRunId;

    private int topicCount;

    private String startedAt;

    private String endedAt;

    private String gitCommit;

    private String textReviewModel;

    private String imageReviewModel;

    private ModeMetrics baseline;

    private ModeMetrics experiment;

    private Map<String, StageComparison> stageComparisons;

    @Builder.Default
    private List<FailureCase> failureCases = new ArrayList<>();

    @Builder.Default
    private List<ArticleArtifact> articleArtifacts = new ArrayList<>();

    @Data
    @Builder
    public static class ModeMetrics {
        private AgentEvalMode mode;
        private int runCount;
        private int successCount;
        private double taskSuccessRate;
        private double phaseSuccessRate;
        private double jsonFirstParseSuccessRate;
        private double schemaPassRate;
        private double avgRepairCount;
        private double avgRetryCount;
        private double avgTotalDurationMs;
        private double p95TotalDurationMs;
        private double imageToolSuccessRate;
        private double imageFallbackRate;
        private double avgImageReplanCount;
        private Map<String, ScoreStats> scoresByStage;
        private Map<String, DurationStats> durationsByPhase;
        private long estimatedTokens;
    }

    @Data
    @Builder
    public static class ScoreStats {
        private int count;
        private double avg;
        private double p50;
        private double p95;
        private double passRate;
    }

    @Data
    @Builder
    public static class DurationStats {
        private int count;
        private double avgMs;
        private double p95Ms;
    }

    @Data
    @Builder
    public static class StageComparison {
        private String stage;
        private ScoreStats baselineScore;
        private ScoreStats experimentScore;
        private DurationStats baselineDuration;
        private DurationStats experimentDuration;
    }

    @Data
    @Builder
    public static class FailureCase {
        private String topic;
        private String mode;
        private String taskId;
        private String failedPhase;
        private String errorMessage;
        private boolean repaired;
        private boolean retried;
        private boolean replanned;
        private boolean fallbackUsed;
        private String finalStatus;
    }

    @Data
    @Builder
    public static class ArticleArtifact {
        private String topic;
        private String mode;
        private String taskId;
        private String articlePath;
    }
}
