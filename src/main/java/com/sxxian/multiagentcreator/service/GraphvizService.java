package com.sxxian.multiagentcreator.service;

import cn.hutool.core.io.FileUtil;
import com.sxxian.multiagentcreator.config.GraphvizConfig;
import com.sxxian.multiagentcreator.model.dto.image.ImageData;
import com.sxxian.multiagentcreator.model.dto.image.ImageRequest;
import com.sxxian.multiagentcreator.model.enums.ImageMethodEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.sxxian.multiagentcreator.constant.ArticleConstant.PICSUM_URL_TEMPLATE;

@Service
@Slf4j
public class GraphvizService implements ImageSearchService {

    private static final Pattern GRAPH_LAYOUT_ATTR_PATTERN = Pattern.compile(
            "(?i)\\b(rankdir|size|ratio|page|viewport|bb|margin|pad|nodesep|ranksep|splines|overlap|outputorder)\\s*=\\s*(\"[^\"]*\"|[^,;\\]\\s]+)\\s*,?");

    private static final Pattern ABSOLUTE_POSITION_ATTR_PATTERN = Pattern.compile(
            "(?i)\\b(pos|pin)\\s*=\\s*(\"[^\"]*\"|[^,;\\]\\s]+)\\s*,?");

    @Resource
    private GraphvizConfig graphvizConfig;

    @Override
    public String searchImage(String keywords) {
        return null;
    }

    @Override
    public String getImage(ImageRequest request) {
        return null;
    }

    @Override
    public ImageData getImageData(ImageRequest request) {
        String dotCode = request.getEffectiveParam(true);
        return generateDiagramData(dotCode);
    }

    public ImageData generateDiagramData(String dotCode) {
        if (dotCode == null || dotCode.trim().isEmpty()) {
            log.warn("Graphviz DOT code is empty");
            return null;
        }

        File tempInputFile = null;
        File tempOutputFile = null;
        String sanitizedDotCode = "";
        try {
            sanitizedDotCode = sanitizeDotCode(dotCode);
            if (sanitizedDotCode.isBlank()) {
                log.warn("Graphviz DOT code is invalid after sanitization");
                return null;
            }

            tempInputFile = FileUtil.createTempFile("graphviz_input_", ".dot", true);
            FileUtil.writeUtf8String(sanitizedDotCode, tempInputFile);

            String outputFormat = normalizeOutputFormat(graphvizConfig.getOutputFormat());
            tempOutputFile = FileUtil.createTempFile("graphviz_output_", "." + outputFormat, true);

            try {
                convertDotToImage(tempInputFile, tempOutputFile, outputFormat);
            } catch (Exception firstFailure) {
                String fallbackDotCode = sanitizeDotCode(dotCode, false);
                if (fallbackDotCode.isBlank() || fallbackDotCode.equals(sanitizedDotCode)) {
                    throw firstFailure;
                }
                log.warn("Graphviz render failed after layout cleanup, retrying with style-only DOT. firstError={}",
                        firstFailure.getMessage());
                sanitizedDotCode = fallbackDotCode;
                FileUtil.writeUtf8String(sanitizedDotCode, tempInputFile);
                convertDotToImage(tempInputFile, tempOutputFile, outputFormat);
            }

            if (!tempOutputFile.exists() || tempOutputFile.length() == 0) {
                log.error("Graphviz CLI produced no output");
                return null;
            }

            byte[] imageBytes = FileUtil.readBytes(tempOutputFile);
            log.info("Graphviz diagram generated, format={}, size={} bytes", outputFormat, imageBytes.length);
            return ImageData.fromBytes(imageBytes, getMimeType(outputFormat));
        } catch (Exception e) {
            log.error("Graphviz diagram generation failed, sanitizedDotWithLineNumbers=\n{}",
                    toLineNumberedSnippet(sanitizedDotCode, 120), e);
            return null;
        } finally {
            if (tempInputFile != null) {
                FileUtil.del(tempInputFile);
            }
            if (tempOutputFile != null) {
                FileUtil.del(tempOutputFile);
            }
        }
    }

    static String sanitizeDotCode(String dotCode) {
        return sanitizeDotCode(dotCode, true);
    }

    private static String sanitizeDotCode(String dotCode, boolean removeLayoutOverrides) {
        String code = stripMarkdownFence(dotCode);
        if (!isDotGraph(code)) {
            return "";
        }
        if (removeLayoutOverrides) {
            code = removeLayoutOverrides(code);
        }
        return injectDefaultStyle(code);
    }

    private static String removeLayoutOverrides(String code) {
        String withoutGraphLayoutOverrides = GRAPH_LAYOUT_ATTR_PATTERN.matcher(code).replaceAll("");
        String withoutPositionOverrides = ABSOLUTE_POSITION_ATTR_PATTERN.matcher(withoutGraphLayoutOverrides).replaceAll("");
        return normalizeDotAttributeLists(withoutPositionOverrides);
    }

    private static String normalizeDotAttributeLists(String code) {
        String normalized = code
                .replaceAll(",\\s*]", "]")
                .replaceAll("\\[\\s*,", "[")
                .replaceAll("(?is)\\b(graph|node|edge)\\s*\\[\\s*]\\s*;?", "")
                .replaceAll("\\[\\s*]\\s*;", ";")
                .replaceAll("(?m)^\\s*,\\s*$", "")
                .replaceAll("(?m)^\\s*;\\s*$", "");
        return normalized.trim();
    }

