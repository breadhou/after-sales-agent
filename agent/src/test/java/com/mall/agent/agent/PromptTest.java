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
    void decisionPrompt_shouldRequireEligibilityCheckBeforeRefund() throws IOException {
        String prompt = load("decision-system.txt");

        assertTrue(prompt.contains("get_refund_eligibility"),
                "决策提示词必须要求执行退款前先查资格");
    }

    @Test
    void decisionPrompt_shouldRequireFreshEligibilityWithoutExistingRefundBeforeRequest() throws IOException {
        String prompt = load("decision-system.txt");

        assertTrue(prompt.contains("eligible=true") && prompt.contains("refundExists=false"),
                "提交申请前必须同时确认本次资格可退且没有既有退款记录");
        assertTrue(prompt.contains("不表示已退款"),
                "资格查询通过不能被解释为退款已经完成");
    }

    @Test
    void decisionPrompt_shouldInterpretExistingRefundThroughItsReason() throws IOException {
        String prompt = load("decision-system.txt");

        assertTrue(prompt.contains("request_refund") && prompt.contains("已归一化"),
                "决策提示词应以本地工具归一化后的执行结论为准");
        assertTrue(prompt.contains("处理中") && prompt.contains("已完成"),
                "本地工具回执必须区分处理中和已完成退款");
    }

    @Test
    void decisionPrompt_shouldGroundPolicyExplanationInEligibilityResult() throws IOException {
        String prompt = load("decision-system.txt");

        assertTrue(prompt.contains("完整目录") && prompt.contains("policyCode"),
                "可退资格必须按 policyCode 在完整目录中匹配条款");
        assertTrue(prompt.contains("eligibility.reason") && prompt.contains("不得随意选择"),
                "不可退时必须解释资格 reason，不能从目录中任意挑选条款");
    }

    @Test
    void decisionPrompt_shouldNotMentionTheRawRefundTool() throws IOException {
        String prompt = load("decision-system.txt");

        // submit_refund 不在决策 Agent 的工具集里，提示词里也不该出现，
        // 否则模型可能试图调用一个不存在的工具
        assertFalse(prompt.contains("submit_refund"),
                "决策提示词提到了它根本调用不到的工具");
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
