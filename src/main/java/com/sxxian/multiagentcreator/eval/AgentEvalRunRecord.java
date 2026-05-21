package com.sxxian.multiagentcreator.eval;

import com.sxxian.multiagentcreator.model.dto.article.ArticleState;
import com.sxxian.multiagentcreator.model.entity.AgentLog;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AgentEvalRunRecord {

    private String evalRunId;

    private String taskId;

    private String topic;

    private AgentEvalMode mode;

    private boolean success;

    private long startedAt;

    private long endedAt;

    private String errorMessage;

    private ArticleState state;

    private List<AgentLog> logs;

    private String articlePath;

    public long durationMs() {
        return Math.max(0L, endedAt - startedAt);
    }
}
