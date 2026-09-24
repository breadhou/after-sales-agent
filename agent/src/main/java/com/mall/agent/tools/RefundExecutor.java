package com.mall.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.util.function.BiFunction;
import java.util.function.Function;

/** MCP submit_refund 在 Agent 客户端的唯一调用点；只在复核通过后由 RefundRequestTools 使用。 */
public class RefundExecutor implements BiFunction<Long, String, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Function<ToolExecutionRequest, ToolExecutionResult> toolCaller;

    public RefundExecutor(McpClient mcp) {
        this(mcp::executeTool);
    }

    /** 测试可替换 MCP 调用，仍经过真实的执行结果检查。 */
    RefundExecutor(Function<ToolExecutionRequest, ToolExecutionResult> toolCaller) {
        this.toolCaller = toolCaller;
    }

    @Override
    public String apply(Long orderId, String reason) {
        ObjectNode arguments = MAPPER.createObjectNode()
                .put("orderId", orderId)
                .put("reason", reason);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("submit_refund")
                .arguments(arguments.toString())
                .build();

        ToolExecutionResult result = toolCaller.apply(request);
        if (result == null || result.isError()) {
            throw new IllegalStateException("退款提交未返回可确认的执行结果");
        }
        String resultText = result.resultText();
        if (resultText == null || resultText.isBlank()) {
            throw new IllegalStateException("退款提交未返回可确认的执行结果");
        }
        return resultText;
    }
}
