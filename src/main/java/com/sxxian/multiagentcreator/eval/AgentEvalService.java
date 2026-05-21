package com.sxxian.multiagentcreator.eval;

import com.google.gson.reflect.TypeToken;
import com.sxxian.multiagentcreator.article.workflow.OrchestratedArticleWorkflow;
import com.sxxian.multiagentcreator.article.review.ReviewAgent;
import com.sxxian.multiagentcreator.exception.ReviewRejectedException;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.dto.eval.AgentEvalRequest;
import com.sxxian.multiagentcreator.model.dto.eval.AgentEvalResponse;
import com.sxxian.multiagentcreator.service.AgentLogService;
import com.sxxian.multiagentcreator.article.workflow.legacy.LegacyArticleWorkflow;
import com.sxxian.multiagentcreator.utils.GsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@Service
@Slf4j
public class AgentEvalService {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_LIMIT = 20;

    @Resource
    private LegacyArticleWorkflow legacyArticleWorkflow;

    @Resource
    private OrchestratedArticleWorkflow orchestratedArticleWorkflow;

    @Resource
    private ReviewAgent reviewAgent;

    @Resource
    private AgentLogService agentLogService;

    @Resource
    private AgentEvalAggregator aggregator;

    @Resource
    private AgentEvalReportGenerator reportGenerator;

    @Resource
    private ResourceLoader resourceLoader;

    @Value("${article.review.text-model:qwen-max}")
    private String textReviewModel;

    @Value("${article.review.image-model:${article.review.model:qwen-vl-plus}}")
    private String imageReviewModel;

    public AgentEvalResponse run(AgentEvalRequest request) {
        LocalDateTime startedAt = LocalDateTime.now();
        String evalRunId = "agent-eval-" + FILE_TIME.format(startedAt) + "-" + shortId();
        List<String> topics = resolveTopics(request);
        List<AgentEvalRunRecord> records = new ArrayList<>();

        log.info("AgentEval started, evalRunId={}, topicCount={}", evalRunId, topics.size());
        for (String topic : topics) {
            records.add(runOne(evalRunId, AgentEvalMode.BASELINE, topic, request, records.size() + 1));
            records.add(runOne(evalRunId, AgentEvalMode.EXPERIMENT, topic, request, records.size() + 1));
        }

        LocalDateTime endedAt = LocalDateTime.now();
        AgentEvalSummary summary = aggregator.summarize(
                evalRunId,
                records,
                DISPLAY_TIME.format(startedAt),
                DISPLAY_TIME.format(endedAt),
                currentGitCommit(),
                textReviewModel,
                imageReviewModel
        );
        String markdown = reportGenerator.generate(summary);
        String reportPath = writeReport(markdown, request, evalRunId);

        log.info("AgentEval completed, evalRunId={}, reportPath={}", evalRunId, reportPath);
        return AgentEvalResponse.builder()
                .evalRunId(evalRunId)
                .topicCount(topics.size())
                .reportPath(reportPath)
                .reportMarkdown(markdown)
                .build();
    }

    private AgentEvalRunRecord runOne(String evalRunId, AgentEvalMode mode, String topic, AgentEvalRequest request, int sequence) {
        String taskId = evalRunId + "-" + mode.value() + "-" + shortId();
        ArticleState state = buildInitialState(taskId, topic, request);
        long startedAt = System.currentTimeMillis();
        boolean success = false;
        String errorMessage = null;
        String articlePath = null;

        try {
            if (mode == AgentEvalMode.BASELINE) {
                runBaseline(state);
            } else {
                runExperiment(state);
            }
            success = true;
        } catch (Exception e) {
            errorMessage = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("AgentEval case failed, evalRunId={}, mode={}, taskId={}, topic={}",
                    evalRunId, mode.value(), taskId, topic, e);
        }

        waitForAsyncLogSave();
        articlePath = writeArticleArtifact(state, request, evalRunId, mode, sequence, success, errorMessage);
        return AgentEvalRunRecord.builder()
                .evalRunId(evalRunId)
                .taskId(taskId)
                .topic(topic)
                .mode(mode)
                .success(success)
                .startedAt(startedAt)
                .endedAt(System.currentTimeMillis())
                .errorMessage(errorMessage)
                .state(state)
                .logs(safeLogs(taskId))
                .articlePath(articlePath)
                .build();
    }

