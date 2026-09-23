package com.mall.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Builds model-facing tool result payloads. */
public final class ToolResults {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolResults() {
    }

    /** Returns a readable business failure without implementation details. */
    public static String failure(SupermallException exception) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("error", true);
        result.put("code", exception.getCode());
        result.put("message", exception.getMessage());
        return result.toString();
    }
}
