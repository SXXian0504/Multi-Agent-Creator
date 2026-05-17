package com.sxxian.multiagentcreator.model.dto.article;

import lombok.Data;

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

    private String userDescription;

    private String style;

    private String phase;

    private List<ArticleState.TitleOption> titleOptions;

    private ArticleState.TitleResult title;

    private ArticleState.OutlineResult outline;

    private String content;

    private List<UserFeedback> feedbackHistory = new ArrayList<>();

    public static ArticleContext fromState(ArticleState state) {
        ArticleContext context = new ArticleContext();
        context.setTaskId(state.getTaskId());
        context.setTopic(state.getTopic());
        context.setUserDescription(state.getUserDescription());
        context.setStyle(state.getStyle());
        context.setPhase(state.getPhase());
        context.setTitleOptions(state.getTitleOptions());
        context.setTitle(state.getTitle());
        context.setOutline(state.getOutline());
        context.setContent(state.getContent());
        return context;
    }

    public void applyToState(ArticleState state) {
        state.setTaskId(taskId);
        state.setTopic(topic);
        state.setUserDescription(userDescription);
        state.setStyle(style);
        state.setPhase(phase);
        state.setTitleOptions(titleOptions);
        state.setTitle(title);
        state.setOutline(outline);
        state.setContent(content);
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
