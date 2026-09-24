package com.mall.agent.model;

import java.util.List;

/**
 * 复核结论。
 *
 * <p>解析失败时按<b>驳回</b>处理——误放与误驳的代价不对称，见复核提示词。</p>
 */
public record ReviewVerdict(boolean approved, List<String> faults) {

    public ReviewVerdict {
        faults = faults == null ? List.of() : List.copyOf(faults);
    }

    /** 解析失败时的安全默认：驳回。 */
    public static ReviewVerdict rejected(String reason) {
        return new ReviewVerdict(false, List.of(reason));
    }

    public static ReviewVerdict approvedVerdict() {
        return new ReviewVerdict(true, List.of());
    }
}
