package com.sxxian.multiagentcreator.agent.agents;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.google.gson.reflect.TypeToken;
import com.sxxian.multiagentcreator.constant.PromptConstant;
import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.service.SkillService;
import com.sxxian.multiagentcreator.utils.GsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 标题生成 Agent
 * 根据选题生成 3-5 个爆款标题方案
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TitleGeneratorAgent implements NodeAction {

    private final DashScopeChatModel chatModel;
    private final SkillService skillService;

    public static final String INPUT_TOPIC = "topic";
    public static final String INPUT_PLATFORM = "platform";
    public static final String INPUT_STYLE = "style";
    public static final String OUTPUT_TITLE_OPTIONS = "titleOptions";

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String topic = state.value(INPUT_TOPIC)
                .map(Object::toString)
                .orElseThrow(() -> new IllegalArgumentException("缺少选题参数"));

        String style = state.value(INPUT_STYLE)
                .map(Object::toString)
                .orElse(null);
        String platform = state.value(INPUT_PLATFORM)
                .map(Object::toString)
                .orElse(null);

        log.info("TitleGeneratorAgent 开始执行: topic={}, style={}", topic, style);

        // 构建 prompt
        String prompt = PromptConstant.AGENT1_TITLE_PROMPT
                .replace("{topic}", topic)
                + skillService.buildPromptInstruction(platform, style, null);

        // 调用 LLM
        ChatResponse response = chatModel.call(new Prompt(new UserMessage(prompt)));
        String content = response.getResult().getOutput().getText();

        // 解析结果
        List<ArticleState.TitleOption> titleOptions = GsonUtils.fromJson(
                content,
                new TypeToken<List<ArticleState.TitleOption>>(){}
        );

        log.info("TitleGeneratorAgent 执行完成: 生成了 {} 个标题方案", titleOptions.size());

        return Map.of(OUTPUT_TITLE_OPTIONS, titleOptions);
    }

}
