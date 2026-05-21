package com.sxxian.multiagentcreator.eval;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.dto.review.ReviewResult;
import com.sxxian.multiagentcreator.model.entity.AgentLog;
import com.sxxian.multiagentcreator.utils.GsonUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class AgentEvalAggregator {

    private static final List<String> SCORE_STAGES = List.of(
            "标题评分", "大纲评分", "正文评分", "配图计划评分", "图片结果评分"
    );

    private static final Map<String, String> SCORE_STAGE_TO_PHASE = Map.of(
            "标题评分", "TITLE_GENERATING",
            "大纲评分", "OUTLINE_GENERATING",
            "正文评分", "CONTENT_GENERATING",
            "配图计划评分", "IMAGE_PLANNING",
            "图片结果评分", "IMAGE_EXECUTING"
    );

    public AgentEvalSummary summarize(String evalRunId,
                                      List<AgentEvalRunRecord> records,
                                      String startedAt,
                                      String endedAt,
                                      String gitCommit,
                                      String textReviewModel,
                                      String imageReviewModel) {
        List<AgentEvalRunRecord> baselineRecords = byMode(records, AgentEvalMode.BASELINE);
        List<AgentEvalRunRecord> experimentRecords = byMode(records, AgentEvalMode.EXPERIMENT);
        AgentEvalSummary.ModeMetrics baseline = summarizeMode(AgentEvalMode.BASELINE, baselineRecords);
        AgentEvalSummary.ModeMetrics experiment = summarizeMode(AgentEvalMode.EXPERIMENT, experimentRecords);

        Map<String, AgentEvalSummary.StageComparison> stageComparisons = new LinkedHashMap<>();
        for (String stage : SCORE_STAGES) {
            String phase = SCORE_STAGE_TO_PHASE.get(stage);
            stageComparisons.put(stage, AgentEvalSummary.StageComparison.builder()
                    .stage(stage)
                    .baselineScore(baseline.getScoresByStage().get(stage))
                    .experimentScore(experiment.getScoresByStage().get(stage))
                    .baselineDuration(baseline.getDurationsByPhase().get(phase))
                    .experimentDuration(experiment.getDurationsByPhase().get(phase))
                    .build());
        }

        return AgentEvalSummary.builder()
                .evalRunId(evalRunId)
                .topicCount((int) records.stream().map(AgentEvalRunRecord::getTopic).distinct().count())
                .startedAt(startedAt)
                .endedAt(endedAt)
                .gitCommit(gitCommit)
                .textReviewModel(textReviewModel)
                .imageReviewModel(imageReviewModel)
                .baseline(baseline)
                .experiment(experiment)
                .stageComparisons(stageComparisons)
                .failureCases(failureCases(records))
                .articleArtifacts(articleArtifacts(records))
                .build();
    }

    private AgentEvalSummary.ModeMetrics summarizeMode(AgentEvalMode mode, List<AgentEvalRunRecord> records) {
        Map<String, List<Integer>> scores = new LinkedHashMap<>();
        SCORE_STAGES.forEach(stage -> scores.put(stage, new ArrayList<>()));
        Map<String, List<Integer>> durationsByPhase = new LinkedHashMap<>();
        List<Long> totalDurations = new ArrayList<>();

        int successfulTasks = 0;
        int successfulPhases = 0;
        int totalPhases = 0;
        int parseSuccess = 0;
        int schemaPass = 0;
        int structuredCount = 0;
        int repairCount = 0;
        int retryCount = 0;
        int toolSuccess = 0;
        int toolCount = 0;
        int fallbackCount = 0;
        int imageTaskCount = 0;
        int replanCount = 0;
        long estimatedTokens = 0L;

        for (AgentEvalRunRecord record : records) {
            if (record.isSuccess()) {
                successfulTasks++;
            }
            totalDurations.add(record.durationMs());
            ArticleState state = record.getState();
            addScores(scores, state);
            estimatedTokens += estimateTokens(state);

            List<AgentLog> logs = Optional.ofNullable(record.getLogs()).orElse(List.of());
            for (AgentLog log : logs) {
                totalPhases++;
                if ("SUCCESS".equalsIgnoreCase(log.getStatus())) {
                    successfulPhases++;
                }
                if (log.getPhase() != null && log.getDurationMs() != null) {
                    durationsByPhase.computeIfAbsent(log.getPhase(), ignored -> new ArrayList<>())
                            .add(log.getDurationMs());
                }
                StructuredCounters counters = readStructuredCounters(log);
                structuredCount += counters.count;
                parseSuccess += counters.parseSuccess;
                schemaPass += counters.schemaPass;
                repairCount += counters.repairCount;
                retryCount += counters.retryCount;
                estimatedTokens += estimateTokens(log.getPrompt())
                        + estimateTokens(log.getInputData())
                        + estimateTokens(log.getOutputData());
            }

            List<ArticleState.ImageExecutionTrace> traces = state == null ? null : state.getImageExecutionTraces();
            if (traces != null && !traces.isEmpty()) {
                imageTaskCount++;
                boolean taskFallbackUsed = false;
                for (ArticleState.ImageExecutionTrace trace : traces) {
                    if (trace.getToolSuccess() != null) {
                        toolCount++;
                        if (Boolean.TRUE.equals(trace.getToolSuccess())) {
                            toolSuccess++;
                        }
                    }
                    if (Boolean.TRUE.equals(trace.getFallbackUsed())) {
                        taskFallbackUsed = true;
                    }
                    if (trace.getAttempt() != null && trace.getAttempt() > 0
                            && !"fallback".equalsIgnoreCase(trace.getPlannedFrom())) {
                        replanCount++;
                    }
                }
                if (taskFallbackUsed) {
                    fallbackCount++;
                }
            }
        }

        Map<String, AgentEvalSummary.ScoreStats> scoreStats = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> entry : scores.entrySet()) {
            scoreStats.put(entry.getKey(), scoreStats(entry.getValue()));
        }

        Map<String, AgentEvalSummary.DurationStats> durationStats = new LinkedHashMap<>();
        durationsByPhase.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> durationStats.put(entry.getKey(), durationStats(entry.getValue())));

        return AgentEvalSummary.ModeMetrics.builder()
                .mode(mode)
                .runCount(records.size())
                .successCount(successfulTasks)
                .taskSuccessRate(rate(successfulTasks, records.size()))
                .phaseSuccessRate(rate(successfulPhases, totalPhases))
                .jsonFirstParseSuccessRate(rate(parseSuccess, structuredCount))
                .schemaPassRate(rate(schemaPass, structuredCount))
                .avgRepairCount(avg(repairCount, structuredCount))
                .avgRetryCount(avg(retryCount, structuredCount))
                .avgTotalDurationMs(avgLong(totalDurations))
                .p95TotalDurationMs(percentileLong(totalDurations, 0.95))
                .imageToolSuccessRate(rate(toolSuccess, toolCount))
                .imageFallbackRate(rate(fallbackCount, imageTaskCount))
                .avgImageReplanCount(avg(replanCount, Math.max(1, imageTaskCount)))
                .scoresByStage(scoreStats)
                .durationsByPhase(durationStats)
                .estimatedTokens(estimatedTokens)
                .build();
    }

    private List<AgentEvalRunRecord> byMode(List<AgentEvalRunRecord> records, AgentEvalMode mode) {
        return records.stream()
                .filter(record -> record.getMode() == mode)
                .toList();
    }

    private void addScores(Map<String, List<Integer>> scores, ArticleState state) {
        if (state == null) {
            return;
        }
        addScore(scores, "标题评分", state.getTitleReviewResult());
        addScore(scores, "大纲评分", state.getOutlineReviewResult());
        addScore(scores, "正文评分", state.getContentReviewResult());
        addScore(scores, "配图计划评分", state.getImagePlanReviewResult());
        if (state.getImageReviewResults() != null) {
            state.getImageReviewResults().forEach(result -> addScore(scores, "图片结果评分", result));
        }
    }

    private void addScore(Map<String, List<Integer>> scores, String stage, ReviewResult result) {
        if (result != null && result.getScore() != null) {
            scores.computeIfAbsent(stage, ignored -> new ArrayList<>()).add(result.getScore());
        }
    }

    private AgentEvalSummary.ScoreStats scoreStats(List<Integer> values) {
        List<Integer> clean = clean(values);
        return AgentEvalSummary.ScoreStats.builder()
                .count(clean.size())
                .avg(avgInts(clean))
                .p50(percentileInt(clean, 0.50))
                .p95(percentileInt(clean, 0.95))
                .passRate(rate((int) clean.stream().filter(value -> value >= 80).count(), clean.size()))
                .build();
    }

    private AgentEvalSummary.DurationStats durationStats(List<Integer> values) {
        List<Integer> clean = clean(values);
        return AgentEvalSummary.DurationStats.builder()
                .count(clean.size())
                .avgMs(avgInts(clean))
                .p95Ms(percentileInt(clean, 0.95))
                .build();
    }

    private List<AgentEvalSummary.FailureCase> failureCases(List<AgentEvalRunRecord> records) {
        List<AgentEvalSummary.FailureCase> cases = new ArrayList<>();
        for (AgentEvalRunRecord record : records) {
            boolean failed = !record.isSuccess();
            boolean repaired = false;
            boolean retried = false;
            String failedPhase = null;
            String finalStatus = record.isSuccess() ? "SUCCESS" : "FAILED";

            for (AgentLog log : Optional.ofNullable(record.getLogs()).orElse(List.of())) {
                StructuredCounters counters = readStructuredCounters(log);
                repaired = repaired || counters.repairCount > 0;
                retried = retried || counters.retryCount > 0 || (log.getRetryCount() != null && log.getRetryCount() > 0);
                if ("FAILED".equalsIgnoreCase(log.getStatus()) && failedPhase == null) {
                    failed = true;
                    failedPhase = log.getPhase();
                }
            }

            boolean replanned = false;
            boolean fallbackUsed = false;
            ArticleState state = record.getState();
            if (state != null && state.getImageExecutionTraces() != null) {
                for (ArticleState.ImageExecutionTrace trace : state.getImageExecutionTraces()) {
                    replanned = replanned || (trace.getAttempt() != null && trace.getAttempt() > 0);
                    fallbackUsed = fallbackUsed || Boolean.TRUE.equals(trace.getFallbackUsed());
                    if (!record.isSuccess() && failedPhase == null && "TOOL_FAILED".equalsIgnoreCase(trace.getFinalStatus())) {
                        failedPhase = "IMAGE_EXECUTING";
                    }
                }
            }

            if (failed || repaired || retried || replanned || fallbackUsed) {
                cases.add(AgentEvalSummary.FailureCase.builder()
                        .topic(record.getTopic())
                        .mode(record.getMode().value())
                        .taskId(record.getTaskId())
                        .failedPhase(failedPhase == null ? "-" : failedPhase)
                        .errorMessage(record.getErrorMessage() == null ? "-" : record.getErrorMessage())
                        .repaired(repaired)
                        .retried(retried)
                        .replanned(replanned)
                        .fallbackUsed(fallbackUsed)
                        .finalStatus(finalStatus)
                        .build());
            }
        }
        return cases;
    }

    private List<AgentEvalSummary.ArticleArtifact> articleArtifacts(List<AgentEvalRunRecord> records) {
        return records.stream()
                .filter(record -> record.getArticlePath() != null && !record.getArticlePath().isBlank())
                .map(record -> AgentEvalSummary.ArticleArtifact.builder()
                        .topic(record.getTopic())
                        .mode(record.getMode().value())
                        .taskId(record.getTaskId())
                        .articlePath(record.getArticlePath())
                        .build())
                .toList();
    }

    private StructuredCounters readStructuredCounters(AgentLog log) {
        StructuredCounters counters = new StructuredCounters();
        if (log == null || log.getMetadata() == null || log.getMetadata().isBlank()) {
            return counters;
        }
        try {
            JsonObject metadata = GsonUtils.fromJson(log.getMetadata(), JsonObject.class);
            if (metadata == null || !metadata.has("structuredOutputMetrics")) {
                return counters;
            }
            JsonArray metrics = metadata.getAsJsonArray("structuredOutputMetrics");
            if (metrics == null) {
                return counters;
            }
            for (JsonElement element : metrics) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                counters.count++;
                if (bool(item, "parseSuccess")) {
                    counters.parseSuccess++;
                }
                if (bool(item, "schemaValid")) {
                    counters.schemaPass++;
                }
                counters.repairCount += intValue(item, "repairCount");
                counters.retryCount += intValue(item, "retryCount");
            }
        } catch (Exception ignored) {
            return counters;
        }
        return counters;
    }

    private boolean bool(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() && object.get(name).getAsBoolean();
    }

    private int intValue(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsInt() : 0;
    }

    private long estimateTokens(ArticleState state) {
        if (state == null) {
            return 0L;
        }
        return estimateTokens(state.getTopic())
                + estimateTokens(GsonUtils.toJson(state.getTitleOptions()))
                + estimateTokens(GsonUtils.toJson(state.getOutline()))
                + estimateTokens(state.getContent())
                + estimateTokens(GsonUtils.toJson(state.getImageRequirements()))
                + estimateTokens(GsonUtils.toJson(state.getImages()))
                + estimateTokens(state.getFullContent());
    }

    private long estimateTokens(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        long cjk = value.codePoints()
                .filter(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)
                .count();
        long other = value.codePoints().count() - cjk;
        return Math.round(cjk / 1.5d + other / 4.0d);
    }

    private double rate(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0.0d;
        }
        return numerator * 100.0d / denominator;
    }

    private double avg(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0.0d;
        }
        return numerator * 1.0d / denominator;
    }

    private double avgInts(List<Integer> values) {
        List<Integer> clean = clean(values);
        if (clean.isEmpty()) {
            return 0.0d;
        }
        return clean.stream().mapToInt(Integer::intValue).average().orElse(0.0d);
    }

    private double avgLong(List<Long> values) {
        List<Long> clean = values.stream().filter(Objects::nonNull).toList();
        if (clean.isEmpty()) {
            return 0.0d;
        }
        return clean.stream().mapToLong(Long::longValue).average().orElse(0.0d);
    }

    private double percentileInt(List<Integer> values, double percentile) {
        List<Integer> clean = clean(values);
        if (clean.isEmpty()) {
            return 0.0d;
        }
        clean.sort(Comparator.naturalOrder());
        int index = (int) Math.ceil(percentile * clean.size()) - 1;
        return clean.get(Math.max(0, Math.min(index, clean.size() - 1)));
    }

    private double percentileLong(List<Long> values, double percentile) {
        List<Long> clean = values.stream().filter(Objects::nonNull).sorted().toList();
        if (clean.isEmpty()) {
            return 0.0d;
        }
        int index = (int) Math.ceil(percentile * clean.size()) - 1;
        return clean.get(Math.max(0, Math.min(index, clean.size() - 1)));
    }

    private List<Integer> clean(List<Integer> values) {
        return values == null
                ? new ArrayList<>()
                : values.stream().filter(Objects::nonNull).collect(Collectors.toCollection(ArrayList::new));
    }

    private static class StructuredCounters {
        private int count;
        private int parseSuccess;
        private int schemaPass;
        private int repairCount;
        private int retryCount;
    }
}
