package com.sxxian.multiagentcreator.agent;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.sxxian.multiagentcreator.agent.context.StreamHandlerContext;
import com.sxxian.multiagentcreator.annotation.AgentExecution;
import com.sxxian.multiagentcreator.constant.PromptConstant;
import com.sxxian.multiagentcreator.model.dto.article.ArticleContext;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.dto.review.ReviewResult;
import com.sxxian.multiagentcreator.model.enums.ArticlePhaseEnum;
import com.sxxian.multiagentcreator.model.enums.ArticleStyleEnum;
import com.sxxian.multiagentcreator.model.enums.SseMessageTypeEnum;
import com.sxxian.multiagentcreator.model.enums.StructuredOutputTypeEnum;
import com.sxxian.multiagentcreator.service.JsonStructuredOutputService;
import com.sxxian.multiagentcreator.utils.ArticlePromptUtils;
import com.sxxian.multiagentcreator.utils.GsonUtils;
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
    private final JsonStructuredOutputService jsonStructuredOutputService;
    private final ReviewAgent reviewAgent;

    @AgentExecution(value = "article_agent_generate_titles", description = "ArticleAgent生成标题", phase = "TITLE_GENERATING")
    public ArticleContext generateTitles(ArticleContext context) {
        String prompt = PromptConstant.AGENT1_TITLE_PROMPT
                .replace("{topic}", context.getTopic())
                + buildFeedbackPrompt(context, ArticlePhaseEnum.TITLE_GENERATING)
                + getWordRangePrompt(context.getWordRange())
                + getStylePrompt(context.getStyle());

        List<ArticleState.TitleOption> titleOptions = generateTitleOptions(prompt);

        context.setTitleOptions(titleOptions);
        ReviewResult reviewResult = reviewAgent.reviewTitles(contextToState(context));
        context.setTitleReviewResult(reviewResult);
        if (!reviewResult.isApprovedByThreshold()) {
            String revisionAdvice = reviewAgent.buildRevisionAdvice(reviewResult);
            context.setReviewAdvice(revisionAdvice);
            String revisedPrompt = prompt + revisionAdvice;
            context.setTitleOptions(generateTitleOptions(revisedPrompt));
            reviewResult = reviewAgent.reviewTitles(contextToState(context));
            context.setTitleReviewResult(reviewResult);
        }
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
                + ArticlePromptUtils.getOutlineWordRangePrompt(context.getWordRange(), context.getStyle())
                + getStylePrompt(context.getStyle());

        ArticleState.OutlineResult outlineResult = generateOutlineResult(prompt, streamHandler);

        context.setOutline(outlineResult);
        ReviewResult reviewResult = reviewAgent.reviewOutline(contextToState(context));
        context.setOutlineReviewResult(reviewResult);
        if (!reviewResult.isApprovedByThreshold()) {
            String revisionAdvice = reviewAgent.buildRevisionAdvice(reviewResult);
            context.setReviewAdvice(revisionAdvice);
            String revisedPrompt = prompt + revisionAdvice;
            context.setOutline(generateOutlineResult(revisedPrompt, streamHandler));
            reviewResult = reviewAgent.reviewOutline(contextToState(context));
            context.setOutlineReviewResult(reviewResult);
        }
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
                + getWordRangePrompt(context.getWordRange())
                + getStylePrompt(context.getStyle());

        String content = callLlmWithStreaming(prompt, streamHandler, SseMessageTypeEnum.AGENT3_STREAMING);
        context.setContent(content);
        ReviewResult reviewResult = reviewAgent.reviewContent(contextToState(context));
        context.setContentReviewResult(reviewResult);
        if (!reviewResult.isApprovedByThreshold()) {
            String revisionAdvice = reviewAgent.buildRevisionAdvice(reviewResult);
            context.setReviewAdvice(revisionAdvice);
            String revisedPrompt = prompt + revisionAdvice;
            context.setContent(callLlmWithStreaming(revisedPrompt, streamHandler, SseMessageTypeEnum.AGENT3_STREAMING));
            reviewResult = reviewAgent.reviewContent(contextToState(context));
            context.setContentReviewResult(reviewResult);
            if (!reviewResult.isApprovedByThreshold()) {
                context.setReviewAdvice(reviewAgent.buildRevisionAdvice(reviewResult));
                log.warn("ArticleAgent 正文二次评审仍未通过, taskId={}, score={}, 保留重写结果并继续后续流程",
                        context.getTaskId(), reviewResult.getScore());
            }
        }
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
        context.setWordRange(readString(state, "wordRange", null));

        generateTitles(context);
        return Map.of(
                "titleOptions", context.getTitleOptions(),
                "titleReviewResult", context.getTitleReviewResult()
        );
    }

    @AgentExecution(value = "article_agent_node_generate_outline", description = "ArticleAgent编排节点生成大纲", phase = "OUTLINE_GENERATING")
    public Map<String, Object> generateOutlineNode(OverAllState state) {
        ArticleContext context = new ArticleContext();
        context.setTaskId(readString(state, "taskId", null));
        context.setTopic(readString(state, "topic", null));
        context.setStyle(readString(state, "style", null));
        context.setWordRange(readString(state, "wordRange", null));
        context.setUserDescription(readString(state, "userDescription", null));

        ArticleState.TitleResult title = new ArticleState.TitleResult();
        title.setMainTitle(readString(state, "mainTitle", true));
        title.setSubTitle(readString(state, "subTitle", null));
        context.setTitle(title);

        generateOutline(context, StreamHandlerContext.get());
        return Map.of(
                "outline", context.getOutline(),
                "outlineReviewResult", context.getOutlineReviewResult()
        );
    }

    @AgentExecution(value = "article_agent_node_generate_content", description = "ArticleAgent编排节点生成正文", phase = "CONTENT_GENERATING")
    public Map<String, Object> generateContentNode(OverAllState state) {
        ArticleContext context = new ArticleContext();
        context.setTaskId(readString(state, "taskId", null));
        context.setTopic(readString(state, "topic", null));
        context.setStyle(readString(state, "style", null));
        context.setWordRange(readString(state, "wordRange", null));
        context.setUserDescription(readString(state, "userDescription", null));

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
        return Map.of(
                "content", context.getContent(),
                "contentReviewResult", context.getContentReviewResult()
        );
    }

    private List<ArticleState.TitleOption> generateTitleOptions(String prompt) {
        String content = callLlm(prompt);
        return jsonStructuredOutputService.parse(
                content,
                new TypeToken<List<ArticleState.TitleOption>>() {},
                StructuredOutputTypeEnum.TITLE_OPTIONS,
                () -> callLlm(prompt),
                1
        );
    }

    private ArticleState.OutlineResult generateOutlineResult(String prompt, Consumer<String> streamHandler) {
        String content = callLlmWithStreaming(prompt, streamHandler, SseMessageTypeEnum.AGENT2_STREAMING);
        return jsonStructuredOutputService.parse(
                content,
                ArticleState.OutlineResult.class,
                StructuredOutputTypeEnum.OUTLINE_RESULT,
                () -> callLlmWithStreaming(prompt, streamHandler, SseMessageTypeEnum.AGENT2_STREAMING),
                1
        );
    }

    private ArticleState contextToState(ArticleContext context) {
        ArticleState state = new ArticleState();
        context.applyToState(state);
        return state;
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
            case MARKETING -> PromptConstant.STYLE_MARKETING_PROMPT;
        };
    }

    private String getWordRangePrompt(String wordRange) {
        String instruction = switch (wordRange == null ? "" : wordRange) {
            case "short" -> "用户选择短文，正文建议约 200-500 字；营销图文可更短，但必须完整覆盖卖点、信任支撑和行动引导。";
            case "medium" -> "用户选择中等篇幅，正文建议约 800-1500 字；请在信息完整和阅读效率之间平衡。";
            case "long" -> "用户选择长文，正文建议约 2000-3500 字；需要更充分的解释、案例、论证和小结。";
            default -> "用户未指定字数范围，请由 ArticleAgent 根据选题、文章风格和大纲自行评估篇幅；营销类通常更短，科普/技术类可适当更长。";
        };
        return PromptConstant.WORD_RANGE_PROMPT.replace("{wordRangeInstruction}", instruction);
    }

}
