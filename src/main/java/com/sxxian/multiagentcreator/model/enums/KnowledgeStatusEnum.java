package com.sxxian.multiagentcreator.model.enums;

public enum KnowledgeStatusEnum {
    ACTIVE("ACTIVE"),
    DISABLED("DISABLED"),
    PENDING("PENDING"),
    PARSING("PARSING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED");

    private final String value;

    KnowledgeStatusEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
