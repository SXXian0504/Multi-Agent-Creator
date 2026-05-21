package com.sxxian.multiagentcreator.model.enums;

import java.util.Arrays;

public enum KnowledgeBaseTypeEnum {
    STYLE_MEMORY("style_memory"),
    PERSONA_MEMORY("persona_memory"),
    DOMAIN_KNOWLEDGE("domain_knowledge"),
    PROJECT_DOCS("project_docs"),
    TASK_REFERENCE("task_reference");

    private final String value;

    KnowledgeBaseTypeEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isValid(String value) {
        return Arrays.stream(values()).anyMatch(item -> item.value.equals(value));
    }
}
