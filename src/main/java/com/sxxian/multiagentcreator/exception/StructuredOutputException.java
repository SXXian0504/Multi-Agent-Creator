package com.sxxian.multiagentcreator.exception;

/**
 * 结构化输出解析、校验或重试失败。
 */
public class StructuredOutputException extends RuntimeException {

    public StructuredOutputException(String message) {
        super(message);
    }

    public StructuredOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
