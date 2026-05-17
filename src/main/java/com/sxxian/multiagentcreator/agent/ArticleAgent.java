package com.sxxian.multiagentcreator.agent;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.sxxian.multiagentcreator.agent.context.StreamHandlerContext;
import com.sxxian.multiagentcreator.annotation.AgentExecution;
import com.sxxian.multiagentcreator.constant.PromptConstant;
import com.sxxian.multiagentcreator.model.dto.article.ArticleContext;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.enums.ArticlePhaseEnum;
import com.sxxian.multiagentcreator.model.enums.ArticleStyleEnum;
import com.sxxian.multiagentcreator.model.enums.SseMessageTypeEnum;
import com.sxxian.multiagentcreator.utils.GsonUtils;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 统一文章 Agent。
 *
 * <p>阶段 2 只收敛标题、大纲、正文三类文本生成能力，图片规划和合成仍沿用现有链路。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ArticleAgent {

    private final DashScopeChatModel chatModel;

    @AgentExecution(value = "article_agent_generate_titles", description = "ArticleAgent生成标题", phase = "TITLE_GENERATING")
    public ArticleContext generateTitles(ArticleContext context) {
        String prompt = PromptConstant.AGENT1_TITLE_PROMPT
                .replace("{topic}", context.getTopic())
                + buildFeedbackPrompt(context, ArticlePhaseEnum.TITLE_GENERATING)
                + getStylePrompt(context.getStyle());

        String content = callLlm(prompt);
        List<ArticleState.TitleOption> titleOptions = parseJsonListResponse(
                content,
                new TypeToken<List<ArticleState.TitleOption>>() {},
                "标题方案"
        );

        context.setTitleOptions(titleOptions);
        context.setPhase(ArticlePhaseEnum.TITLE_WAITING_USER_CONFIRM.getValue());
        log.info("ArticleAgent 标题生成完成, taskId={}, optionsCount={}", context.getTaskId(), titleOptions.size());
        return context;
    }

    @AgentExecution(value = "article_agent_generate_outline", description = "ArticleAgent生成大纲", phase = "OUTLINE_GENERATING")
    public ArticleContext generateOutline(ArticleContext context, Consumer<String> streamHandler) {
        String descriptionSection = "";
        if (context.getUserDescription() != null && !context.getUserDescription().trim().isEmpty()) {
            descriptionSection = PromptConstant.AGENT2_DESCRIPTION_SECTION
                    .replace("{userDescription}", context.getUserDescription());
        }

        String prompt = PromptConstant.AGENT2_OUTLINE_PROMPT
                .replace("{mainTitle}", context.getTitle().getMainTitle())
                .replace("{subTitle}", context.getTitle().getSubTitle())
                .replace("{descriptionSection}", descriptionSection)
                + buildFeedbackPrompt(context, ArticlePhaseEnum.OUTLINE_GENERATING)
                + getStylePrompt(context.getStyle());

        String content = callLlmWithStreaming(prompt, streamHandler, SseMessageTypeEnum.AGENT2_STREAMING);
        ArticleState.OutlineResult outlineResult = parseJsonResponse(content, ArticleState.OutlineResult.class, "大纲");

        context.setOutline(outlineResult);
        context.setPhase(ArticlePhaseEnum.OUTLINE_WAITING_USER_CONFIRM.getValue());
        log.info("ArticleAgent 大纲生成完成, taskId={}, sections={}",
                context.getTaskId(), outlineResult.getSections().size());
        return context;
    }

    @AgentExecution(value = "article_agent_generate_content", description = "ArticleAgent生成正文", phase = "CONTENT_GENERATING")
    public ArticleContext generateContent(ArticleContext context, Consumer<String> streamHandler) {
        String outlineText = GsonUtils.toJson(context.getOutline().getSections());
        String prompt = PromptConstant.AGENT3_CONTENT_PROMPT
                .replace("{mainTitle}", context.getTitle().getMainTitle())
                .replace("{subTitle}", context.getTitle().getSubTitle())
                .replace("{outline}", outlineText)
                + buildFeedbackPrompt(context, ArticlePhaseEnum.CONTENT_GENERATING)
                + getStylePrompt(context.getStyle());

        String content = callLlmWithStreaming(prompt, streamHandler, SseMessageTypeEnum.AGENT3_STREAMING);
        context.setContent(content);
        context.setPhase(ArticlePhaseEnum.CONTENT_WAITING_USER_CONFIRM.getValue());
        log.info("ArticleAgent 正文生成完成, taskId={}, length={}", context.getTaskId(), content.length());
        return context;
    }

    /**
     * 按当前阶段重跑文本生成阶段，供后续反馈接口复用。
     */
    public ArticleContext rerunCurrentPhase(ArticleContext context, Consumer<String> streamHandler) {
        ArticlePhaseEnum phase = ArticlePhaseEnum.getByValue(context.getPhase());
        if (phase == null) {
            throw new IllegalArgumentException("未知文章阶段: " + context.getPhase());
        }
        return switch (phase) {
            case TITLE_GENERATING, TITLE_WAITING_USER_CONFIRM, TITLE_SELECTING -> generateTitles(context);
            case OUTLINE_GENERATING, OUTLINE_WAITING_USER_CONFIRM, OUTLINE_EDITING -> generateOutline(context, streamHandler);
            case CONTENT_GENERATING, CONTENT_WAITING_USER_CONFIRM -> generateContent(context, streamHandler);
            default -> throw new IllegalArgumentException("当前阶段不支持 ArticleAgent 重跑: " + context.getPhase());
        };
    }

    @AgentExecution(value = "article_agent_node_generate_titles", description = "ArticleAgent编排节点生成标题", phase = "TITLE_GENERATING")
    public Map<String, Object> generateTitlesNode(OverAllState state) {
        ArticleContext context = new ArticleContext();
        context.setTaskId(readString(state, "taskId", null));
        context.setTopic(readString(state, "topic", true));
        context.setStyle(readString(state, "style", null));

        generateTitles(context);
        return Map.of("titleOptions", context.getTitleOptions());
    }

    @AgentExecution(value = "article_agent_node_generate_outline", description = "ArticleAgent编排节点生成大纲", phase = "OUTLINE_GENERATING")
    public Map<String, Object> generateOutlineNode(OverAllState state) {
        ArticleContext context = new ArticleContext();
        context.setTaskId(readString(state, "taskId", null));
        context.setStyle(readString(state, "style", null));
        context.setUserDescription(readString(state, "userDescription", null));

        ArticleState.TitleResult title = new ArticleState.TitleResult();
        title.setMainTitle(readString(state, "mainTitle", true));
        title.setSubTitle(readString(state, "subTitle", null));
        context.setTitle(title);

        generateOutline(context, StreamHandlerContext.get());
        return Map.of("outline", context.getOutline());
    }

    @AgentExecution(value = "article_agent_node_generate_content", description = "ArticleAgent编排节点生成正文", phase = "CONTENT_GENERATING")
    public Map<String, Object> generateContentNode(OverAllState state) {
        ArticleContext context = new ArticleContext();
        context.setTaskId(readString(state, "taskId", null));
        context.setStyle(readString(state, "style", null));

        ArticleState.TitleResult title = new ArticleState.TitleResult();
        title.setMainTitle(readString(state, "mainTitle", true));
        title.setSubTitle(readString(state, "subTitle", null));
        context.setTitle(title);

        ArticleState.OutlineResult outline = state.value("outline")
                .map(value -> {
                    if (value instanceof ArticleState.OutlineResult outlineResult) {
                        return outlineResult;
                    }
                    return GsonUtils.fromJson(GsonUtils.toJson(value), ArticleState.OutlineResult.class);
                })
                .orElseThrow(() -> new IllegalArgumentException("缺少大纲参数"));
        context.setOutline(outline);

        generateContent(context, StreamHandlerContext.get());
        return Map.of("content", context.getContent());
    }

    private String callLlm(String prompt) {
        ChatResponse response = chatModel.call(new Prompt(new UserMessage(prompt)));
        return response.getResult().getOutput().getText();
    }

    private String callLlmWithStreaming(String prompt, Consumer<String> streamHandler, SseMessageTypeEnum messageType) {
        StringBuilder contentBuilder = new StringBuilder();
        Flux<ChatResponse> streamResponse = chatModel.stream(new Prompt(new UserMessage(prompt)));

        streamResponse
                .doOnNext(response -> {
                    String chunk = response.getResult().getOutput().getText();
                    if (chunk != null && !chunk.isEmpty()) {
                        contentBuilder.append(chunk);
                        if (streamHandler != null) {
                            streamHandler.accept(messageType.getStreamingPrefix() + chunk);
                        }
                    }
                })
                .doOnError(error -> log.error("ArticleAgent 流式调用失败, messageType={}", messageType, error))
                .blockLast();

        return contentBuilder.toString();
    }

    private <T> T parseJsonResponse(String content, Class<T> clazz, String name) {
        try {
            return GsonUtils.fromJson(content, clazz);
        } catch (JsonSyntaxException e) {
            log.error("{}解析失败, content={}", name, content, e);
            throw new RuntimeException(name + "解析失败");
        }
    }

    private <T> T parseJsonListResponse(String content, TypeToken<T> typeToken, String name) {
        try {
            return GsonUtils.fromJson(content, typeToken);
        } catch (JsonSyntaxException e) {
            log.error("{}解析失败, content={}", name, content, e);
            throw new RuntimeException(name + "解析失败");
        }
    }

    private String buildFeedbackPrompt(ArticleContext context, ArticlePhaseEnum phase) {
        String feedback = context.getLatestFeedbackContent(phase.getValue());
        if (feedback == null || feedback.trim().isEmpty()) {
            return "";
        }
        return "\n\n用户对当前阶段的反馈：\n" + feedback.trim() + "\n请仅基于该反馈重写当前阶段结果，不要重跑其他阶段。\n";
    }

    private String readString(OverAllState state, String key, Boolean required) {
        return state.value(key)
                .map(Object::toString)
                .orElseGet(() -> {
                    if (Boolean.TRUE.equals(required)) {
                        throw new IllegalArgumentException("缺少参数: " + key);
                    }
                    return null;
                });
    }

    private String getStylePrompt(String style) {
        if (style == null || style.isEmpty()) {
            return "";
        }

        ArticleStyleEnum styleEnum = ArticleStyleEnum.getEnumByValue(style);
        if (styleEnum == null) {
            return "";
        }

        return switch (styleEnum) {
            case TECH -> PromptConstant.STYLE_TECH_PROMPT;
            case EMOTIONAL -> PromptConstant.STYLE_EMOTIONAL_PROMPT;
            case EDUCATIONAL -> PromptConstant.STYLE_EDUCATIONAL_PROMPT;
            case HUMOROUS -> PromptConstant.STYLE_HUMOROUS_PROMPT;
        };
    }
}