    private void runBaseline(ArticleState state) {
        Consumer<String> noopStream = ignored -> {
        };
        legacyArticleWorkflow.agent1GenerateTitleOptions(state);
        state.setTitleReviewResult(reviewAgent.reviewTitles(state));
        autoSelectTitle(state);
        legacyArticleWorkflow.agent2GenerateOutline(state, noopStream);
        state.setOutlineReviewResult(reviewAgent.reviewOutline(state));
        legacyArticleWorkflow.agent3GenerateContent(state, noopStream);
        state.setContentReviewResult(reviewAgent.reviewContent(state));
        try {
            legacyArticleWorkflow.agent4AnalyzeImageRequirements(state);
        } catch (ReviewRejectedException e) {
            log.warn("AgentEval baseline image plan review rejected but continuing as weak baseline, taskId={}, score={}",
                    state.getTaskId(), e.getReviewResult() == null ? null : e.getReviewResult().getScore());
            if (state.getImageRequirements() == null || state.getImageRequirements().isEmpty()) {
                throw e;
            }
        }
        legacyArticleWorkflow.agent5GenerateImages(state, noopStream);
        legacyArticleWorkflow.mergeImagesIntoContent(state);
    }

    private void runExperiment(ArticleState state) {
        Consumer<String> noopStream = ignored -> {
        };
        orchestratedArticleWorkflow.executePhase1_GenerateTitles(state, noopStream);
        autoSelectTitle(state);
        orchestratedArticleWorkflow.executePhase2_GenerateOutline(state, noopStream);
        orchestratedArticleWorkflow.executePhase3_GenerateContent(state, noopStream);
    }

    private ArticleState buildInitialState(String taskId, String topic, AgentEvalRequest request) {
        ArticleState state = new ArticleState();
        state.setTaskId(taskId);
        state.setTopic(topic);
        state.setPlatform(defaultString(request == null ? null : request.getPlatform(), "default"));
        state.setStyle(defaultString(request == null ? null : request.getStyle(), "tech"));
        state.setWordRange(defaultString(request == null ? null : request.getWordRange(), "short"));
        state.setEnabledImageMethods(request == null ? null : request.getEnabledImageMethods());
        state.setRetrievedContext(null);
        return state;
    }

    private void autoSelectTitle(ArticleState state) {
        if (state.getTitleOptions() == null || state.getTitleOptions().isEmpty()) {
            throw new IllegalStateException("title options are empty");
        }
        ArticleState.TitleOption selected = state.getTitleOptions().get(0);
        ArticleState.TitleResult title = new ArticleState.TitleResult();
        title.setMainTitle(selected.getMainTitle());
        title.setSubTitle(selected.getSubTitle());
        state.setTitle(title);
    }

    private List<String> resolveTopics(AgentEvalRequest request) {
        List<String> topics = new ArrayList<>();
        if (request != null && request.getTopics() != null && !request.getTopics().isEmpty()) {
            topics.addAll(request.getTopics());
        } else {
            topics.addAll(loadDefaultTopics());
        }

        int limit = request != null && request.getLimit() != null && request.getLimit() > 0
                ? request.getLimit()
                : DEFAULT_LIMIT;
        return topics.stream()
                .filter(topic -> topic != null && !topic.isBlank())
                .limit(limit)
                .toList();
    }

