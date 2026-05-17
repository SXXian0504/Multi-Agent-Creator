package com.sxxian.multiagentcreator.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.sxxian.multiagentcreator.context.StructuredOutputTraceContext;
import com.sxxian.multiagentcreator.exception.StructuredOutputException;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.dto.review.ReviewResult;
import com.sxxian.multiagentcreator.model.dto.structured.StructuredOutputMetrics;
import com.sxxian.multiagentcreator.model.enums.ImageMethodEnum;
import com.sxxian.multiagentcreator.model.enums.StructuredOutputTypeEnum;
import com.sxxian.multiagentcreator.utils.GsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 统一处理 LLM 结构化 JSON 输出。
 *
 * <p>MVP 不引入第三方 JSON Schema 校验库，先用根类型、必填字段和业务规则形成稳定闭环。</p>
 */
@Service
@Slf4j
public class JsonStructuredOutputService {

    private static final int DEFAULT_MAX_RETRIES = 1;

    public <T> T parse(String rawContent, Class<T> clazz, StructuredOutputTypeEnum outputType) {
        return parse(rawContent, clazz, outputType, null, DEFAULT_MAX_RETRIES);
    }

    public <T> T parse(String rawContent, Class<T> clazz, StructuredOutputTypeEnum outputType,
                       Supplier<String> retrySupplier, int maxRetries) {
        return parse(rawContent, (Type) clazz, outputType, retrySupplier, maxRetries);
    }

    public <T> T parse(String rawContent, TypeToken<T> typeToken, StructuredOutputTypeEnum outputType) {
        return parse(rawContent, typeToken, outputType, null, DEFAULT_MAX_RETRIES);
    }

    public <T> T parse(String rawContent, TypeToken<T> typeToken, StructuredOutputTypeEnum outputType,
                       Supplier<String> retrySupplier, int maxRetries) {
        return parse(rawContent, typeToken.getType(), outputType, retrySupplier, maxRetries);
    }

    private <T> T parse(String rawContent, Type targetType, StructuredOutputTypeEnum outputType,
                        Supplier<String> retrySupplier, int maxRetries) {
        int attempt = 0;
        String currentContent = rawContent;
        StructuredOutputException lastError = null;

        while (attempt <= Math.max(0, maxRetries)) {
            ParseAttempt<T> result = tryParseOnce(currentContent, targetType, outputType, attempt);
            StructuredOutputTraceContext.add(result.metrics);

            if (result.value != null) {
                return result.value;
            }

            lastError = result.error;
            if (retrySupplier == null || attempt >= maxRetries) {
                break;
            }

            attempt++;
            log.warn("结构化输出解析失败，准备重试原 Agent, outputType={}, retryCount={}, reason={}",
                    outputType.getValue(), attempt, lastError.getMessage());
            currentContent = retrySupplier.get();
        }

        throw lastError != null ? lastError : new StructuredOutputException(outputType.getValue() + "解析失败");
    }

    private <T> ParseAttempt<T> tryParseOnce(String rawContent, Type targetType,
                                             StructuredOutputTypeEnum outputType, int retryCount) {
        int repairCount = 0;
        boolean parseSuccess = false;
        boolean schemaValid = false;
        boolean businessValid = false;
        String errorMessage = null;

        try {
            JsonExtraction extraction = extractJson(rawContent, outputType);
            repairCount = extraction.repaired ? 1 : 0;

            JsonElement jsonElement = normalizeRoot(JsonParser.parseString(extraction.json), outputType);
            validateRootType(jsonElement, outputType);
            schemaValid = true;

            T value = GsonUtils.fromJson(jsonElement.toString(), targetType);
            parseSuccess = true;

            validateBusinessRules(value, jsonElement, outputType);
            businessValid = true;

            StructuredOutputMetrics metrics = buildMetrics(
                    outputType, true, true, true, repairCount, retryCount, null);
            log.info("结构化输出解析成功, outputType={}, repairCount={}, retryCount={}",
                    outputType.getValue(), repairCount, retryCount);
            return ParseAttempt.success(value, metrics);
        } catch (Exception e) {
            errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            StructuredOutputException error = e instanceof StructuredOutputException structuredOutputException
                    ? structuredOutputException
                    : new StructuredOutputException(outputType.getValue() + "解析失败: " + errorMessage, e);

            StructuredOutputMetrics metrics = buildMetrics(
                    outputType, parseSuccess, schemaValid, businessValid, repairCount, retryCount, errorMessage);
            log.warn("结构化输出解析失败, outputType={}, parseSuccess={}, schemaValid={}, businessValid={}, retryCount={}, error={}",
                    outputType.getValue(), parseSuccess, schemaValid, businessValid, retryCount, errorMessage);
            return ParseAttempt.failure(error, metrics);
        }
    }

