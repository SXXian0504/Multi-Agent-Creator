package com.sxxian.multiagentcreator.image.execution;

import com.sxxian.multiagentcreator.image.planning.ImageAgent;
import com.sxxian.multiagentcreator.article.review.ReviewAgent;
import com.sxxian.multiagentcreator.image.execution.ImageGenerationTool;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.dto.image.ImageExecutionResult;
import com.sxxian.multiagentcreator.model.dto.review.ImageReviewResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageToolExecutionServiceTest {

    private final ImageGenerationTool imageGenerationTool = mock(ImageGenerationTool.class);
    private final ReviewAgent reviewAgent = mock(ReviewAgent.class);
    private final ImageAgent imageAgent = mock(ImageAgent.class);
    private final ImageToolExecutionService executor = new ImageToolExecutionService(imageGenerationTool, reviewAgent, imageAgent);

    @Test
    void replansAndRegeneratesWhenImageReviewFails() {
        ArticleState reviewState = reviewState();
        ArticleState.ImageRequirement original = requirement("first prompt", 0);
        ArticleState.ImageRequirement replanned = requirement("replanned prompt", 1);

        when(imageGenerationTool.generateImageDirect(anyString(), isNull(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(generated("https://example.com/failed.png"))
                .thenReturn(generated("https://example.com/approved.png"));
        when(reviewAgent.reviewImageResult(any(), any(), any()))
                .thenReturn(review(false, 65, "REPLAN"))
                .thenReturn(review(true, 90, "APPROVE"));
        when(imageAgent.replanSingleImage(any(), anyList())).thenReturn(replanned);

        ImageExecutionResult result = executor.execute(List.of(original), reviewState, null);

        assertEquals(1, result.getImages().size());
        assertEquals("https://example.com/approved.png", result.getImages().get(0).getUrl());
        assertEquals(2, result.getImageReviewResults().size());
        assertEquals(2, result.getTraces().size());
        assertEquals("APPROVED", result.getTraces().get(1).getFinalStatus());
        verify(imageAgent).replanSingleImage(any(), anyList());
    }

    @Test
    void returnsBestScoredImageAfterMaxReviewRetries() {
        ArticleState reviewState = reviewState();
        ArticleState.ImageRequirement original = requirement("first prompt", 0);
        ArticleState.ImageRequirement retryOne = requirement("retry one", 1);
        ArticleState.ImageRequirement retryTwo = requirement("retry two", 2);

        when(imageGenerationTool.generateImageDirect(anyString(), isNull(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(generated("https://example.com/1.png"))
                .thenReturn(generated("https://example.com/2.png"))
                .thenReturn(generated("https://example.com/3.png"));
        when(reviewAgent.reviewImageResult(any(), any(), any()))
                .thenReturn(review(false, 60, "REPLAN"))
                .thenReturn(review(false, 70, "REPLAN"))
                .thenReturn(review(false, 75, "REPLAN"));
        when(imageAgent.replanSingleImage(any(), anyList()))
                .thenReturn(retryOne)
                .thenReturn(retryTwo);

        ImageExecutionResult result = executor.execute(List.of(original), reviewState, null);

        assertEquals(1, result.getImages().size());
        assertEquals("https://example.com/3.png", result.getImages().get(0).getUrl());
        assertEquals("{{IMAGE_PLACEHOLDER_1}}", result.getImages().get(0).getPlaceholderId());
        assertEquals(3, result.getImageReviewResults().size());
        assertEquals("BEST_EFFORT_AFTER_RETRIES", result.getTraces().get(2).getFinalStatus());
    }

    private ArticleState reviewState() {
        ArticleState state = new ArticleState();
        state.setTaskId("task-1");
        state.setTopic("topic");
        state.setEnabledImageMethods(List.of("QWEN_IMAGE"));
        ArticleState.TitleResult title = new ArticleState.TitleResult();
        title.setMainTitle("title");
        state.setTitle(title);
        return state;
    }

    private ArticleState.ImageRequirement requirement(String prompt, int retryCount) {
        ArticleState.ImageRequirement requirement = new ArticleState.ImageRequirement();
        requirement.setPosition(1);
        requirement.setType("cover");
        requirement.setSectionTitle("section");
        requirement.setImageSource("QWEN_IMAGE");
        requirement.setPrompt(prompt);
        requirement.setReason("reason");
        requirement.setRetryCount(retryCount);
        requirement.setPlaceholderId("{{IMAGE_PLACEHOLDER_1}}");
        return requirement;
    }

    private ImageGenerationTool.ImageGenerationResult generated(String url) {
        ImageGenerationTool.ImageGenerationResult result = new ImageGenerationTool.ImageGenerationResult();
        result.setSuccess(true);
        result.setPosition(1);
        result.setUrl(url);
        result.setMethod("QWEN_IMAGE");
        result.setDescription("cover");
        result.setPlaceholderId("{{IMAGE_PLACEHOLDER_1}}");
        return result;
    }

    private ImageReviewResult review(boolean approved, int score, String nextAction) {
        ImageReviewResult result = new ImageReviewResult();
        result.setApproved(approved);
        result.setScore(score);
        result.setProblems(approved ? List.of() : List.of("不符合需求"));
        result.setSuggestions(approved ? List.of() : List.of("按评审意见重画"));
        result.setNextAction(nextAction);
        result.setObservation(approved ? "通过" : "画面不匹配");
        result.setRevisionAdvice(approved ? "" : "改成更贴合 prompt 的画面");
        return result;
    }
}
