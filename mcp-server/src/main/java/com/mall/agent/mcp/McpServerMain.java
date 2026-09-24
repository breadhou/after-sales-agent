package com.mall.agent.mcp;

import com.mall.agent.mcp.tools.OrderTools;
import com.mall.agent.mcp.tools.PolicyTools;
import com.mall.agent.mcp.tools.RefundTools;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Stdio MCP server. Stdout is reserved for JSON-RPC; simplelogger routes logs to stderr.
 */
public final class McpServerMain {

    private static final Logger LOG = LoggerFactory.getLogger(McpServerMain.class);
    private static final int INVALID_ARGUMENT_CODE = 10000;

    private McpServerMain() {
    }

    public record ToolDefinition(String name, String description, String inputSchema) {
    }

    private static final List<ToolDefinition> TOOLS = List.of(
            new ToolDefinition("get_order",
                    "查询指定订单的状态与金额；需要订单 ID 时先用 list_user_orders。",
                    """
                    {"type":"object","properties":{"orderId":{"type":"integer","minimum":1,"maximum":9223372036854775807,"description":"订单 ID"}},
                     "required":["orderId"],"additionalProperties":false}
                    """),
            new ToolDefinition("list_user_orders",
                    "列出当前用户的订单；可按状态筛选（PENDING/PAID/SHIPPED/DELIVERED/RECEIVED/REFUNDED/CANCELLED）。",
                    """
                    {"type":"object","properties":{"status":{"type":"string","description":"订单状态，可省略"}},
                     "additionalProperties":false}
                    """),
            new ToolDefinition("get_logistics",
                    "查询指定订单的物流信息。",
                    """
                    {"type":"object","properties":{"orderId":{"type":"integer","minimum":1,"maximum":9223372036854775807,"description":"订单 ID"}},
                     "required":["orderId"],"additionalProperties":false}
                    """),
            new ToolDefinition("get_refund_eligibility",
                    "查询退款资格和政策依据。仅 eligible=true 时，refundableAmount 才是本次可退金额；"
                            + "eligible=false 时该数值只是金额观察值，不能据此执行退款。"
                            + "refundExists 仅表示已有退款记录，可能仍为 PENDING，不能据此声称已退款。"
                            + "执行退款前必须先查询本工具。",
                    """
                    {"type":"object","properties":{"orderId":{"type":"integer","minimum":1,"maximum":9223372036854775807,"description":"订单 ID"}},
                     "required":["orderId"],"additionalProperties":false}
                    """),
            new ToolDefinition("list_policy_clauses",
                    "获取售后政策条款原文与目录指纹，用于解释系统的资格判定。",
                    """
                    {"type":"object","properties":{},"additionalProperties":false}
                    """),
            new ToolDefinition("submit_refund",
                    "按订单执行退款；金额由系统根据订单确定，调用方无法指定。"
                            + "调用前须先用 get_refund_eligibility 确认 eligible=true。"
                            + "返回的 refundExists 只表示已有记录，不能单凭该字段声称已退款。",
                    """
                    {"type":"object","properties":{
                      "orderId":{"type":"integer","minimum":1,"maximum":9223372036854775807,"description":"订单 ID"},
                      "reason":{"type":"string","minLength":1,"maxLength":512,"description":"退款原因，最多 512 字符"}},
                     "required":["orderId","reason"],"additionalProperties":false}
                    """));

    public static List<ToolDefinition> toolDefinitions() {
        return TOOLS;
    }

    public static void main(String[] args) {
        String token = System.getenv("SUPERMALL_TOKEN");
        if (token == null || token.isBlank()) {
            LOG.error("SUPERMALL_TOKEN 未设置，无法启动 MCP server");
            System.exit(1);
            return;
        }

        String baseUrl = System.getenv().getOrDefault("SUPERMALL_BASE_URL", "http://localhost:8081");
        startServer(new StdioServerTransportProvider(), new SupermallClient(baseUrl, token));
        LOG.info("after-sales MCP server 已启动（stdio）");
    }

