package com.sxxian.multiagentcreator.eval;

public enum AgentEvalMode {
    BASELINE("baseline"),
    EXPERIMENT("experiment");

    private final String value;

    AgentEvalMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
