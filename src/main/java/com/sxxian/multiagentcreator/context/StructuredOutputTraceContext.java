package com.sxxian.multiagentcreator.context;

import com.sxxian.multiagentcreator.model.dto.structured.StructuredOutputMetrics;

import java.util.ArrayList;
import java.util.List;

/**
 * 当前线程内的结构化输出指标上下文，供 Agent AOP 写入 metadata。
 */
public final class StructuredOutputTraceContext {

    private static final ThreadLocal<List<StructuredOutputMetrics>> METRICS =
            ThreadLocal.withInitial(ArrayList::new);

    private StructuredOutputTraceContext() {
    }

    public static void add(StructuredOutputMetrics metrics) {
        if (metrics != null) {
            METRICS.get().add(metrics);
        }
    }

    public static List<StructuredOutputMetrics> snapshot() {
        return new ArrayList<>(METRICS.get());
    }

    public static void clear() {
        METRICS.remove();
    }
}
