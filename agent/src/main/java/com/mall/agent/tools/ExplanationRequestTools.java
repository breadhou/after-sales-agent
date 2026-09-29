package com.mall.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.concurrent.ConcurrentLinkedQueue;

/** The decision model may mark a request, but cannot retrieve sources or answer from it. */
public final class ExplanationRequestTools {

    private final ConcurrentLinkedQueue<Long> requestedOrderIds = new ConcurrentLinkedQueue<>();

    @Tool(name = "request_explanation", value = "仅记录资料解释请求；政策、FAQ 和商品证据由可信代码检索。")
    public String requestExplanation(@P(name = "orderId", value = "明确的订单 ID；无订单时传 0") Long orderId) {
        requestedOrderIds.add(orderId == null ? 0L : orderId);
        return "已记录资料解释请求，等待可信代码检索。";
    }

    public void clear() {
        requestedOrderIds.clear();
    }

    /** Multiple markers in one turn are ambiguous and must not select an order. */
    public Long takeOrderId() {
        Long first = requestedOrderIds.poll();
        if (first == null) return null;
        boolean ambiguous = false;
        Long next;
        while ((next = requestedOrderIds.poll()) != null) {
            if (!next.equals(first)) ambiguous = true;
        }
        return ambiguous ? -1L : first;
    }
}
