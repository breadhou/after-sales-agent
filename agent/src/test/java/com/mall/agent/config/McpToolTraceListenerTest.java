package com.mall.agent.config;

import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.protocol.McpCallToolRequest;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class McpToolTraceListenerTest {

    @Test
    void recordsOnlyKnownToolNameAndFixedStatuses() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream old = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            McpToolTraceListener listener = new McpToolTraceListener();
            McpCallContext context = context("get_order", "synthetic-argument-secret");

            listener.beforeExecuteTool(context);
            listener.afterExecuteTool(context, result(false, "synthetic-response-secret"),
                    Map.of("raw", "synthetic-raw-secret"));
            listener.afterExecuteTool(context, result(true, "synthetic-error-secret"),
                    Map.of("raw", "synthetic-raw-secret"));
            listener.onExecuteToolError(context, new IllegalStateException("synthetic-exception-secret"));

            assertEquals("TASK6_TRACE tool=get_order status=called" + System.lineSeparator()
                            + "TASK6_TRACE tool=get_order status=ok" + System.lineSeparator()
                            + "TASK6_TRACE tool=get_order status=error" + System.lineSeparator()
                            + "TASK6_TRACE tool=get_order status=transport_error" + System.lineSeparator(),
                    captured.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(old);
        }
    }

    @Test
    void hostileToolNameCannotInjectTraceLines() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream old = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            McpToolTraceListener listener = new McpToolTraceListener();
            listener.beforeExecuteTool(context("submit_refund\nSUPERMALL_TOKEN=synthetic-secret", "x"));

            String trace = captured.toString(StandardCharsets.UTF_8);
            assertEquals("TASK6_TRACE tool=unknown status=called" + System.lineSeparator(), trace);
            assertFalse(trace.contains("synthetic-secret"));
        } finally {
            System.setErr(old);
        }
    }

    @Test
    void nullToolNameIsSafelyUnknown() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream old = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            new McpToolTraceListener().beforeExecuteTool(context(null, "synthetic-secret"));
            assertEquals("TASK6_TRACE tool=unknown status=called" + System.lineSeparator(),
                    captured.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(old);
        }
    }

    private static McpCallContext context(String name, String argument) {
        return new McpCallContext(null, new McpCallToolRequest(1L, name,
                Map.of("reason", argument)));
    }

    private static ToolExecutionResult result(boolean error, String text) {
        return ToolExecutionResult.builder().isError(error).resultText(text).build();
    }
}
