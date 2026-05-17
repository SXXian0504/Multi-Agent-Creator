package com.sxxian.multiagentcreator.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.sxxian.multiagentcreator.mapper.AgentLogMapper;
import com.sxxian.multiagentcreator.model.entity.AgentLog;
import com.sxxian.multiagentcreator.model.vo.AgentExecutionStats;
import com.sxxian.multiagentcreator.service.AgentLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体日志服务实现
 *
 */
@Service
@Slf4j
public class AgentLogServiceImpl extends ServiceImpl<AgentLogMapper, AgentLog> implements AgentLogService {

    @Override
    @Async
    public void saveLogAsync(AgentLog agentLog) {
        try {
            this.save(agentLog);
            log.info("智能体日志已保存, taskId={}, agentName={}, status={}, durationMs={}", 
                    agentLog.getTaskId(), agentLog.getAgentName(), agentLog.getStatus(), agentLog.getDurationMs());
        } catch (Exception e) {
            log.error("保存智能体日志失败, taskId={}, agentName={}", 
                    agentLog.getTaskId(), agentLog.getAgentName(), e);
        }
    }

    @Override
    public List<AgentLog> getLogsByTaskId(String taskId) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("taskId", taskId)
                .orderBy("createTime", true);
        return this.list(queryWrapper);
    }

    @Override
    public AgentExecutionStats getExecutionStats(String taskId) {
        List<AgentLog> logs = getLogsByTaskId(taskId);
        
        if (logs == null || logs.isEmpty()) {
            return AgentExecutionStats.builder()
                    .taskId(taskId)
                    .agentCount(0)
                    .totalDurationMs(0)
                    .overallStatus("NOT_FOUND")
                    .build();
        }

        // 计算统计数据
        int totalDuration = 0;
        Map<String, Integer> agentDurations = new HashMap<>();
        Map<String, Integer> phaseDurations = new HashMap<>();
        String overallStatus = "SUCCESS";
        String traceId = taskId;

        for (AgentLog log : logs) {
            if (log.getTraceId() != null && !log.getTraceId().isEmpty()) {
                traceId = log.getTraceId();
            }

            // 累加总耗时
            if (log.getDurationMs() != null) {
                totalDuration += log.getDurationMs();
                agentDurations.put(log.getAgentName(), log.getDurationMs());
                if (log.getPhase() != null && !log.getPhase().isEmpty()) {
                    phaseDurations.merge(log.getPhase(), log.getDurationMs(), Integer::sum);
                }
            }

            // 判断总体状态
            if ("FAILED".equals(log.getStatus())) {
                overallStatus = "FAILED";
            } else if ("RUNNING".equals(log.getStatus()) && !"FAILED".equals(overallStatus)) {
                overallStatus = "RUNNING";
            }
        }

        return AgentExecutionStats.builder()
                .taskId(taskId)
                .traceId(traceId)
                .totalDurationMs(totalDuration)
                .agentCount(logs.size())
                .agentDurations(agentDurations)
                .phaseDurations(phaseDurations)
                .overallStatus(overallStatus)
                .logs(logs)
                .build();
    }
}
