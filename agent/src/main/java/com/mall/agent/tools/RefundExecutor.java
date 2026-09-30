package com.mall.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.math.BigDecimal;
import java.util.function.Function;

/** MCP submit_refund 在 Agent 客户端的唯一调用点；只接收已复核的政策版本与条款。 */
public class RefundExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Function<ToolExecutionRequest, ToolExecutionResult> toolCaller;

    public RefundExecutor(McpClient mcp) {
        this(mcp::executeTool);
    }

    /** 测试可替换 MCP 调用，仍经过真实的执行结果检查。 */
    RefundExecutor(Function<ToolExecutionRequest, ToolExecutionResult> toolCaller) {
        this.toolCaller = toolCaller;
    }

    public String apply(Long orderId, String reason, String expectedCatalogFingerprint,
                        String expectedPolicyCode) {
        if (orderId == null || orderId <= 0 || reason == null || reason.isBlank()
                || expectedCatalogFingerprint == null || expectedCatalogFingerprint.isBlank()
                || expectedPolicyCode == null || expectedPolicyCode.isBlank()) {
            throw new IllegalArgumentException("缺少已复核的退款参数");
        }
        ObjectNode arguments = MAPPER.createObjectNode()
                .put("orderId", orderId)
                .put("reason", reason)
                .put("expectedCatalogFingerprint", expectedCatalogFingerprint)
                .put("expectedPolicyCode", expectedPolicyCode);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("submit_refund")
                .arguments(arguments.toString())
                .build();

        ToolExecutionResult result;
        try {
            result = toolCaller.apply(request);
        } catch (ToolExecutionException e) {
            // The MCP SDK throws for an application-level isError result, carrying
            // our server's structured business envelope in its message cause.
            Throwable cause = e.getCause();
            if (e.errorCode() == null && cause != null
                    && cause.getClass() == RuntimeException.class && cause.getCause() == null
                    && isStaleReview(cause.getMessage())) {
                throw new StaleReviewException();
            }
            throw e;
        }
        if (result == null) {
            throw new IllegalStateException("退款提交未返回可确认的执行结果");
        }
        if (result.isError()) {
            if (isStaleReview(result.resultText())) {
                throw new StaleReviewException();
            }
            throw new IllegalStateException("退款提交未返回可确认的执行结果");
        }
        String resultText = result.resultText();
        if (resultText == null || resultText.isBlank()) {
            throw new IllegalStateException("退款提交未返回可确认的执行结果");
        }
        return confirmedResult(orderId, resultText);
    }

    private static boolean isStaleReview(String text) {
        if (text == null || text.isBlank()) return false;
        try {
            JsonNode error = MAPPER.readerFor(JsonNode.class)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(text);
            return error != null && error.isObject() && error.size() == 3
                    && error.path("error").isBoolean() && error.path("error").booleanValue()
                    && error.path("code").isIntegralNumber()
                    && error.path("code").canConvertToLong()
                    && error.path("code").longValue() == 50005L
                    && error.path("message").isTextual()
                    && !error.path("message").textValue().isBlank();
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /** Backend's explicit pre-write 50005 means no new refund was created. */
    public static final class StaleReviewException extends IllegalStateException {
        public StaleReviewException() { super("复核依据已过期"); }
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
