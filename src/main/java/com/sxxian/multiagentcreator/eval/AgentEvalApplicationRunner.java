package com.sxxian.multiagentcreator.eval;

import com.sxxian.multiagentcreator.model.dto.eval.AgentEvalRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AgentEvalApplicationRunner implements ApplicationRunner {

    @Resource
    private AgentEvalService agentEvalService;

    @Value("${agent-eval.enabled:false}")
    private boolean enabled;

    @Value("${agent-eval.limit:20}")
    private int limit;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        AgentEvalRequest request = new AgentEvalRequest();
        request.setLimit(limit);
        log.info("agent-eval.enabled=true, running AgentEval from ApplicationRunner, limit={}", limit);
        agentEvalService.run(request);
    }
}