    static McpSyncServer startServer(McpServerTransportProvider transport, SupermallClient client) {
        OrderTools orders = new OrderTools(client);
        RefundTools refunds = new RefundTools(client);
        PolicyTools policies = new PolicyTools(client);

        List<McpServerFeatures.SyncToolSpecification> specs = List.of(
                spec("get_order", args -> orders.getOrder(longArg(args, "orderId"))),
                spec("list_user_orders", args -> orders.listUserOrders(optionalStringArg(args, "status"))),
                spec("get_logistics", args -> orders.getLogistics(longArg(args, "orderId"))),
                spec("get_refund_eligibility", args -> refunds.getRefundEligibility(longArg(args, "orderId"))),
                spec("list_policy_clauses", args -> policies.listPolicyClauses()),
                spec("submit_refund", args -> refunds.submitRefund(
                        longArg(args, "orderId"), requiredReason(args))));

        return McpServer.sync(transport)
                .serverInfo("after-sales-mcp", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(specs)
                .build();
    }

    @FunctionalInterface
    private interface ToolCall {
        String run(Map<String, Object> args);
    }

    private static McpServerFeatures.SyncToolSpecification spec(String name, ToolCall call) {
        ToolDefinition definition = TOOLS.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst().orElseThrow();
        McpSchema.Tool tool = new McpSchema.Tool(
                definition.name(), definition.description(), definition.inputSchema());

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, args) -> {
            try {
                validateFields(args, tool);
                return new McpSchema.CallToolResult(call.run(args), false);
            } catch (InvalidToolArguments exception) {
                return new McpSchema.CallToolResult(
                        ToolResults.failure(new SupermallException(INVALID_ARGUMENT_CODE, exception.getMessage())), true);
            } catch (SupermallException exception) {
                LOG.warn("工具 {} 调用失败：code={}", name, exception.getCode());
                return new McpSchema.CallToolResult(ToolResults.failure(exception), true);
            } catch (RuntimeException exception) {
                LOG.error("工具 {} 出现内部错误：type={}", name, exception.getClass().getSimpleName());
                return new McpSchema.CallToolResult(
                        ToolResults.failure(new SupermallException(-1, "工具调用失败，请稍后重试")), true);
            }
        });
    }

    private static void validateFields(Map<String, Object> args, McpSchema.Tool tool) {
        if (args == null || !tool.inputSchema().properties().keySet().containsAll(args.keySet())) {
            throw new InvalidToolArguments("参数不合法：存在未支持的字段或参数对象缺失");
        }
    }

    private static long longArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        long parsed;
        if (value instanceof Integer integer) {
            parsed = integer;
        } else if (value instanceof Long longValue) {
            parsed = longValue;
        } else if (value instanceof BigInteger bigInteger) {
            try {
                parsed = bigInteger.longValueExact();
            } catch (ArithmeticException exception) {
                throw new InvalidToolArguments("参数不合法：" + key + " 必须是 long 范围内的正整数");
            }
        } else {
            throw new InvalidToolArguments("参数不合法：" + key + " 必须是 long 范围内的正整数");
        }
        if (parsed <= 0) {
            throw new InvalidToolArguments("参数不合法：" + key + " 必须是 long 范围内的正整数");
        }
        return parsed;
    }

    private static String optionalStringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        throw new InvalidToolArguments("参数不合法：" + key + " 必须是字符串");
    }

    private static String requiredReason(Map<String, Object> args) {
        Object value = args.get("reason");
        if (value instanceof String text && !text.isBlank() && text.length() <= 512) {
            return text;
        }
        throw new InvalidToolArguments("参数不合法：reason 必须是非空且不超过 512 字符的字符串");
    }

    private static final class InvalidToolArguments extends RuntimeException {
        private InvalidToolArguments(String message) {
            super(message);
        }
    }
}
