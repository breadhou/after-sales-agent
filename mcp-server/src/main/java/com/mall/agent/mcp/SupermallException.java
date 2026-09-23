package com.mall.agent.mcp;

/** A failure returned by, or encountered while calling, supermall. */
public class SupermallException extends RuntimeException {

    private final int code;

    public SupermallException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
