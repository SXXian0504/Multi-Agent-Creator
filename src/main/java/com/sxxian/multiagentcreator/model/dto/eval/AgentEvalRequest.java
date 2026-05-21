package com.sxxian.multiagentcreator.model.dto.eval;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class AgentEvalRequest implements Serializable {

    private List<String> topics;

    private Integer limit;

    private String platform;

    private String style;

    private String wordRange;

    private List<String> enabledImageMethods;

    private String outputDir;

    private static final long serialVersionUID = 1L;
}