    private static String injectDefaultStyle(String code) {
        int openBraceIndex = code.indexOf('{');
        if (openBraceIndex < 0) {
            return code;
        }
        if (code.contains("multi-agent-creator graphviz defaults")) {
            return code;
        }

        String defaultStyle = """

                  // multi-agent-creator graphviz defaults
                  graph [
                    rankdir=LR,
                    pad="0.25",
                    margin="0.08",
                    nodesep="0.45",
                    ranksep="0.65",
                    splines=ortho,
                    outputorder=edgesfirst,
                    overlap=false
                  ];
                  node [
                    shape=rect,
                    style="rounded,filled",
                    fillcolor="#F8FAFC",
                    color="#2563EB",
                    penwidth=2,
                    fontname="Microsoft YaHei, Arial, sans-serif",
                    fontsize=18,
                    fontcolor="#0F172A",
                    margin="0.18,0.12",
                    height=0.55
                  ];
                  edge [
                    color="#475569",
                    penwidth=2,
                    arrowsize=0.8,
                    fontname="Microsoft YaHei, Arial, sans-serif",
                    fontsize=14,
                    fontcolor="#334155"
                  ];
                """;
        return code.substring(0, openBraceIndex + 1)
                + defaultStyle
                + code.substring(openBraceIndex + 1);
    }

    private static String stripMarkdownFence(String dotCode) {
        String code = dotCode == null ? "" : dotCode.trim();
        if (!code.startsWith("```")) {
            return code;
        }

        int firstLineEnd = code.indexOf('\n');
        if (firstLineEnd < 0) {
            return code;
        }
        int fenceEnd = code.lastIndexOf("```");
        if (fenceEnd <= firstLineEnd) {
            return code.substring(firstLineEnd + 1).trim();
        }
        return code.substring(firstLineEnd + 1, fenceEnd).trim();
    }

    private static boolean isDotGraph(String code) {
        String lower = code == null ? "" : code.stripLeading().toLowerCase(Locale.ROOT);
        return startsWithDotKeyword(lower, "digraph")
                || startsWithDotKeyword(lower, "graph")
                || startsWithDotKeyword(lower, "strict digraph")
                || startsWithDotKeyword(lower, "strict graph");
    }

    private static boolean startsWithDotKeyword(String code, String keyword) {
        if (!code.startsWith(keyword)) {
            return false;
        }
        if (code.length() == keyword.length()) {
            return false;
        }
        char next = code.charAt(keyword.length());
        return Character.isWhitespace(next) || next == '{';
    }

    private void convertDotToImage(File inputFile, File outputFile, String outputFormat) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                graphvizConfig.getCliCommand(),
                "-T" + outputFormat,
                "-Gbgcolor=" + graphvizConfig.getBackgroundColor(),
                inputFile.getAbsolutePath(),
                "-o",
                outputFile.getAbsolutePath()
        );
        builder.redirectErrorStream(true);
        log.info("Executing Graphviz CLI command: {}", builder.command());

        Process process = builder.start();
        boolean finished = process.waitFor(graphvizConfig.getTimeout(), TimeUnit.MILLISECONDS);
        String logs = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Graphviz CLI timed out after " + graphvizConfig.getTimeout() + " ms");
        }

        int exitCode = process.exitValue();
        log.info("Graphviz CLI exitCode={}", exitCode);
        if (!logs.isBlank()) {
            log.info("Graphviz CLI logs:\n{}", logs);
        }
        if (exitCode != 0) {
            throw new RuntimeException("Graphviz CLI failed:\n" + logs);
        }
    }

    private static String toLineNumberedSnippet(String code, int maxLines) {
        if (code == null || code.isBlank()) {
            return "";
        }
        String[] lines = code.split("\\R", -1);
        int limit = Math.min(lines.length, maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            sb.append(String.format("%03d: %s%n", i + 1, lines[i]));
        }
        if (lines.length > limit) {
            sb.append("... ").append(lines.length - limit).append(" more lines");
        }
        return sb.toString();
    }

    private String normalizeOutputFormat(String format) {
        if (format == null || format.isBlank()) {
            return "svg";
        }
        return format.toLowerCase(Locale.ROOT);
    }

    private String getMimeType(String format) {
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "png" -> "image/png";
            case "svg" -> "image/svg+xml";
            case "pdf" -> "application/pdf";
            default -> "image/svg+xml";
        };
    }

    @Override
    public ImageMethodEnum getMethod() {
        return ImageMethodEnum.GRAPHVIZ;
    }

    @Override
    public String getFallbackImage(int position) {
        return String.format(PICSUM_URL_TEMPLATE, position);
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder builder = new ProcessBuilder(graphvizConfig.getCliCommand(), "-V");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean finished = process.waitFor(5000, TimeUnit.MILLISECONDS);
            String logs = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            log.info("Graphviz CLI version: {}", logs.trim());
            return process.exitValue() == 0;
        } catch (Exception e) {
            log.warn("Graphviz CLI is unavailable: {}", e.getMessage());
            return false;
        }
    }
}
