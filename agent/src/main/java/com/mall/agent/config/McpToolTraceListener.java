package com.mall.agent.config;

import com.mall.agent.trace.ToolTrace;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpClientListener;
import dev.langchain4j.mcp.protocol.McpCallToolParams;
import dev.langchain4j.mcp.protocol.McpCallToolRequest;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.util.Map;

/** One listener for both model-visible reads and the internal refund submission. */
final class McpToolTraceListener implements McpClientListener {

    @Override
    public void beforeExecuteTool(McpCallContext context) {
        ToolTrace.record(toolName(context), ToolTrace.Status.CALLED);
    }

    @Override
    public void afterExecuteTool(McpCallContext context, ToolExecutionResult result,
                                 Map<String, Object> rawResult) {
        ToolTrace.record(toolName(context),
                result != null && !result.isError() ? ToolTrace.Status.OK : ToolTrace.Status.ERROR);
    }

    @Override
    public void onExecuteToolError(McpCallContext context, Throwable error) {
        ToolTrace.record(toolName(context), ToolTrace.Status.TRANSPORT_ERROR);
    }

    private static String toolName(McpCallContext context) {
        if (context != null && context.message() instanceof McpCallToolRequest request
                && request.getParams() instanceof McpCallToolParams params) {
            return params.getName();
        }
        return "unknown";
    }
}
