package com.sxxian.multiagentcreator.model.dto.structured;

import lombok.Builder;
import lombok.Data;

/**
 * 单次结构化输出解析指标。
 */
@Data
@Builder
public class StructuredOutputMetrics {

    private String outputType;

    private boolean parseSuccess;

    private boolean schemaValid;

    private boolean businessValid;

    private int repairCount;

    private int retryCount;

    private String errorMessage;
}
