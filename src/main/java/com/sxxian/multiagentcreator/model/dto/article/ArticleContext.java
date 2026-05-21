package com.sxxian.multiagentcreator.model.dto.article;

import lombok.Data;
import com.sxxian.multiagentcreator.model.dto.review.ReviewResult;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 文章生成上下文。
 *
 * <p>阶段 2 先作为 ArticleAgent 的服务层上下文，避免改动持久化结构。</p>
 */
@Data
public class ArticleContext implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;

    private String topic;

    private String platform;

    private String userDescription;

    private String style;

    private String wordRange;

    private String phase;

    private List<ArticleState.TitleOption> titleOptions;

    private ReviewResult titleReviewResult;

    private ArticleState.TitleResult title;

    private ArticleState.OutlineResult outline;

    private ReviewResult outlineReviewResult;

    private String content;

    private ReviewResult contentReviewResult;

    private String reviewAdvice;

    private String retrievedContext;

    private List<UserFeedback> feedbackHistory = new ArrayList<>();

    public static ArticleContext fromState(ArticleState state) {
        ArticleContext context = new ArticleContext();
        context.setTaskId(state.getTaskId());
        context.setTopic(state.getTopic());
        context.setPlatform(state.getPlatform());
        context.setUserDescription(state.getUserDescription());
        context.setStyle(state.getStyle());
        context.setWordRange(state.getWordRange());
        context.setPhase(state.getPhase());
        context.setTitleOptions(state.getTitleOptions());
        context.setTitleReviewResult(state.getTitleReviewResult());
        context.setTitle(state.getTitle());
        context.setOutline(state.getOutline());
        context.setOutlineReviewResult(state.getOutlineReviewResult());
        context.setContent(state.getContent());
        context.setContentReviewResult(state.getContentReviewResult());
        context.setRetrievedContext(state.getRetrievedContext());
        return context;
    }

    public void applyToState(ArticleState state) {
        state.setTaskId(taskId);
        state.setTopic(topic);
        state.setPlatform(platform);
        state.setUserDescription(userDescription);
        state.setStyle(style);
        state.setWordRange(wordRange);
        state.setPhase(phase);
        state.setTitleOptions(titleOptions);
        state.setTitleReviewResult(titleReviewResult);
        state.setTitle(title);
        state.setOutline(outline);
        state.setOutlineReviewResult(outlineReviewResult);
        state.setContent(content);
        state.setContentReviewResult(contentReviewResult);
        state.setRetrievedContext(retrievedContext);
    }

    public String getLatestFeedbackContent(String phase) {
        if (feedbackHistory == null || feedbackHistory.isEmpty() || phase == null) {
            return null;
        }
        for (int i = feedbackHistory.size() - 1; i >= 0; i--) {
            UserFeedback feedback = feedbackHistory.get(i);
            if (phase.equals(feedback.getPhase())) {
                return feedback.getContent();
            }
        }
        return null;
    }
}