    private List<String> loadDefaultTopics() {
        try {
            org.springframework.core.io.Resource resource = resourceLoader.getResource("classpath:eval/topics.json");
            try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                List<String> topics = GsonUtils.getInstance().fromJson(reader, new TypeToken<List<String>>() {}.getType());
                return topics == null ? fallbackTopics() : topics;
            }
        } catch (Exception e) {
            log.warn("AgentEval default topics load failed, using fallback topics", e);
            return fallbackTopics();
        }
    }

    private List<String> fallbackTopics() {
        return List.of(
                "AI Agent 如何重塑个人知识管理",
                "多智能体系统为什么需要可观测性",
                "如何用日志证明 AI 系统的稳定性"
        );
    }

    private List<com.sxxian.multiagentcreator.model.entity.AgentLog> safeLogs(String taskId) {
        try {
            return agentLogService.getLogsByTaskId(taskId);
        } catch (Exception e) {
            log.warn("AgentEval failed to read agent logs, taskId={}", taskId, e);
            return List.of();
        }
    }

    private String writeReport(String markdown, AgentEvalRequest request, String evalRunId) {
        String outputDir = resolveOutputDir(request);
        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            Path report = dir.resolve(evalRunId + ".md");
            Files.writeString(report, markdown, StandardCharsets.UTF_8);
            return report.toAbsolutePath().toString();
        } catch (Exception e) {
            throw new IllegalStateException("write AgentEval report failed: " + e.getMessage(), e);
        }
    }

    private String writeArticleArtifact(ArticleState state,
                                        AgentEvalRequest request,
                                        String evalRunId,
                                        AgentEvalMode mode,
                                        int sequence,
                                        boolean success,
                                        String errorMessage) {
        String outputDir = resolveOutputDir(request);
        try {
            Path dir = Path.of(outputDir).resolve(evalRunId).resolve("articles");
            Files.createDirectories(dir);
            String fileName = String.format("%02d-%s-%s.md", sequence, mode.value(), safeFilePart(state.getTopic()));
            Path articleFile = dir.resolve(fileName);
            Files.writeString(articleFile, buildArticleMarkdown(state, mode, success, errorMessage), StandardCharsets.UTF_8);
            return articleFile.toAbsolutePath().toString();
        } catch (Exception e) {
            log.warn("AgentEval failed to write article artifact, evalRunId={}, mode={}, topic={}",
                    evalRunId, mode.value(), state == null ? null : state.getTopic(), e);
            return null;
        }
    }

    private String buildArticleMarkdown(ArticleState state, AgentEvalMode mode, boolean success, String errorMessage) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(state.getTitle() != null && state.getTitle().getMainTitle() != null
                ? state.getTitle().getMainTitle()
                : state.getTopic()).append("\n\n");
        markdown.append("| 项目 | 值 |\n");
        markdown.append("| --- | --- |\n");
        markdown.append("| 分组 | ").append(mode.value()).append(" |\n");
        markdown.append("| taskId | ").append(state.getTaskId()).append(" |\n");
        markdown.append("| 主题 | ").append(nullToDash(state.getTopic())).append(" |\n");
        markdown.append("| 状态 | ").append(success ? "SUCCESS" : "FAILED").append(" |\n");
        markdown.append("| 错误 | ").append(errorMessage == null ? "-" : errorMessage.replace("\n", " ")).append(" |\n\n");

        if (state.getTitle() != null && state.getTitle().getSubTitle() != null && !state.getTitle().getSubTitle().isBlank()) {
            markdown.append("> ").append(state.getTitle().getSubTitle()).append("\n\n");
        }

        markdown.append("## Review Scores\n\n");
        markdown.append("| 阶段 | 分数 | 通过 |\n");
        markdown.append("| --- | ---: | --- |\n");
        reviewRow(markdown, "标题", state.getTitleReviewResult());
        reviewRow(markdown, "大纲", state.getOutlineReviewResult());
        reviewRow(markdown, "正文", state.getContentReviewResult());
        reviewRow(markdown, "配图计划", state.getImagePlanReviewResult());
        if (state.getImageReviewResults() != null) {
            for (int i = 0; i < state.getImageReviewResults().size(); i++) {
                reviewRow(markdown, "图片结果 " + (i + 1), state.getImageReviewResults().get(i));
            }
        }
        markdown.append("\n");

        markdown.append("## Outline\n\n");
        if (state.getOutline() != null && state.getOutline().getSections() != null) {
            for (ArticleState.OutlineSection section : state.getOutline().getSections()) {
                markdown.append("- ").append(section.getSection()).append(". ").append(nullToDash(section.getTitle())).append("\n");
                if (section.getPoints() != null) {
                    for (String point : section.getPoints()) {
                        markdown.append("  - ").append(point).append("\n");
                    }
                }
            }
        } else {
            markdown.append("-\n");
        }
        markdown.append("\n");

        markdown.append("## Image Artifacts\n\n");
        if (state.getImages() != null && !state.getImages().isEmpty()) {
            for (ArticleState.ImageResult image : state.getImages()) {
                markdown.append("- position=").append(image.getPosition())
                        .append(", method=").append(nullToDash(image.getMethod()))
                        .append(", url=").append(nullToDash(image.getUrl()))
                        .append("\n");
            }
        } else {
            markdown.append("-\n");
        }
        markdown.append("\n");

        markdown.append("## Final Article\n\n");
        String article = state.getFullContent();
        if (article == null || article.isBlank()) {
            article = state.getContent();
        }
        markdown.append(article == null || article.isBlank() ? "-" : article).append("\n");
        return markdown.toString();
    }

    private void reviewRow(StringBuilder markdown, String stage, com.sxxian.multiagentcreator.model.dto.review.ReviewResult result) {
        markdown.append("| ").append(stage).append(" | ")
                .append(result == null || result.getScore() == null ? "-" : result.getScore())
                .append(" | ")
                .append(result != null && result.isApprovedByThreshold() ? "是" : "否")
                .append(" |\n");
    }

    private String resolveOutputDir(AgentEvalRequest request) {
        return request != null && request.getOutputDir() != null && !request.getOutputDir().isBlank()
                ? request.getOutputDir()
                : "document/eval";
    }

    private String safeFilePart(String value) {
        String safe = value == null ? "untitled" : value.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        if (safe.length() > 40) {
            safe = safe.substring(0, 40);
        }
        return safe.isBlank() ? "untitled" : safe;
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private void waitForAsyncLogSave() {
        try {
            Thread.sleep(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String currentGitCommit() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();
            return exitCode == 0 && !output.isBlank() ? output : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
