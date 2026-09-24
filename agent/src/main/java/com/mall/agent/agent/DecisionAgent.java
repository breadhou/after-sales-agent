package com.mall.agent.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/** 决策 Agent：理解诉求、查证事实、选择动作。 */
public interface DecisionAgent {

    @SystemMessage(fromResource = "prompts/decision-system.txt")
    String handle(@MemoryId String sessionId, @UserMessage String userMessage);
}
