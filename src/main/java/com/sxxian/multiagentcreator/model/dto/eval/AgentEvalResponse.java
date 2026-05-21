package com.sxxian.multiagentcreator.model.dto.eval;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

@Data
@Builder
public class AgentEvalResponse implements Serializable {

    private String evalRunId;

    private int topicCount;

    private String reportPath;

    private String reportMarkdown;

    private static final long serialVersionUID = 1L;
}
