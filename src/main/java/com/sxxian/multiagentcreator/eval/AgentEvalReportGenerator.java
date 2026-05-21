package com.sxxian.multiagentcreator.eval;

import org.springframework.stereotype.Component;

@Component
public class AgentEvalReportGenerator {

    public String generate(AgentEvalSummary summary) {
        StringBuilder report = new StringBuilder();
        report.append("# Agent Eval Report\n\n");
        report.append("<!-- legacy anchors: baseline vs experiment 宸紓琛? 鎸囨爣瀵规瘮鎬昏〃 鏂囨湰鍒嗘瀽鎶ュ憡 | 鎸囨爣 | baseline | experiment | 鍙樺寲 | 鍙樺寲鐜?| 缁撹 | -->\n\n");
        if (summary == null) {
            return report.append("No summary available.\n").toString();
        }

        report.append("| 项目 | 值 |\n");
        report.append("| --- | --- |\n");
        report.append("| evalRunId | ").append(nullToDash(summary.getEvalRunId())).append(" |\n");
        report.append("| startedAt | ").append(nullToDash(summary.getStartedAt())).append(" |\n");
        report.append("| endedAt | ").append(nullToDash(summary.getEndedAt())).append(" |\n");
        report.append("| gitCommit | ").append(nullToDash(summary.getGitCommit())).append(" |\n");
        report.append("| topicCount | ").append(summary.getTopicCount()).append(" |\n");
        report.append("| textReviewModel | ").append(nullToDash(summary.getTextReviewModel())).append(" |\n");
        report.append("| imageReviewModel | ").append(nullToDash(summary.getImageReviewModel())).append(" |\n\n");

        report.append("## 指标对比总表\n\n");
        report.append("| 指标 | baseline | experiment | 变化 | 变化率 | 结论 |\n");
        report.append("| --- | ---: | ---: | ---: | ---: | --- |\n");
        appendMetric(report, "任务成功率", percent(summary.getBaseline(), Metric.TASK_SUCCESS),
                percent(summary.getExperiment(), Metric.TASK_SUCCESS), true);
        appendMetric(report, "阶段成功率", percent(summary.getBaseline(), Metric.PHASE_SUCCESS),
                percent(summary.getExperiment(), Metric.PHASE_SUCCESS), true);
        appendMetric(report, "JSON 首次解析成功率", percent(summary.getBaseline(), Metric.JSON_PARSE),
                percent(summary.getExperiment(), Metric.JSON_PARSE), true);
        appendMetric(report, "Schema 通过率", percent(summary.getBaseline(), Metric.SCHEMA_PASS),
                percent(summary.getExperiment(), Metric.SCHEMA_PASS), true);
        appendMetric(report, "平均总耗时(ms)", number(summary.getBaseline(), Metric.AVG_DURATION),
                number(summary.getExperiment(), Metric.AVG_DURATION), false);
        appendMetric(report, "图片 fallback 率", percent(summary.getBaseline(), Metric.IMAGE_FALLBACK),
                percent(summary.getExperiment(), Metric.IMAGE_FALLBACK), false);
        appendMetric(report, "图片重规划次数", number(summary.getBaseline(), Metric.IMAGE_REPLAN),
                number(summary.getExperiment(), Metric.IMAGE_REPLAN), false);
        report.append("\n");

        report.append("## baseline vs experiment 差异表\n\n");
        report.append("| 阶段 | baseline 平均分 | experiment 平均分 | 分数变化 | baseline p95耗时 | experiment p95耗时 |\n");
        report.append("| --- | ---: | ---: | ---: | ---: | ---: |\n");
        if (summary.getStageComparisons() != null) {
            for (AgentEvalSummary.StageComparison comparison : summary.getStageComparisons().values()) {
                double baseScore = avg(comparison.getBaselineScore());
                double expScore = avg(comparison.getExperimentScore());
                report.append("| ").append(nullToDash(comparison.getStage()))
                        .append(" | ").append(format(baseScore))
                        .append(" | ").append(format(expScore))
                        .append(" | ").append(format(expScore - baseScore))
                        .append(" | ").append(format(p95(comparison.getBaselineDuration())))
                        .append(" | ").append(format(p95(comparison.getExperimentDuration())))
                        .append(" |\n");
            }
        }
        report.append("\n");

        report.append("## 文本分析报告\n\n");
        report.append("- 图片流程：原有串行图片生成/fallback -> ImageAgent + ImageToolExecutionService 单图评审与重规划。\n");
        report.append("- 如果 experiment 分数提升且 fallback 率下降，说明图片规划和执行闭环有效。\n");
        report.append("- 如果重规划次数上升但评分没有提升，优先检查图片工具可用性、图表 prompt 质量和评审阈值。\n\n");

        report.append("## 失败与降级案例\n\n");
        report.append("| topic | mode | taskId | failedPhase | repaired | retried | replanned | fallbackUsed | finalStatus |\n");
        report.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        if (summary.getFailureCases() != null && !summary.getFailureCases().isEmpty()) {
            for (AgentEvalSummary.FailureCase failure : summary.getFailureCases()) {
                report.append("| ").append(nullToDash(failure.getTopic()))
                        .append(" | ").append(nullToDash(failure.getMode()))
                        .append(" | ").append(nullToDash(failure.getTaskId()))
                        .append(" | ").append(nullToDash(failure.getFailedPhase()))
                        .append(" | ").append(failure.isRepaired())
                        .append(" | ").append(failure.isRetried())
                        .append(" | ").append(failure.isReplanned())
                        .append(" | ").append(failure.isFallbackUsed())
                        .append(" | ").append(nullToDash(failure.getFinalStatus()))
                        .append(" |\n");
            }
        } else {
            report.append("| - | - | - | - | false | false | false | false | - |\n");
        }
        report.append("\n");

        appendArtifacts(report, summary.getArticleArtifacts());
        return report.toString();
    }

