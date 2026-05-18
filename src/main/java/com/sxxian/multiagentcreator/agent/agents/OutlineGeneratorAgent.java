package com.sxxian.multiagentcreator.agent.agents;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.sxxian.multiagentcreator.agent.context.StreamHandlerContext;
import com.sxxian.multiagentcreator.constant.PromptConstant;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.enums.SseMessageTypeEnum;
import com.sxxian.multiagentcreator.service.SkillService;
import com.sxxian.multiagentcreator.utils.ArticlePromptUtils;
import com.sxxian.multiagentcreator.utils.GsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 大纲生成 Agent
 * 根据标题生成文章大纲（支持流式输出）
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OutlineGeneratorAgent implements NodeAction {

    private final DashScopeChatModel chatModel;
    private final SkillService skillService;

    public static final String INPUT_MAIN_TITLE = "mainTitle";
    public static final String INPUT_SUB_TITLE = "subTitle";
    public static final String INPUT_USER_DESCRIPTION = "userDescription";
    public static final String INPUT_PLATFORM = "platform";
    public static final String INPUT_STYLE = "style";
    public static final String INPUT_WORD_RANGE = "wordRange";
    public static final String OUTPUT_OUTLINE = "outline";

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String mainTitle = state.value(INPUT_MAIN_TITLE)
                .map(Object::toString)
                .orElseThrow(() -> new IllegalArgumentException("缺少主标题参数"));

        String subTitle = state.value(INPUT_SUB_TITLE)
                .map(Object::toString)
                .orElse("");

        String userDescription = state.value(INPUT_USER_DESCRIPTION)
                .map(Object::toString)
                .orElse(null);

        String style = state.value(INPUT_STYLE)
                .map(Object::toString)
                .orElse(null);
        String platform = state.value(INPUT_PLATFORM)
                .map(Object::toString)
                .orElse(null);

        String wordRange = state.value(INPUT_WORD_RANGE)
                .map(Object::toString)
                .orElse(null);

        log.info("OutlineGeneratorAgent 开始执行: mainTitle={}, subTitle={}", mainTitle, subTitle);

        // 构建用户描述部分
        String descriptionSection = "";
        if (userDescription != null && !userDescription.trim().isEmpty()) {
            descriptionSection = PromptConstant.AGENT2_DESCRIPTION_SECTION
                    .replace("{userDescription}", userDescription);
        }

        // 构建 prompt
        String prompt = PromptConstant.AGENT2_OUTLINE_PROMPT
                .replace("{mainTitle}", mainTitle)
                .replace("{subTitle}", subTitle)
                .replace("{descriptionSection}", descriptionSection)
                + ArticlePromptUtils.getOutlineWordRangePrompt(wordRange, style)
                + skillService.buildPromptInstruction(platform, style, wordRange);

        // 获取流式处理器
        Consumer<String> streamHandler = StreamHandlerContext.get();

        // 调用 LLM（流式输出）
        String content = callLlmWithStreaming(prompt, streamHandler);

        // 解析结果
        ArticleState.OutlineResult outlineResult = GsonUtils.fromJson(
                content,
                ArticleState.OutlineResult.class
        );

        log.info("OutlineGeneratorAgent 执行完成: 生成了 {} 个章节",
                outlineResult.getSections().size());

        return Map.of(OUTPUT_OUTLINE, outlineResult);
    }

    /**
     * 调用 LLM（流式输出）
     */
    private String callLlmWithStreaming(String prompt, Consumer<String> streamHandler) {
        StringBuilder contentBuilder = new StringBuilder();

        Flux<ChatResponse> streamResponse = chatModel.stream(new Prompt(new UserMessage(prompt)));

        streamResponse
                .doOnNext(response -> {
                    String chunk = response.getResult().getOutput().getText();
                    if (chunk != null && !chunk.isEmpty()) {
                        contentBuilder.append(chunk);
                        // 带前缀发送流式消息
                        if (streamHandler != null) {
                            streamHandler.accept(SseMessageTypeEnum.AGENT2_STREAMING.getStreamingPrefix() + chunk);
                        }
                    }
                })
                .doOnError(error -> log.error("OutlineGeneratorAgent 流式调用失败", error))
                .blockLast();

        return contentBuilder.toString();
    }

}
