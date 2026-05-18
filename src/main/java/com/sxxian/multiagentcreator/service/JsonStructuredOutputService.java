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
            log.warn("structured output parse failed, retrying, outputType={}, retryCount={}, reason={}",
                    outputType.getValue(), attempt, lastError.getMessage());
            currentContent = retrySupplier.get();
        }
        throw lastError != null ? lastError : new StructuredOutputException(outputType.getValue() + " parse failed");
    }

    private <T> ParseAttempt<T> tryParseOnce(String rawContent, Type targetType,
                                             StructuredOutputTypeEnum outputType, int retryCount) {
        int repairCount = 0;
        boolean parseSuccess = false;
        boolean schemaValid = false;
        boolean businessValid = false;
        String errorMessage;
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
            StructuredOutputMetrics metrics = buildMetrics(outputType, true, true, true, repairCount, retryCount, null);
            log.info("structured output parse succeeded, outputType={}, repairCount={}, retryCount={}",
                    outputType.getValue(), repairCount, retryCount);
            return ParseAttempt.success(value, metrics);
        } catch (Exception e) {
            errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            StructuredOutputException error = e instanceof StructuredOutputException structuredOutputException
                    ? structuredOutputException
                    : new StructuredOutputException(outputType.getValue() + " parse failed: " + errorMessage, e);
            StructuredOutputMetrics metrics = buildMetrics(outputType, parseSuccess, schemaValid, businessValid,
                    repairCount, retryCount, errorMessage);
            log.warn("structured output parse failed, outputType={}, parseSuccess={}, schemaValid={}, businessValid={}, retryCount={}, error={}",
                    outputType.getValue(), parseSuccess, schemaValid, businessValid, retryCount, errorMessage);
            return ParseAttempt.failure(error, metrics);
        }
    }

    private JsonExtraction extractJson(String rawContent, StructuredOutputTypeEnum outputType) {
        if (rawContent == null || rawContent.isBlank()) {
            throw new StructuredOutputException(outputType.getValue() + " content cannot be empty");
        }
        String trimmed = rawContent.trim();
        String fenced = stripSingleJsonFence(trimmed);
        if (!fenced.equals(trimmed)) {
            return new JsonExtraction(fenced, true);
        }
        String extracted = extractBalancedJson(trimmed, outputType.getRootType());
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
            throw new StructuredOutputException("JSON root not found");
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < content.length(); i++) {
            char current = content.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (current == '\\') {
                escape = true;
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
        throw new StructuredOutputException("balanced JSON root not found");
    }

    private JsonElement normalizeRoot(JsonElement jsonElement, StructuredOutputTypeEnum outputType) {
        JsonElement unwrapped = unwrapNamedRoot(jsonElement, outputType);
        if (matchesRootType(unwrapped, outputType)) {
            return unwrapped;
        }
        if (outputType.getRootType() == StructuredOutputTypeEnum.RootType.OBJECT
                && unwrapped.isJsonArray() && unwrapped.getAsJsonArray().size() == 1
                && unwrapped.getAsJsonArray().get(0).isJsonObject()) {
            return unwrapped.getAsJsonArray().get(0);
        }
        return unwrapped;
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

    private void validateRootType(JsonElement jsonElement, StructuredOutputTypeEnum outputType) {
        if (!matchesRootType(jsonElement, outputType)) {
            throw new StructuredOutputException(outputType.getValue() + " root type is invalid");
        }
    }

    private void validateBusinessRules(Object value, JsonElement jsonElement, StructuredOutputTypeEnum outputType) {
        switch (outputType) {
            case TITLE_OPTIONS -> validateTitleOptions(value);
            case OUTLINE_RESULT -> validateOutlineResult(value);
            case IMAGE_PLAN -> validateImagePlan(value);
            case OUTLINE_IMAGE_PLAN -> validateOutlineImagePlan(value);
            case REVIEW_RESULT, IMAGE_REVIEW_RESULT -> {
                if (!jsonElement.isJsonObject()) {
                    throw new StructuredOutputException(outputType.getValue() + " must be a JSON object");
                }
                validateReviewResult(value, outputType);
            }
        }
    }

    private void validateReviewResult(Object value, StructuredOutputTypeEnum outputType) {
        if (!(value instanceof ReviewResult reviewResult)) {
            throw new StructuredOutputException("review result structure is invalid");
        }
        if (reviewResult.getApproved() == null) {
            throw new StructuredOutputException("review result approved cannot be empty");
        }
        if (reviewResult.getScore() == null || reviewResult.getScore() < 0 || reviewResult.getScore() > 100) {
            throw new StructuredOutputException("review result score must be between 0 and 100");
        }
        if (reviewResult.getProblems() == null) {
            throw new StructuredOutputException("review result problems cannot be null");
        }
        if (reviewResult.getSuggestions() == null) {
            throw new StructuredOutputException("review result suggestions cannot be null");
        }
        if (isBlank(reviewResult.getNextAction())) {
            throw new StructuredOutputException("review result nextAction cannot be empty");
        }
        if (outputType == StructuredOutputTypeEnum.IMAGE_REVIEW_RESULT) {
            return;
        }
        if (reviewResult.getDimensionScores() == null || reviewResult.getDimensionScores().isEmpty()) {
            throw new StructuredOutputException("text review dimensionScores cannot be empty");
        }
        Integer commonBaseline = reviewResult.getDimensionScores().get("commonBaseline");
        Integer styleFit = reviewResult.getDimensionScores().get("styleFit");
        Integer stageFit = reviewResult.getDimensionScores().get("stageFit");
        if (commonBaseline == null || styleFit == null || stageFit == null) {
            throw new StructuredOutputException("text review dimensionScores must contain commonBaseline/styleFit/stageFit");
        }
        if (commonBaseline < 0 || commonBaseline > 40 || styleFit < 0 || styleFit > 45 || stageFit < 0 || stageFit > 15) {
            throw new StructuredOutputException("review dimensionScores are out of range");
        }
        int total = commonBaseline + styleFit + stageFit;
        if (reviewResult.getScore() == null || total != reviewResult.getScore()) {
            log.warn("text review score normalized from dimensionScores, originalScore={}, normalizedScore={}",
                    reviewResult.getScore(), total);
            reviewResult.setScore(total);
            reviewResult.setApproved(total >= 80);
        }
        if (reviewResult.getScore() < 95 && (reviewResult.getProblems().isEmpty() || reviewResult.getSuggestions().isEmpty())) {
            throw new StructuredOutputException("text review problems/suggestions cannot be empty when score is below 95");
        }
    }

    @SuppressWarnings("unchecked")
    private void validateTitleOptions(Object value) {
        if (!(value instanceof List<?> titleOptions)) {
            throw new StructuredOutputException("title options must be an array");
        }
        if (titleOptions.size() < 3 || titleOptions.size() > 5) {
            throw new StructuredOutputException("title option count must be 3-5");
        }
        for (Object item : titleOptions) {
            if (!(item instanceof ArticleState.TitleOption option)) {
                throw new StructuredOutputException("title option structure is invalid");
            }
            if (isBlank(option.getMainTitle()) || isBlank(option.getSubTitle())) {
                throw new StructuredOutputException("title option mainTitle/subTitle cannot be empty");
            }
            if (option.getMainTitle().trim().length() > 30) {
                throw new StructuredOutputException("title option mainTitle must not exceed 30 characters");
            }
        }
    }

    private void validateOutlineResult(Object value) {
        if (!(value instanceof ArticleState.OutlineResult outlineResult)) {
            throw new StructuredOutputException("outline structure is invalid");
        }
        List<ArticleState.OutlineSection> sections = outlineResult.getSections();
        if (sections == null || sections.isEmpty()) {
            throw new StructuredOutputException("outline sections cannot be empty");
        }
        for (int i = 0; i < sections.size(); i++) {
            ArticleState.OutlineSection section = sections.get(i);
            int expectedSectionNo = i + 1;
            if (section.getSection() == null || section.getSection() != expectedSectionNo) {
                throw new StructuredOutputException("outline section numbers must be continuous from 1");
            }
            if (isBlank(section.getTitle())) {
                throw new StructuredOutputException("outline section title cannot be empty");
            }
            if (section.getPoints() == null || section.getPoints().isEmpty()) {
                throw new StructuredOutputException("outline section points cannot be empty");
            }
            for (String point : section.getPoints()) {
                if (isBlank(point)) {
                    throw new StructuredOutputException("outline section point cannot be empty");
                }
            }
        }
    }

    private void validateImagePlan(Object value) {
        if (!(value instanceof ArticleState.Agent4Result agent4Result)) {
            throw new StructuredOutputException("image plan structure is invalid");
        }
        if (isBlank(agent4Result.getContentWithPlaceholders())) {
            throw new StructuredOutputException("image plan contentWithPlaceholders cannot be empty");
        }
        List<ArticleState.ImageRequirement> requirements = agent4Result.getImageRequirements();
        if (requirements == null || requirements.isEmpty()) {
            throw new StructuredOutputException("image plan imageRequirements cannot be empty");
        }
        Set<Integer> positions = new HashSet<>();
        for (ArticleState.ImageRequirement requirement : requirements) {
            validateImageRequirement(requirement, positions, agent4Result.getContentWithPlaceholders());
        }
    }

    private void validateOutlineImagePlan(Object value) {
        if (!(value instanceof ArticleState.Agent4Result agent4Result)) {
            throw new StructuredOutputException("outline image plan structure is invalid");
        }
        List<ArticleState.ImageRequirement> requirements = agent4Result.getImageRequirements();
        if (requirements == null || requirements.isEmpty()) {
            throw new StructuredOutputException("outline image plan imageRequirements cannot be empty");
        }
        Set<Integer> positions = new HashSet<>();
        for (ArticleState.ImageRequirement requirement : requirements) {
            validateImageRequirement(requirement, positions, null);
        }
    }

    private void validateImageRequirement(ArticleState.ImageRequirement requirement, Set<Integer> positions,
                                          String contentWithPlaceholders) {
        if (requirement == null) {
            throw new StructuredOutputException("image requirement cannot be null");
        }
        if (requirement.getPosition() == null || requirement.getPosition() < 1) {
            throw new StructuredOutputException("image requirement position must be greater than 0");
        }
        if (!positions.add(requirement.getPosition())) {
            throw new StructuredOutputException("image requirement position cannot be duplicated");
        }
        if (isBlank(requirement.getType())) {
            throw new StructuredOutputException("image requirement type cannot be empty");
        }
        if (isBlank(requirement.getReason())) {
            throw new StructuredOutputException("image requirement reason cannot be empty");
        }
        ImageMethodEnum imageMethod = ImageMethodEnum.getByValue(requirement.getImageSource());
        if (imageMethod == null) {
            throw new StructuredOutputException("image requirement imageSource enum is invalid: " + requirement.getImageSource());
        }
        if (!isBlank(contentWithPlaceholders) && requirement.getPosition() > 1 && !isBlank(requirement.getPlaceholderId())
                && !contentWithPlaceholders.contains(requirement.getPlaceholderId())) {
            throw new StructuredOutputException("image requirement placeholderId is not present in content: " + requirement.getPlaceholderId());
        }
        if (imageMethod.isAiGenerated() && isBlank(requirement.getPrompt())) {
            throw new StructuredOutputException("AI image method prompt cannot be empty");
        }
        if (!imageMethod.isAiGenerated() && !imageMethod.isFallback() && isBlank(requirement.getKeywords())) {
            throw new StructuredOutputException("search image method keywords cannot be empty");
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
