package com.mall.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mall.agent.model.CandidateRefundAction;
import com.mall.agent.model.RefundReviewContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/** 以原始输入和重新读取的 MCP 事实组装复核上下文，不接收决策 Agent 的推理过程。 */
public final class RefundReviewContextFactory
        implements BiFunction<Long, String, RefundReviewContext> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Function<ToolExecutionRequest, ToolExecutionResult> toolCaller;
    private final Supplier<String> originalUserRequest;

    public RefundReviewContextFactory(McpClient mcp, Supplier<String> originalUserRequest) {
        this(mcp::executeTool, originalUserRequest);
    }

    /** 测试可替换 MCP 调用，仍经过事实解析与校验。 */
    RefundReviewContextFactory(Function<ToolExecutionRequest, ToolExecutionResult> toolCaller,
                               Supplier<String> originalUserRequest) {
        this.toolCaller = toolCaller;
        this.originalUserRequest = originalUserRequest;
    }

    @Override
    public RefundReviewContext apply(Long orderId, String reason) {
        String rawRequest = originalUserRequest.get();
        if (rawRequest == null || rawRequest.isBlank()) {
            throw new IllegalStateException("缺少本轮原始用户输入");
        }
        return new RefundReviewContext(
                rawRequest,
                read("get_order", orderId),
                read("get_refund_eligibility", orderId),
                new CandidateRefundAction(orderId, reason));
    }

    private String read(String toolName, Long orderId) {
        ObjectNode arguments = MAPPER.createObjectNode().put("orderId", orderId);
        ToolExecutionResult result = toolCaller.apply(ToolExecutionRequest.builder()
                .name(toolName)
                .arguments(arguments.toString())
                .build());
        if (result == null || result.isError()) {
            throw new IllegalStateException("无法读取复核所需事实: " + toolName);
        }
        String resultText = result.resultText();
        if (resultText == null || resultText.isBlank()) {
            throw new IllegalStateException("无法读取复核所需事实: " + toolName);
        }

        JsonNode fact;
        try {
            fact = MAPPER.readTree(resultText);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("复核事实不是有效 JSON: " + toolName, e);
        }
        if (fact == null || !fact.isObject()) {
            throw new IllegalStateException("复核事实不是对象: " + toolName);
        }
        String idField = "get_order".equals(toolName) ? "id" : "orderId";
        JsonNode factId = fact.path(idField);
        if (!factId.isIntegralNumber() || !factId.canConvertToLong()
                || factId.longValue() != orderId) {
            throw new IllegalStateException("复核事实订单号不匹配: " + toolName);
        }
        if ("get_order".equals(toolName)) {
            if (!text(fact, "status") || !fact.path("totalAmount").isNumber()) {
                throw new IllegalStateException("订单事实缺少状态或实付金额");
            }
        } else if ("get_refund_eligibility".equals(toolName)) {
            JsonNode eligible = fact.path("eligible");
            if (!eligible.isBoolean() || !fact.path("refundExists").isBoolean()) {
                throw new IllegalStateException("资格事实缺少判定或退款记录标记");
            }
            if (eligible.booleanValue()) {
                if (!fact.path("refundableAmount").isNumber()
                        || !text(fact, "policyCode") || !text(fact, "policyTitle")) {
                    throw new IllegalStateException("可退资格缺少金额或政策");
                }
            } else if (!text(fact, "reason")) {
                throw new IllegalStateException("不可退资格缺少原因");
            }
        } else {
            throw new IllegalArgumentException("未预期的复核查询: " + toolName);
        }
        return fact.toString();
    }

    private static boolean text(JsonNode fact, String field) {
        JsonNode value = fact.path(field);
        return value.isTextual() && !value.textValue().isBlank();
    }
}