    private JsonExtraction extractJson(String rawContent, StructuredOutputTypeEnum outputType) {
        if (rawContent == null || rawContent.isBlank()) {
            throw new StructuredOutputException(outputType.getValue() + "原始输出为空");
        }

        String trimmed = rawContent.trim();
        String fenced = stripSingleJsonFence(trimmed);
        if (!fenced.equals(trimmed)) {
            return new JsonExtraction(fenced, true);
        }

        String extracted = extractBalancedJson(trimmed, outputType.getRootType());
        if (extracted == null) {
            throw new StructuredOutputException(outputType.getValue() + "未找到完整 JSON");
        }

        return new JsonExtraction(extracted, !extracted.equals(trimmed));
    }

    private String stripSingleJsonFence(String content) {
        if (!content.startsWith("```")) {
            return content;
        }

        int firstLineEnd = content.indexOf('\n');
        int lastFence = content.lastIndexOf("```");
        if (firstLineEnd < 0 || lastFence <= firstLineEnd) {
            return content;
        }

        String firstLine = content.substring(0, firstLineEnd).trim();
        if (!"```".equals(firstLine) && !"```json".equalsIgnoreCase(firstLine)) {
            return content;
        }

        return content.substring(firstLineEnd + 1, lastFence).trim();
    }

    private String extractBalancedJson(String content, StructuredOutputTypeEnum.RootType rootType) {
        char open = rootType == StructuredOutputTypeEnum.RootType.ARRAY ? '[' : '{';
        char close = rootType == StructuredOutputTypeEnum.RootType.ARRAY ? ']' : '}';
        int start = content.indexOf(open);
        if (start < 0) {
            return null;
        }

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;

        for (int i = start; i < content.length(); i++) {
            char current = content.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }

            if (current == open) {
                depth++;
            } else if (current == close) {
                depth--;
                if (depth == 0) {
                    return content.substring(start, i + 1).trim();
                }
            }
        }

