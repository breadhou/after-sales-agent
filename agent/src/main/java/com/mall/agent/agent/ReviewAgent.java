package com.mall.agent.agent;

import com.mall.agent.model.ReviewVerdict;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 复核 Agent：独立审视一笔退款申请。
 *
 * <p>它不接入决策 Agent 的会话记忆，以隔离两者的上下文。</p>
 */
public interface ReviewAgent {

    @SystemMessage(fromResource = "prompts/review-system.txt")
    ReviewVerdict review(@UserMessage String refundContext);
}
