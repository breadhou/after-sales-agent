package com.mall.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.math.BigDecimal;
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
        return confirmedResult(orderId, resultText);
    }

    /** 后端成功回执的三个确定语义；其余形态的执行结果只能视为不确定。 */
    private static String confirmedResult(Long orderId, String resultText) {
        final JsonNode receipt;
        try {
            receipt = MAPPER.readTree(resultText);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("退款提交未返回可确认的执行结果", e);
        }
        if (receipt == null || !receipt.isObject()
                || !receipt.path("orderId").isIntegralNumber()
                || receipt.path("orderId").longValue() != orderId
                || !receipt.path("eligible").isBoolean()
                || !receipt.path("refundExists").isBoolean()
                || !receipt.path("refundableAmount").isNumber()) {
            throw new IllegalStateException("退款提交未返回可确认的执行结果");
        }
        BigDecimal amount = receipt.path("refundableAmount").decimalValue();
        if (amount.signum() < 0) {
            throw new IllegalStateException("退款提交未返回可确认的执行结果");
        }
        String amountText = amount.stripTrailingZeros().toPlainString();
        boolean eligible = receipt.path("eligible").booleanValue();
        boolean existed = receipt.path("refundExists").booleanValue();
        if (eligible && !existed) {
            return "订单 " + orderId + " 的退款已完成，退款金额 " + amountText
                    + " 元，订单状态已更新为 REFUNDED。本次合规复核和退款执行均已完成。";
        }
        if (!eligible && existed) {
            String reason = receipt.path("reason").asText("");
            if ("该订单已完成退款".equals(reason)) {
                return "订单 " + orderId + " 此前已完成退款，退款金额 " + amountText
                        + " 元，订单状态为 REFUNDED；此次没有重复退款。";
            }
            if ("该订单已有退款申请在处理中".equals(reason)) {
                return "订单 " + orderId + " 已有退款申请在处理中，尚未完成退款；"
                        + "请联系人工客服核实状态。";
            }
        }
        throw new IllegalStateException("退款提交未返回可确认的执行结果");
    }
}
