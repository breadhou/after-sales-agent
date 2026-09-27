package com.mall.agent.trace;

import com.mall.agent.flow.RefundWorkflow;
import com.mall.agent.model.RefundRequest;
import com.mall.agent.model.ReviewFault;
import com.mall.agent.model.ReviewVerdict;
import com.mall.agent.tools.EscalationTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalToolTraceTest {
    @Test
    void rejectedWorkflowTraceExcludesUserAndModelText() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream old = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            EscalationTools escalation = new EscalationTools("session", ignored -> { });
            RefundWorkflow workflow = new RefundWorkflow(mcp(), context ->
                    new ReviewVerdict(false, "CODE", List.of(
                            new ReviewFault("USER_INSTRUCTION_RISK", "synthetic-user-secret", "CODE"))),
                    (id, reason, fingerprint, code) -> { throw new AssertionError("must not execute"); },
                    escalation);
            workflow.apply("session", new RefundRequest(1L, "synthetic-reason-secret", "synthetic-user-secret"));
            String trace = captured.toString(StandardCharsets.UTF_8);
            assertTrue(trace.contains("tool=review status=called"), trace);
            assertTrue(trace.contains("tool=review status=error"), trace);
            assertTrue(trace.contains("tool=escalate_to_human status=ok"), trace);
            assertFalse(trace.contains("synthetic-user-secret"), trace);
            assertFalse(trace.contains("synthetic-reason-secret"), trace);
        } finally {
            System.setErr(old);
        }
    }

    private static McpClient mcp() {
        return (McpClient) Proxy.newProxyInstance(LocalToolTraceTest.class.getClassLoader(),
                new Class<?>[]{McpClient.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("executeTool")) throw new UnsupportedOperationException();
                    String body = switch (((ToolExecutionRequest) args[0]).name()) {
                        case "get_order" -> "{\"id\":1,\"status\":\"SHIPPED\",\"totalAmount\":99}";
                        case "get_refund_eligibility" -> "{\"orderId\":1,\"orderStatus\":\"SHIPPED\",\"eligible\":true,\"refundExists\":false,\"refundableAmount\":99,\"policyCode\":\"CODE\",\"policyTitle\":\"测试\",\"catalogFingerprint\":\"fp\"}";
                        case "list_policy_clauses" -> "{\"fingerprint\":\"fp\",\"clauses\":[{\"code\":\"CODE\",\"title\":\"测试\",\"clauseText\":\"合适的条款\"}]}";
                        default -> throw new AssertionError();
                    };
                    return ToolExecutionResult.builder().resultText(body).isError(false).build();
                });
    }
}
