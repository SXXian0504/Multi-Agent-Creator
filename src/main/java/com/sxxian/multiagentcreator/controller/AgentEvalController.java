package com.sxxian.multiagentcreator.controller;

import com.sxxian.multiagentcreator.common.BaseResponse;
import com.sxxian.multiagentcreator.common.ResultUtils;
import com.sxxian.multiagentcreator.eval.AgentEvalService;
import com.sxxian.multiagentcreator.model.dto.eval.AgentEvalRequest;
import com.sxxian.multiagentcreator.model.dto.eval.AgentEvalResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent-eval")
@Slf4j
public class AgentEvalController {

    @Resource
    private AgentEvalService agentEvalService;

    @PostMapping("/run")
    @Operation(summary = "运行阶段10 AgentEval 量化评估")
    public BaseResponse<AgentEvalResponse> run(@RequestBody(required = false) AgentEvalRequest request) {
        return ResultUtils.success(agentEvalService.run(request == null ? new AgentEvalRequest() : request));
    }
}