        return null;
    }

    private void validateRootType(JsonElement jsonElement, StructuredOutputTypeEnum outputType) {
        boolean valid = switch (outputType.getRootType()) {
            case ARRAY -> jsonElement.isJsonArray();
            case OBJECT -> jsonElement.isJsonObject();
        };
        if (!valid) {
            throw new StructuredOutputException(outputType.getValue() + "根节点类型不匹配");
        }
    }

    private JsonElement normalizeRoot(JsonElement jsonElement, StructuredOutputTypeEnum outputType) {
        if (matchesRootType(jsonElement, outputType)) {
            return unwrapNamedRoot(jsonElement, outputType);
        }

        JsonElement unwrapped = unwrapNamedRoot(jsonElement, outputType);
        if (matchesRootType(unwrapped, outputType)) {
            return unwrapped;
        }

        if (outputType.getRootType() == StructuredOutputTypeEnum.RootType.OBJECT
                && jsonElement.isJsonArray()
                && jsonElement.getAsJsonArray().size() == 1
                && jsonElement.getAsJsonArray().get(0).isJsonObject()) {
            return jsonElement.getAsJsonArray().get(0);
        }

        return jsonElement;
    }

    private JsonElement unwrapNamedRoot(JsonElement jsonElement, StructuredOutputTypeEnum outputType) {
        if (!jsonElement.isJsonObject()) {
            return jsonElement;
        }
        JsonElement namedValue = jsonElement.getAsJsonObject().get(outputType.getValue());
        return namedValue != null ? namedValue : jsonElement;
    }

    private boolean matchesRootType(JsonElement jsonElement, StructuredOutputTypeEnum outputType) {
        return switch (outputType.getRootType()) {
            case ARRAY -> jsonElement.isJsonArray();
            case OBJECT -> jsonElement.isJsonObject();
        };
    }

    private void validateBusinessRules(Object value, JsonElement jsonElement, StructuredOutputTypeEnum outputType) {
        switch (outputType) {
            case TITLE_OPTIONS -> validateTitleOptions(value);
            case OUTLINE_RESULT -> validateOutlineResult(value);
            case IMAGE_PLAN -> validateImagePlan(value);
            case REVIEW_RESULT, IMAGE_REVIEW_RESULT -> {
                if (!jsonElement.isJsonObject()) {
                    throw new StructuredOutputException(outputType.getValue() + "必须是 JSON 对象");
                }
                validateReviewResult(value, outputType);
            }
        }
    }

    private void validateReviewResult(Object value, StructuredOutputTypeEnum outputType) {
        if (!(value instanceof ReviewResult reviewResult)) {
            throw new StructuredOutputException("评审结果结构错误");
        }
        if (reviewResult.getApproved() == null) {
            throw new StructuredOutputException("评审结果 approved 不能为空");
        }
        if (reviewResult.getScore() == null || reviewResult.getScore() < 0 || reviewResult.getScore() > 100) {
            throw new StructuredOutputException("评审结果 score 必须在 0-100 之间");
        }
        if (reviewResult.getProblems() == null) {
            throw new StructuredOutputException("评审结果 problems 不能为空");
        }
        if (reviewResult.getSuggestions() == null) {
            throw new StructuredOutputException("评审结果 suggestions 不能为空");
        }
        if (isBlank(reviewResult.getNextAction())) {
            throw new StructuredOutputException("评审结果 nextAction 不能为空");
        }
        if (outputType == StructuredOutputTypeEnum.REVIEW_RESULT) {
            validateTextReviewDimensionScores(reviewResult);
        }
    }

    private void validateTextReviewDimensionScores(ReviewResult reviewResult) {
        if (reviewResult.getDimensionScores() == null) {
            throw new StructuredOutputException("文本评审 dimensionScores 不能为空");
        }
        Integer commonBaseline = reviewResult.getDimensionScores().get("commonBaseline");
        Integer styleFit = reviewResult.getDimensionScores().get("styleFit");
        Integer stageFit = reviewResult.getDimensionScores().get("stageFit");
        if (commonBaseline == null || styleFit == null || stageFit == null) {
            throw new StructuredOutputException("文本评审 dimensionScores 必须包含 commonBaseline/styleFit/stageFit");
        }
        if (commonBaseline < 0 || commonBaseline > 40
                || styleFit < 0 || styleFit > 45
                || stageFit < 0 || stageFit > 15) {
            throw new StructuredOutputException("文本评审 dimensionScores 分值超出范围");
        }
        int total = commonBaseline + styleFit + stageFit;
        if (reviewResult.getScore() == null || total != reviewResult.getScore()) {
            throw new StructuredOutputException("文本评审 dimensionScores 之和必须等于 score");
        }
        if (reviewResult.getScore() < 95
                && (reviewResult.getProblems().isEmpty() || reviewResult.getSuggestions().isEmpty())) {
            throw new StructuredOutputException("文本评审 score 低于 95 时 problems/suggestions 不能为空");
        }
    }

    @SuppressWarnings("unchecked")
    private void validateTitleOptions(Object value) {
        if (!(value instanceof List<?> titleOptions)) {
            throw new StructuredOutputException("标题候选必须是数组");
        }
        if (titleOptions.size() < 3 || titleOptions.size() > 5) {
            throw new StructuredOutputException("标题候选数量必须为 3-5 个");
        }
        for (Object item : titleOptions) {
            if (!(item instanceof ArticleState.TitleOption option)) {
                throw new StructuredOutputException("标题候选结构错误");
            }
            if (isBlank(option.getMainTitle()) || isBlank(option.getSubTitle())) {
                throw new StructuredOutputException("标题候选 mainTitle/subTitle 不能为空");
            }
            if (option.getMainTitle().trim().length() > 30) {
                throw new StructuredOutputException("标题候选 mainTitle 不能超过 30 字");
            }
        }
    }

    private void validateOutlineResult(Object value) {
        if (!(value instanceof ArticleState.OutlineResult outlineResult)) {
            throw new StructuredOutputException("大纲结构错误");
        }
        List<ArticleState.OutlineSection> sections = outlineResult.getSections();
        if (sections == null || sections.isEmpty()) {
            throw new StructuredOutputException("大纲 sections 不能为空");
        }

        for (int i = 0; i < sections.size(); i++) {
            ArticleState.OutlineSection section = sections.get(i);
            int expectedSectionNo = i + 1;
            if (section.getSection() == null || section.getSection() != expectedSectionNo) {
                throw new StructuredOutputException("大纲章节编号必须从 1 连续递增");
            }
            if (isBlank(section.getTitle())) {
                throw new StructuredOutputException("大纲章节标题不能为空");
            }
            if (section.getPoints() == null || section.getPoints().isEmpty()) {
                throw new StructuredOutputException("大纲章节 points 不能为空");
            }
            for (String point : section.getPoints()) {
                if (isBlank(point)) {
                    throw new StructuredOutputException("大纲章节 point 不能为空");
                }
            }
        }
    }

    private void validateImagePlan(Object value) {
        if (!(value instanceof ArticleState.Agent4Result agent4Result)) {
            throw new StructuredOutputException("配图计划结构错误");
        }
        if (isBlank(agent4Result.getContentWithPlaceholders())) {
            throw new StructuredOutputException("配图计划 contentWithPlaceholders 不能为空");
        }
        List<ArticleState.ImageRequirement> requirements = agent4Result.getImageRequirements();
        if (requirements == null || requirements.isEmpty()) {
            throw new StructuredOutputException("配图计划 imageRequirements 不能为空");
        }

        Set<Integer> positions = new HashSet<>();
        for (ArticleState.ImageRequirement requirement : requirements) {
            validateImageRequirement(requirement, positions, agent4Result.getContentWithPlaceholders());
        }
    }

    private void validateImageRequirement(ArticleState.ImageRequirement requirement, Set<Integer> positions,
                                          String contentWithPlaceholders) {
        if (requirement == null) {
            throw new StructuredOutputException("配图需求不能为空");
        }
        if (requirement.getPosition() == null || requirement.getPosition() < 1) {
            throw new StructuredOutputException("配图需求 position 必须大于 0");
        }
        if (!positions.add(requirement.getPosition())) {
            throw new StructuredOutputException("配图需求 position 不能重复");
        }
        if (isBlank(requirement.getType())) {
            throw new StructuredOutputException("配图需求 type 不能为空");
        }
        ImageMethodEnum imageMethod = ImageMethodEnum.getByValue(requirement.getImageSource());
        if (imageMethod == null) {
            throw new StructuredOutputException("配图需求 imageSource 枚举错误: " + requirement.getImageSource());
        }

        if (requirement.getPosition() > 1 && !isBlank(requirement.getPlaceholderId())
                && !contentWithPlaceholders.contains(requirement.getPlaceholderId())) {
            throw new StructuredOutputException("配图需求 placeholderId 未出现在正文中: " + requirement.getPlaceholderId());
        }
        if (imageMethod.isAiGenerated() && isBlank(requirement.getPrompt())) {
            throw new StructuredOutputException("AI 配图方式 prompt 不能为空");
        }
        if (!imageMethod.isAiGenerated() && !imageMethod.isFallback() && isBlank(requirement.getKeywords())) {
            throw new StructuredOutputException("检索型配图方式 keywords 不能为空");
        }
    }

    private StructuredOutputMetrics buildMetrics(StructuredOutputTypeEnum outputType, boolean parseSuccess,
                                                 boolean schemaValid, boolean businessValid, int repairCount,
                                                 int retryCount, String errorMessage) {
        return StructuredOutputMetrics.builder()
                .outputType(outputType.getValue())
                .parseSuccess(parseSuccess)
                .schemaValid(schemaValid)
                .businessValid(businessValid)
                .repairCount(repairCount)
                .retryCount(retryCount)
                .errorMessage(errorMessage)
                .build();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record JsonExtraction(String json, boolean repaired) {
    }

    private record ParseAttempt<T>(T value, StructuredOutputException error, StructuredOutputMetrics metrics) {

        private static <T> ParseAttempt<T> success(T value, StructuredOutputMetrics metrics) {
            return new ParseAttempt<>(value, null, metrics);
        }

        private static <T> ParseAttempt<T> failure(StructuredOutputException error, StructuredOutputMetrics metrics) {
            return new ParseAttempt<>(null, error, metrics);
        }
    }
}
