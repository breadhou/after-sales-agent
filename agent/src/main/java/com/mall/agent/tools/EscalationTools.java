package com.mall.agent.tools;

import com.mall.agent.model.EscalationRecord;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** 本阶段的人工升级只写入会话记录与日志，不代表已有可供人工领取的工单。 */
public class EscalationTools {

    private static final Logger log = LoggerFactory.getLogger(EscalationTools.class);

    private final String sessionId;
    private final List<EscalationRecord> records = new CopyOnWriteArrayList<>();
    private final Consumer<EscalationRecord> sink;

    public EscalationTools(String sessionId, Consumer<EscalationRecord> sink) {
        this.sessionId = sessionId;
        this.sink = sink;
    }

    @Tool(name = "escalate_to_human", value = "记录本次会话的人工升级请求，并引导用户联系人工客服。用于你无法按政策处理、或用户对政策解释不接受时。")
    public String escalateToHuman(@P(name = "orderId", value = "订单 ID，没有明确订单时传 0") Long orderId,
                                  @P(name = "summary", value = "升级原因摘要，一句话说明为什么需要人工介入") String summary) {
        EscalationRecord record = EscalationRecord.of(sessionId, orderId, summary);
        records.add(record);
        sink.accept(record);
        log.info("会话升级人工 session={} orderId={} reason={}", sessionId, orderId, summary);
        return "已记录本次升级请求。请引导用户联系人工客服继续处理。";
    }

    /** 本次会话的升级记录，供评测断言使用。 */
    public List<EscalationRecord> records() {
        return List.copyOf(records);
    }
}
