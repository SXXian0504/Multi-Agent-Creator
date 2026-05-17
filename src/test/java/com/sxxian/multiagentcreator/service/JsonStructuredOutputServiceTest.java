package com.sxxian.multiagentcreator.service;

import com.google.gson.reflect.TypeToken;
import com.sxxian.multiagentcreator.exception.StructuredOutputException;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.dto.review.ImageReviewResult;
import com.sxxian.multiagentcreator.model.dto.review.ReviewResult;
import com.sxxian.multiagentcreator.model.enums.ArticleStyleEnum;
import com.sxxian.multiagentcreator.model.enums.StructuredOutputTypeEnum;
import com.sxxian.multiagentcreator.utils.ArticlePromptUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonStructuredOutputServiceTest {

    private final JsonStructuredOutputService service = new JsonStructuredOutputService();

    @Test
    void parsesJsonFromMarkdownFence() {
        String raw = """
                ```json
                [
                  {"mainTitle":"标题一","subTitle":"副标题一"},
                  {"mainTitle":"标题二","subTitle":"副标题二"},
                  {"mainTitle":"标题三","subTitle":"副标题三"}
                ]
                ```
                """;

        List<ArticleState.TitleOption> result = service.parse(
                raw,
                new TypeToken<List<ArticleState.TitleOption>>() {},
                StructuredOutputTypeEnum.TITLE_OPTIONS
        );

        assertEquals(3, result.size());
        assertEquals("标题一", result.get(0).getMainTitle());
    }

    @Test
    void extractsJsonObjectFromSurroundingText() {
        String raw = """
                下面是大纲：
                {
                  "sections": [
                    {"section":1,"title":"开头","points":["引入主题"]},
                    {"section":2,"title":"方法","points":["拆解方法"]}
                  ]
                }
                请查收。
                """;

        ArticleState.OutlineResult result = service.parse(
                raw,
                ArticleState.OutlineResult.class,
                StructuredOutputTypeEnum.OUTLINE_RESULT
        );

        assertEquals(2, result.getSections().size());
    }

    @Test
    void rejectsMissingTitleField() {
        String raw = """
                [
                  {"mainTitle":"标题一"},
                  {"mainTitle":"标题二","subTitle":"副标题二"},
                  {"mainTitle":"标题三","subTitle":"副标题三"}
                ]
                """;

        assertThrows(StructuredOutputException.class, () -> service.parse(
                raw,
                new TypeToken<List<ArticleState.TitleOption>>() {},
                StructuredOutputTypeEnum.TITLE_OPTIONS
        ));
    }

    @Test
    void rejectsInvalidImageSourceEnum() {
        String raw = """
                {
                  "contentWithPlaceholders": "正文\\n{{IMAGE_PLACEHOLDER_1}}",
                  "imageRequirements": [
                    {
                      "position": 1,
                      "type": "cover",
                      "sectionTitle": "",
                      "imageSource": "UNKNOWN",
                      "keywords": "office",
                      "prompt": "",
                      "placeholderId": ""
                    }
                  ]
                }
                """;

        assertThrows(StructuredOutputException.class, () -> service.parse(
                raw,
                ArticleState.Agent4Result.class,
                StructuredOutputTypeEnum.IMAGE_PLAN
        ));
    }

    @Test
    void retriesOnceAfterBusinessValidationFailure() {
        AtomicInteger retryCount = new AtomicInteger();
        String invalid = """
                [
                  {"mainTitle":"标题一","subTitle":"副标题一"}
                ]
                """;
        String valid = """
                [
                  {"mainTitle":"标题一","subTitle":"副标题一"},
                  {"mainTitle":"标题二","subTitle":"副标题二"},
                  {"mainTitle":"标题三","subTitle":"副标题三"}
                ]
                """;

        List<ArticleState.TitleOption> result = service.parse(
                invalid,
                new TypeToken<List<ArticleState.TitleOption>>() {},
                StructuredOutputTypeEnum.TITLE_OPTIONS,
                () -> {
                    retryCount.incrementAndGet();
                    return valid;
                },
                1
        );

        assertEquals(1, retryCount.get());
        assertEquals(3, result.size());
    }

    @Test
    void marketingStyleIsValid() {
        assertTrue(ArticleStyleEnum.isValid("marketing"));
        assertEquals(ArticleStyleEnum.MARKETING, ArticleStyleEnum.getEnumByValue("marketing"));
    }

    @Test
    void outlinePromptForShortWordRangeLimitsSectionCount() {
        String prompt = ArticlePromptUtils.getOutlineWordRangePrompt("short", "marketing");

        assertTrue(prompt.contains("200-500"));
        assertTrue(prompt.contains("2-3"));
        assertTrue(prompt.contains("不要生成 5 个章节"));
        assertTrue(prompt.contains("痛点/场景"));
    }

    @Test
    void outlinePromptForMarketingWithoutWordRangeStaysCompact() {
        String prompt = ArticlePromptUtils.getOutlineWordRangePrompt(null, "marketing");

        assertTrue(prompt.contains("2-4"));
        assertTrue(prompt.contains("转化导向"));
        assertTrue(prompt.contains("不要按长篇文章自动扩展"));
    }

    @Test
    void parsesReviewResult() {
        String raw = """
                {
                  "approved": true,
                  "score": 86,
                  "dimensionScores": {
                    "commonBaseline": 34,
                    "styleFit": 38,
                    "stageFit": 14
                  },
                  "problems": ["标题候选之间差异还可以更明显"],
                  "suggestions": ["补充更明确的用户收益表达"],
                  "nextAction": "APPROVE"
                }
                """;

        ReviewResult result = service.parse(raw, ReviewResult.class, StructuredOutputTypeEnum.REVIEW_RESULT);

        assertEquals(86, result.getScore());
        assertTrue(result.isApprovedByThreshold());
    }

    @Test
    void parsesReviewResultFromSingleItemArray() {
        String raw = """
                [
                  {
                    "approved": false,
                    "score": 76,
                    "dimensionScores": {
                      "commonBaseline": 32,
                      "styleFit": 32,
                      "stageFit": 12
                    },
                    "problems": ["Conversion path is incomplete"],
                    "suggestions": ["Add a clearer pain-solution-CTA flow"],
                    "nextAction": "REVISE"
                  }
                ]
                """;

        ReviewResult result = service.parse(raw, ReviewResult.class, StructuredOutputTypeEnum.REVIEW_RESULT);

        assertEquals(76, result.getScore());
        assertEquals("REVISE", result.getNextAction());
    }

    @Test
    void parsesReviewResultFromNamedWrapper() {
        String raw = """
                {
                  "review_result": {
                    "approved": false,
                    "score": 78,
                    "dimensionScores": {
                      "commonBaseline": 33,
                      "styleFit": 33,
                      "stageFit": 12
                    },
                    "problems": ["Evidence is weak"],
                    "suggestions": ["Add concrete scenarios or proof points"],
                    "nextAction": "REVISE"
                  }
                }
                """;

        ReviewResult result = service.parse(raw, ReviewResult.class, StructuredOutputTypeEnum.REVIEW_RESULT);

        assertEquals(78, result.getScore());
        assertEquals("REVISE", result.getNextAction());
    }

    @Test
    void rejectsReviewScoreOutOfRange() {
        String raw = """
                {
                  "approved": true,
                  "score": 120,
                  "problems": [],
                  "suggestions": [],
                  "nextAction": "APPROVE"
                }
                """;

        assertThrows(StructuredOutputException.class, () -> service.parse(
                raw,
                ReviewResult.class,
                StructuredOutputTypeEnum.REVIEW_RESULT
        ));
    }

    @Test
    void rejectsTextReviewWithoutDimensionScores() {
        String raw = """
                {
                  "approved": true,
                  "score": 85,
                  "problems": ["缺少具体场景"],
                  "suggestions": ["补充用户场景"],
                  "nextAction": "APPROVE"
                }
                """;

        assertThrows(StructuredOutputException.class, () -> service.parse(
                raw,
                ReviewResult.class,
                StructuredOutputTypeEnum.REVIEW_RESULT
        ));
    }

    @Test
    void rejectsTextReviewWhenDimensionScoresDoNotMatchScore() {
        String raw = """
                {
                  "approved": true,
                  "score": 85,
                  "dimensionScores": {
                    "commonBaseline": 34,
                    "styleFit": 36,
                    "stageFit": 14
                  },
                  "problems": ["缺少具体场景"],
                  "suggestions": ["补充用户场景"],
                  "nextAction": "APPROVE"
                }
                """;

        assertThrows(StructuredOutputException.class, () -> service.parse(
                raw,
                ReviewResult.class,
                StructuredOutputTypeEnum.REVIEW_RESULT
        ));
    }

    @Test
    void parsesImageReviewResult() {
        String raw = """
                {
                  "approved": false,
                  "score": 72,
                  "problems": ["图片与章节主题关联较弱"],
                  "suggestions": ["改用更具体的关键词"],
                  "nextAction": "REPLAN",
                  "observation": "图片可用但不够贴合",
                  "revisionAdvice": "突出章节核心概念"
                }
                """;

        ImageReviewResult result = service.parse(
                raw,
                ImageReviewResult.class,
                StructuredOutputTypeEnum.IMAGE_REVIEW_RESULT
        );

        assertEquals(72, result.getScore());
        assertEquals("突出章节核心概念", result.getRevisionAdvice());
    }
}
