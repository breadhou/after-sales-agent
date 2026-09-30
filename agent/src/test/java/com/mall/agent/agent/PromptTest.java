package com.mall.agent.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 提示词是行为的一部分，它的关键约束必须被锁住——
 * 否则一次无意的改写就可能悄悄拿掉某条防线。
 */
class PromptTest {

    private String load(String name) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("prompts/" + name)) {
            assertNotNull(in, "找不到提示词文件 " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void decisionPrompt_shouldDescribeOnlyHandoffAndEligibilityMarkers() throws IOException {
        String prompt = load("decision-system.txt");

        assertTrue(prompt.contains("handoff_refund"));
        assertTrue(prompt.contains("ask_refund_eligibility"));
        assertTrue(prompt.contains("escalate_to_human"));
        assertTrue(prompt.contains("request_explanation"));
        assertTrue(prompt.contains("资料解释"));
        assertFalse(prompt.contains("request_refund"));
        assertFalse(prompt.contains("submit_refund"));
        assertFalse(prompt.contains("get_refund_eligibility"));
        assertFalse(prompt.contains("list_policy_clauses"));
    }

    @Test
    void decisionPrompt_shouldTreatHandoffAsAnUnconfirmedCandidate() throws IOException {
        String prompt = load("decision-system.txt");

        assertTrue(prompt.contains("候选"));
        assertTrue(prompt.contains("用户确认"));
        assertTrue(prompt.contains("不能声称已退款"));
    }

    @Test
    void decisionPrompt_shouldKeepOrdinaryConversationAndReadOnlyQueries() throws IOException {
        String prompt = load("decision-system.txt");

        assertTrue(prompt.contains("get_order"));
        assertTrue(prompt.contains("list_user_orders"));
        assertTrue(prompt.contains("get_logistics"));
    }

    @Test
    void decisionPrompt_shouldInstructRefusalOnCoercion() throws IOException {
        String prompt = load("decision-system.txt");

        assertTrue(prompt.contains("升级") || prompt.contains("拒绝"),
                "决策提示词必须说明遇到越权诉求时怎么办");
    }

    @Test
    void reviewPrompt_shouldFrameTheQuestionAsFindingFaults() throws IOException {
        String prompt = load("review-system.txt");

        // 复核的价值来自视角不同：不是"再确认一遍"，而是"找出问题"
        assertTrue(prompt.contains("驳回") || prompt.contains("问题"),
                "复核提示词应从'找出问题'的角度提问");
    }

    @Test
    void reviewPrompt_shouldRequireTrustedFactsAndOriginalRequest() throws IOException {
        String prompt = load("review-system.txt");

        assertTrue(prompt.contains("原始用户诉求") && prompt.contains("可信"),
                "复核必须看可信事实与未改写的原始用户诉求");
        assertTrue(prompt.contains("推理过程"),
                "复核提示词必须明确排除决策 Agent 的推理过程");
    }

    @Test
    void reviewPrompt_shouldRequireEligibleRequestWithoutExistingRefund() throws IOException {
        String prompt = load("review-system.txt");

        assertTrue(prompt.contains("eligible=true && refundExists=false"),
                "复核通过必须同时要求可退资格和无既有退款记录");
    }
}
