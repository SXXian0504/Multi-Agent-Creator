package com.sxxian.multiagentcreator.agent.agents;


import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.sxxian.multiagentcreator.agent.context.StreamHandlerContext;
import com.sxxian.multiagentcreator.constant.PromptConstant;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.enums.SseMessageTypeEnum;
import com.sxxian.multiagentcreator.service.SkillService;
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
 * 正文生成 Agent
 * 根据大纲生成文章正文内容（支持流式输出）
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ContentGeneratorAgent implements NodeAction {

    private final DashScopeChatModel chatModel;
    private final SkillService skillService;

    public static final String INPUT_MAIN_TITLE = "mainTitle";
    public static final String INPUT_SUB_TITLE = "subTitle";
    public static final String INPUT_OUTLINE = "outline";
    public static final String INPUT_PLATFORM = "platform";
    public static final String INPUT_STYLE = "style";
    public static final String OUTPUT_CONTENT = "content";

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String mainTitle = state.value(INPUT_MAIN_TITLE)
                .map(Object::toString)
                .orElseThrow(() -> new IllegalArgumentException("缺少主标题参数"));

        String subTitle = state.value(INPUT_SUB_TITLE)
                .map(Object::toString)
                .orElse("");

        @SuppressWarnings("unchecked")
        ArticleState.OutlineResult outline = state.value(INPUT_OUTLINE)
                .map(v -> {
                    if (v instanceof ArticleState.OutlineResult) {
                        return (ArticleState.OutlineResult) v;
                    }
                    return GsonUtils.fromJson(GsonUtils.toJson(v), ArticleState.OutlineResult.class);
                })
                .orElseThrow(() -> new IllegalArgumentException("缺少大纲参数"));

        String style = state.value(INPUT_STYLE)
                .map(Object::toString)
                .orElse(null);
        String platform = state.value(INPUT_PLATFORM)
                .map(Object::toString)
                .orElse(null);

        log.info("ContentGeneratorAgent 开始执行: mainTitle={}", mainTitle);

        // 构建 prompt
        String outlineText = GsonUtils.toJson(outline.getSections());
        String prompt = PromptConstant.AGENT3_CONTENT_PROMPT
                .replace("{mainTitle}", mainTitle)
                .replace("{subTitle}", subTitle)
                .replace("{outline}", outlineText)
                + skillService.buildPromptInstruction(platform, style, null);

        // 获取流式处理器
        Consumer<String> streamHandler = StreamHandlerContext.get();

        // 调用 LLM（流式输出）
        String content = callLlmWithStreaming(prompt, streamHandler);

        log.info("ContentGeneratorAgent 执行完成: 正文长度={}", content.length());

        return Map.of(OUTPUT_CONTENT, content);
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
                            streamHandler.accept(SseMessageTypeEnum.AGENT3_STREAMING.getStreamingPrefix() + chunk);
                        }
                    }
                })
                .doOnError(error -> log.error("ContentGeneratorAgent 流式调用失败", error))
                .blockLast();

        return contentBuilder.toString();
    }

}