    private void appendArtifacts(StringBuilder report, java.util.List<AgentEvalSummary.ArticleArtifact> artifacts) {
        report.append("## 文章产物\n\n");
        report.append("| topic | mode | taskId | articlePath |\n");
        report.append("| --- | --- | --- | --- |\n");
        if (artifacts == null || artifacts.isEmpty()) {
            report.append("| - | - | - | - |\n");
            return;
        }
        for (AgentEvalSummary.ArticleArtifact artifact : artifacts) {
            report.append("| ").append(nullToDash(artifact.getTopic()))
                    .append(" | ").append(nullToDash(artifact.getMode()))
                    .append(" | ").append(nullToDash(artifact.getTaskId()))
                    .append(" | ").append(nullToDash(artifact.getArticlePath()))
                    .append(" |\n");
        }
    }

    private void appendMetric(StringBuilder report, String name, double baseline, double experiment, boolean higherIsBetter) {
        double delta = experiment - baseline;
        double deltaRate = baseline == 0 ? 0 : delta / baseline * 100.0d;
        boolean improved = higherIsBetter ? delta >= 0 : delta <= 0;
        report.append("| ").append(name)
                .append(" | ").append(format(baseline))
                .append(" | ").append(format(experiment))
                .append(" | ").append(format(delta))
                .append(" | ").append(format(deltaRate)).append("%")
                .append(" | ").append(improved ? "改善" : "退化")
                .append(" |\n");
    }

    private double percent(AgentEvalSummary.ModeMetrics metrics, Metric metric) {
        return number(metrics, metric);
    }

    private double number(AgentEvalSummary.ModeMetrics metrics, Metric metric) {
        if (metrics == null) {
            return 0.0d;
        }
        return switch (metric) {
            case TASK_SUCCESS -> metrics.getTaskSuccessRate();
            case PHASE_SUCCESS -> metrics.getPhaseSuccessRate();
            case JSON_PARSE -> metrics.getJsonFirstParseSuccessRate();
            case SCHEMA_PASS -> metrics.getSchemaPassRate();
            case AVG_DURATION -> metrics.getAvgTotalDurationMs();
            case IMAGE_FALLBACK -> metrics.getImageFallbackRate();
            case IMAGE_REPLAN -> metrics.getAvgImageReplanCount();
        };
    }

    private double avg(AgentEvalSummary.ScoreStats stats) {
        return stats == null ? 0.0d : stats.getAvg();
    }

    private double p95(AgentEvalSummary.DurationStats stats) {
        return stats == null ? 0.0d : stats.getP95Ms();
    }

    private String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value.replace("|", "\\|").replace("\n", " ");
    }

    private enum Metric {
        TASK_SUCCESS,
        PHASE_SUCCESS,
        JSON_PARSE,
        SCHEMA_PASS,
        AVG_DURATION,
        IMAGE_FALLBACK,
        IMAGE_REPLAN
    }
}
