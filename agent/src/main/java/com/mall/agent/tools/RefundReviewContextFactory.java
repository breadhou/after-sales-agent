package com.mall.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mall.agent.model.CandidateRefundAction;
import com.mall.agent.model.CheckedRefundFacts;
import com.mall.agent.model.RefundReviewContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.math.BigDecimal;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/** 以原始输入和重新读取的 MCP 事实组装复核上下文，不接收决策 Agent 的推理过程。 */
public final class RefundReviewContextFactory
        implements BiFunction<Long, String, RefundReviewContext> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Current backend AfterSalesPolicy.resolve status domain; do not infer finer policy selection here. */
    private static final Set<String> REFUNDABLE_STATUSES = Set.of("SHIPPED", "DELIVERED", "RECEIVED");

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
                new CandidateRefundAction(orderId, reason), null);
    }

    public CheckedRefundFacts requireEligible(Long orderId, String reason, String originalUserRequest) {
        if (orderId == null || orderId <= 0 || reason == null || reason.isBlank()
                || originalUserRequest == null || originalUserRequest.isBlank()) {
            throw new RefundFactsUnavailableException("缺少已确认的退款申请");
        }

        JsonNode order = readFact("get_order", orderId);
        JsonNode eligibility = readFact("get_refund_eligibility", orderId);
        if (!text(order, "status") || !order.path("totalAmount").isNumber()
                || !text(eligibility, "orderStatus")
                || !text(eligibility, "catalogFingerprint")
                || !eligibility.path("eligible").isBoolean()
                || !eligibility.path("refundExists").isBoolean()
                || !eligibility.path("refundableAmount").isNumber()) {
            throw new RefundFactsUnavailableException("订单或退款资格事实缺少关键字段");
        }
        String orderStatus = order.path("status").textValue();
        if (!orderStatus.equals(eligibility.path("orderStatus").textValue())) {
            throw new RefundFactsUnavailableException("两次查询的订单状态不一致");
        }
        BigDecimal paidAmount = order.path("totalAmount").decimalValue();
        BigDecimal refundableAmount = eligibility.path("refundableAmount").decimalValue();
        if (paidAmount.signum() <= 0 || refundableAmount.signum() <= 0
                || paidAmount.compareTo(refundableAmount) != 0) {
            throw new RefundFactsUnavailableException("退款资格金额与订单实付金额不一致");
        }

        boolean eligible = eligibility.path("eligible").booleanValue();
        boolean refundExists = eligibility.path("refundExists").booleanValue();
        if (eligible && refundExists) {
            throw new RefundFactsUnavailableException("资格与已有退款记录标记矛盾");
        }
        if (!eligible) {
            if (!text(eligibility, "reason")) {
                throw new RefundFactsUnavailableException("不可退资格缺少后端原因");
            }
            throw new RefundNotEligibleException(eligibility.path("reason").textValue());
        }
        if (!REFUNDABLE_STATUSES.contains(orderStatus)) {
            throw new RefundFactsUnavailableException("可退资格与订单状态不一致");
        }

        if (!text(eligibility, "policyCode") || !text(eligibility, "policyTitle")) {
            throw new RefundFactsUnavailableException("可退资格缺少政策");
        }
        return new CheckedRefundFacts(originalUserRequest, order.toString(), eligibility.toString(),
                new CandidateRefundAction(orderId, reason), orderStatus,
                eligibility.path("policyCode").textValue(),
                eligibility.path("catalogFingerprint").textValue(), refundableAmount);
    }

    public static final class RefundNotEligibleException extends IllegalStateException {
        public RefundNotEligibleException(String reason) {
            super(reason);
        }
    }

    public static final class RefundFactsUnavailableException extends IllegalStateException {
        public RefundFactsUnavailableException(String reason) {
            super(reason);
        }

        public RefundFactsUnavailableException(String reason, Throwable cause) {
            super(reason, cause);
        }
    }

    private String read(String toolName, Long orderId) {
        JsonNode fact = readFact(toolName, orderId);
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
        }
        return fact.toString();
    }

    private JsonNode readFact(String toolName, Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw new RefundFactsUnavailableException("缺少有效订单号");
        }
        if (!"get_order".equals(toolName) && !"get_refund_eligibility".equals(toolName)) {
            throw new IllegalArgumentException("未预期的复核查询: " + toolName);
        }
        ObjectNode arguments = MAPPER.createObjectNode().put("orderId", orderId);
        ToolExecutionResult result;
        try {
            result = toolCaller.apply(ToolExecutionRequest.builder()
                    .name(toolName)
                    .arguments(arguments.toString())
                    .build());
        } catch (RuntimeException e) {
            throw new RefundFactsUnavailableException("无法读取复核所需事实: " + toolName, e);
        }
        if (result == null || result.isError()) {
            throw new RefundFactsUnavailableException("无法读取复核所需事实: " + toolName);
        }
        String resultText = result.resultText();
        if (resultText == null || resultText.isBlank()) {
            throw new RefundFactsUnavailableException("无法读取复核所需事实: " + toolName);
        }

        JsonNode fact;
        try {
            fact = MAPPER.readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(resultText);
        } catch (JsonProcessingException e) {
            throw new RefundFactsUnavailableException("复核事实不是完整 JSON: " + toolName, e);
        }
        if (fact == null || !fact.isObject()) {
            throw new RefundFactsUnavailableException("复核事实不是对象: " + toolName);
        }
        String idField = "get_order".equals(toolName) ? "id" : "orderId";
        JsonNode factId = fact.path(idField);
        if (!factId.isIntegralNumber() || !factId.canConvertToLong()
                || factId.longValue() != orderId) {
            throw new RefundFactsUnavailableException("复核事实订单号不匹配: " + toolName);
        }
        return fact;
    }

    private static boolean text(JsonNode fact, String field) {
        JsonNode value = fact.path(field);
        return value.isTextual() && !value.textValue().isBlank();
    }
}
