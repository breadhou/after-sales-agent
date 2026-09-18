# supermall 售后能力 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 supermall 具备确定性的售后能力——资格判定、金额计算、退款执行，全部由代码完成，供后续 MCP server 包装成工具。

**Architecture:** 政策定义为枚举，**同一个枚举同时提供判定参数与条款文本**，保证「代码判定的规则」与「agent 解释用的文本」同源。资格查询与执行分离：查询无副作用，执行走幂等 + 原子事务。

**Tech Stack:** Java 17 / Spring Boot 3.4.4 / MyBatis-Plus 3.5.10 / MySQL 8

**仓库:** 本计划的全部改动都在 **supermall** 仓库（`D:\sourcecode\supermall`），不在 after-sales-agent。

**前置:** supermall 当前处于干净状态，`main` 已推送。开工前先 `git checkout -b feat/after-sales-capability`。

**命令约定**：本计划多处需要查库。先定义这个函数，后续步骤直接复用（不要依赖外部脚本，临时目录会被系统清理）：

```bash
mysql_q() {
  "/d/MySQL/MySQL Server 8.0/bin/mysql" -uroot -p123456 -N -B \
    --default-character-set=utf8mb4 -e "$1" 2>/dev/null
}
```

---

## 背景：为什么这些改动是必要的

Agent 要"真执行"退款，而 supermall 现在的退款能力有三个硬缺口：

1. **没有执行** —— `POST /api/orders/{id}/refund` 只插入一条 `PENDING` 记录，不改订单状态、没有后续
2. **没有幂等** —— 调两次插两条。Agent 会重试、会循环，**一次重试就是一笔重复退款**
3. **没有政策** —— 7 天无理由之类的规则完全不存在，agent 无从判断

本计划补齐这三项，**不改动任何现有接口的行为**，全部是新增。

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `module/order/enums/AfterSalesPolicy.java` | 政策定义：判定参数 + 条款文本同源 |
| `module/order/entity/vo/RefundEligibilityVO.java` | 资格查询结果 |
| `module/order/entity/vo/PolicyClauseVO.java` | 政策条款（供 RAG 索引） |
| `module/order/entity/dto/RefundReasonDTO.java` | 退款原因（**只有 reason，不含金额**） |
| `module/order/service/RefundEligibilityService.java` | 纯判定逻辑，无副作用 |
| `module/order/service/impl/RefundEligibilityServiceImpl.java` | 上述实现 |
| `module/order/service/RefundExecutionService.java` | 执行：幂等 + 原子 |
| `module/order/service/impl/RefundExecutionServiceImpl.java` | 上述实现 |
| `module/order/controller/OrderController.java` | **修改**：新增资格查询与执行端点 |
| `module/order/controller/AfterSalesPolicyController.java` | 条款列表端点 |
| `mall-common/enums/ResultStatus.java` | **修改**：新增售后错误码 |

**分层约束（沿用项目约定）**：Controller 只做路由与校验，业务判断在 Service，归属校验统一走 `UserContext`。`RefundEligibilityService` 是**只读**的，任何写操作只出现在 `RefundExecutionService`。

---

## Task 1: 给 refund 表加唯一索引

**为什么先做这个**：没有它，后面所有幂等设计都是空谈。

**Files:**
- Modify: `mall-server/src/main/resources/db/init.sql`
- Create: `mall-server/src/main/resources/db/migration/2026-09-18-refund-unique.sql`

- [ ] **Step 1: 写迁移脚本**

创建 `mall-server/src/main/resources/db/migration/2026-09-18-refund-unique.sql`：

```sql
-- 一单一退：Agent 会重试，没有这个约束一次重试就是一笔重复退款
ALTER TABLE refund ADD UNIQUE KEY uk_refund_order (order_id);
```

- [ ] **Step 2: 同步修改 init.sql**

在 `init.sql` 的 `refund` 建表语句里，把 `PRIMARY KEY (id)` 那行改为：

```sql
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_order (order_id)
```

- [ ] **Step 3: 在现有数据库上执行迁移**

```bash
mysql_q "ALTER TABLE mall.refund ADD UNIQUE KEY uk_refund_order (order_id);"
```

- [ ] **Step 4: 验证索引已生效**

```bash
mysql_q "SELECT index_name, non_unique FROM information_schema.statistics WHERE table_schema='mall' AND table_name='refund';"
```

Expected: 出现 `uk_refund_order`，且 `non_unique = 0`

- [ ] **Step 5: 验证约束真的会挡重复**

```bash
mysql_q "SELECT COUNT(*) FROM mall.refund;"
# 记下当前行数，若为 0 则先造一条
mysql_q "INSERT INTO mall.refund (id, order_id, user_id, reason, amount, status, created_at) VALUES (999000000000000001, 999000000000000001, 1, 'test', 1.00, 'PENDING', NOW());"
mysql_q "INSERT INTO mall.refund (id, order_id, user_id, reason, amount, status, created_at) VALUES (999000000000000002, 999000000000000001, 1, 'dup', 1.00, 'PENDING', NOW());"
```

Expected: 第二条报 `Duplicate entry`（错误信息会显示为 mysql 客户端错误，这是**预期的**）

清理：`mysql_q "DELETE FROM mall.refund WHERE order_id = 999000000000000001;"`

- [ ] **Step 6: 提交**

```bash
git add mall-server/src/main/resources/db/
git commit -m "fix: add unique index on refund.order_id to make refunds idempotent"
```

---

## Task 2: 售后政策枚举（判定与文本同源）

**Files:**
- Create: `mall-server/src/main/java/com/mall/module/order/enums/AfterSalesPolicy.java`
- Test: `mall-server/src/test/java/com/mall/module/order/enums/AfterSalesPolicyTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `AfterSalesPolicyTest.java`：

```java
package com.mall.module.order.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AfterSalesPolicyTest {

    @Test
    void everyPolicyCarriesBothRuleAndClauseText() {
        for (AfterSalesPolicy policy : AfterSalesPolicy.values()) {
            assertFalse(policy.getClauseText().isBlank(),
                    policy + " 缺少条款文本，RAG 将无据可依");
            assertFalse(policy.getTitle().isBlank(), policy + " 缺少标题");
        }
    }

    @Test
    void sevenDayPolicy_shouldRejectAfterWindow() {
        // 签收后 8 天，超出 7 天窗口
        assertFalse(AfterSalesPolicy.SEVEN_DAY_NO_REASON.appliesTo("RECEIVED", 8));
        assertTrue(AfterSalesPolicy.SEVEN_DAY_NO_REASON.appliesTo("RECEIVED", 7));
        assertTrue(AfterSalesPolicy.SEVEN_DAY_NO_REASON.appliesTo("RECEIVED", 0));
    }

    @Test
    void sevenDayPolicy_shouldNotApplyBeforeReceipt() {
        assertFalse(AfterSalesPolicy.SEVEN_DAY_NO_REASON.appliesTo("SHIPPED", 1));
        assertFalse(AfterSalesPolicy.SEVEN_DAY_NO_REASON.appliesTo("PAID", 1));
    }

    @Test
    void shippedNotReceived_shouldOnlyApplyToShippedOrDelivered() {
        assertTrue(AfterSalesPolicy.SHIPPED_NOT_RECEIVED.appliesTo("SHIPPED", 3));
        assertTrue(AfterSalesPolicy.SHIPPED_NOT_RECEIVED.appliesTo("DELIVERED", 3));
        assertFalse(AfterSalesPolicy.SHIPPED_NOT_RECEIVED.appliesTo("RECEIVED", 3));
        assertFalse(AfterSalesPolicy.SHIPPED_NOT_RECEIVED.appliesTo("PAID", 3));
    }

    @Test
    void qualityIssue_shouldApplyToReceivedOrdersWithoutTimeLimit() {
        assertTrue(AfterSalesPolicy.QUALITY_ISSUE.appliesTo("RECEIVED", 100));
        assertFalse(AfterSalesPolicy.QUALITY_ISSUE.appliesTo("SHIPPED", 1));
    }

    @Test
    void resolve_shouldReturnFirstMatchingPolicyInPriorityOrder() {
        // 签收 2 天：7天无理由 与 质量问题 都成立，取优先级更高的 7天无理由
        assertEquals(AfterSalesPolicy.SEVEN_DAY_NO_REASON,
                AfterSalesPolicy.resolve("RECEIVED", 2));
        // 签收 30 天：只有质量问题成立
        assertEquals(AfterSalesPolicy.QUALITY_ISSUE,
                AfterSalesPolicy.resolve("RECEIVED", 30));
        // 已发货未签收
        assertEquals(AfterSalesPolicy.SHIPPED_NOT_RECEIVED,
                AfterSalesPolicy.resolve("SHIPPED", 1));
    }

    @Test
    void resolve_shouldReturnNullWhenNothingApplies() {
        assertEquals(null, AfterSalesPolicy.resolve("PAID", 1));
        assertEquals(null, AfterSalesPolicy.resolve("PENDING", 1));
        assertEquals(null, AfterSalesPolicy.resolve("CANCELLED", 1));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -Dtest=AfterSalesPolicyTest
```

Expected: 编译失败，`找不到符号: 类 AfterSalesPolicy`

- [ ] **Step 3: 实现枚举**

创建 `AfterSalesPolicy.java`：

```java
package com.mall.module.order.enums;

import java.util.Arrays;

/**
 * 售后政策。
 *
 * <p><b>判定参数与条款文本刻意放在同一处。</b>Agent 侧会用 RAG 检索条款来组织解释，
 * 如果文本另有来源，就可能出现「代码判定拒绝、解释却说可以」的矛盾。放在一起，
 * 一致性由构造保证，而不是靠约定。</p>
 *
 * <p>{@link #resolve} 按声明顺序取第一个命中的政策，因此<b>顺序即优先级</b>。</p>
 */
public enum AfterSalesPolicy {

    /** 签收后 7 天内无理由。 */
    SEVEN_DAY_NO_REASON("7 天无理由退货",
            "自签收之日起 7 天内，商品未使用且不影响二次销售的，可申请无理由退货。",
            7) {
        @Override
        public boolean appliesTo(String orderStatus, long daysSinceReceipt) {
            return "RECEIVED".equals(orderStatus) && daysSinceReceipt <= windowDays;
        }
    },

    /** 已发货但尚未签收。 */
    SHIPPED_NOT_RECEIVED("已发货未签收退款",
            "订单已发货但尚未签收的，可申请退款；退款在货物退回后完成。",
            0) {
        @Override
        public boolean appliesTo(String orderStatus, long daysSinceReceipt) {
            return "SHIPPED".equals(orderStatus) || "DELIVERED".equals(orderStatus);
        }
    },

    /** 质量问题，不受 7 天窗口限制。 */
    QUALITY_ISSUE("质量问题退货",
            "商品存在质量问题的，凭有效凭证可申请退货退款，不受 7 天期限限制。",
            0) {
        @Override
        public boolean appliesTo(String orderStatus, long daysSinceReceipt) {
            return "RECEIVED".equals(orderStatus);
        }
    };

    private final String title;
    private final String clauseText;
    /** 窗口天数，0 表示该政策不按天限制。 */
    protected final long windowDays;

    AfterSalesPolicy(String title, String clauseText, long windowDays) {
        this.title = title;
        this.clauseText = clauseText;
        this.windowDays = windowDays;
    }

    public String getTitle() {
        return title;
    }

    public String getClauseText() {
        return clauseText;
    }

    /** 该政策是否适用于当前订单状态与签收天数。 */
    public abstract boolean appliesTo(String orderStatus, long daysSinceReceipt);

    /**
     * 选出适用政策，落在声明顺序靠前的优先。
     *
     * @return 适用政策；无任何政策适用时返回 {@code null}
     */
    public static AfterSalesPolicy resolve(String orderStatus, long daysSinceReceipt) {
        return Arrays.stream(values())
                .filter(policy -> policy.appliesTo(orderStatus, daysSinceReceipt))
                .findFirst()
                .orElse(null);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -Dtest=AfterSalesPolicyTest
```

Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add mall-server/src/main/java/com/mall/module/order/enums/ mall-server/src/test/java/com/mall/module/order/enums/
git commit -m "feat: add after-sales policy enum with rule and clause text co-located"
```

---

## Task 3: 新增订单状态 REFUNDED 与售后错误码

**Files:**
- Modify: `mall-common/src/main/java/com/mall/common/enums/ResultStatus.java`
- Modify: `mall-server/src/main/resources/db/init.sql`（仅注释）

- [ ] **Step 1: 新增错误码**

在 `ResultStatus` 的订单模块段（50000）末尾追加：

```java
    ORDER_NOT_REFUNDABLE(50002, "该订单当前不可退款"),
    REFUND_ALREADY_EXISTS(50003, "该订单已有退款记录"),
    REFUND_NOT_EXECUTABLE(50004, "退款记录状态不允许执行"),
```

- [ ] **Step 2: 更新 init.sql 的状态注释**

把 `order` 表 `status` 字段的注释改为（加入 `REFUNDED`）：

```sql
    status       VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PAID/SHIPPED/DELIVERED/RECEIVED/REFUNDED/CANCELLED',
```

- [ ] **Step 3: 编译确认无误**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" -q compile
```

Expected: 无输出（编译成功）

- [ ] **Step 4: 提交**

```bash
git add mall-common/src/main/java/com/mall/common/enums/ResultStatus.java mall-server/src/main/resources/db/init.sql
git commit -m "feat: add after-sales error codes and REFUNDED order status"
```

---

## Task 4: 资格判定服务

**Files:**
- Create: `mall-server/src/main/java/com/mall/module/order/entity/vo/RefundEligibilityVO.java`
- Create: `mall-server/src/main/java/com/mall/module/order/service/RefundEligibilityService.java`
- Create: `mall-server/src/main/java/com/mall/module/order/service/impl/RefundEligibilityServiceImpl.java`
- Test: `mall-server/src/test/java/com/mall/module/order/service/impl/RefundEligibilityServiceImplTest.java`

**设计要点**：本服务**只读**，不写库。金额一律从订单取，不接受任何入参。

- [ ] **Step 1: 创建 VO**

```java
package com.mall.module.order.entity.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 售后资格查询结果。
 *
 * <p>{@code policyCode} 是判定与解释之间的接缝：代码给出政策码，agent 据此
 * 检索对应条款来组织解释。</p>
 */
@Data
@Accessors(chain = true)
public class RefundEligibilityVO {

    private Long orderId;
    /** 当前是否可退。 */
    private boolean eligible;
    /** 不可退时的原因，可直接展示给用户。 */
    private String reason;
    /** 适用政策码，不可退时为 null。 */
    private String policyCode;
    private String policyTitle;
    /** 可退金额，由服务端从订单算出。 */
    private BigDecimal refundableAmount;
    /** 是否已有退款记录（含已完成），用于避免重复申请。 */
    private boolean refundExists;
}
```

- [ ] **Step 2: 写失败的测试**

```java
package com.mall.module.order.service.impl;

import com.mall.module.order.entity.po.Order;
import com.mall.module.order.entity.po.Refund;
import com.mall.module.order.entity.vo.RefundEligibilityVO;
import com.mall.module.order.enums.AfterSalesPolicy;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.module.order.mapper.RefundMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundEligibilityServiceImplTest {

    private static final Long ORDER_ID = 9001L;

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private RefundMapper refundMapper;

    private RefundEligibilityServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RefundEligibilityServiceImpl(orderMapper, refundMapper);
    }

    private Order order(String status, int daysAgo) {
        return new Order()
                .setId(ORDER_ID)
                .setStatus(status)
                .setTotalAmount(new BigDecimal("199.99"))
                .setCreatedAt(LocalDateTime.now().minusDays(daysAgo));
    }

    @Test
    void shouldBeEligibleWithinSevenDays() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("RECEIVED", 2));
        when(refundMapper.selectOne(any())).thenReturn(null);

        RefundEligibilityVO vo = service.check(ORDER_ID);

        assertTrue(vo.isEligible());
        assertEquals(AfterSalesPolicy.SEVEN_DAY_NO_REASON.name(), vo.getPolicyCode());
        assertEquals(new BigDecimal("199.99"), vo.getRefundableAmount());
        assertFalse(vo.isRefundExists());
    }

    @Test
    void shouldBeIneligibleAfterSevenDaysForNoReasonPolicy() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("RECEIVED", 30));
        when(refundMapper.selectOne(any())).thenReturn(null);

        RefundEligibilityVO vo = service.check(ORDER_ID);

        // 30 天后 7 天无理由失效，但质量问题仍成立
        assertTrue(vo.isEligible());
        assertEquals(AfterSalesPolicy.QUALITY_ISSUE.name(), vo.getPolicyCode());
    }

    @Test
    void shouldBeIneligibleWhenOrderIsPending() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("PENDING", 1));
        when(refundMapper.selectOne(any())).thenReturn(null);

        RefundEligibilityVO vo = service.check(ORDER_ID);

        assertFalse(vo.isEligible());
        assertNull(vo.getPolicyCode());
        assertNotNull(vo.getReason());
    }

    @Test
    void shouldReportExistingRefundAndRefuseToRefundAgain() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("RECEIVED", 2));
        when(refundMapper.selectOne(any()))
                .thenReturn(new Refund().setId(1L).setOrderId(ORDER_ID).setStatus("PENDING"));

        RefundEligibilityVO vo = service.check(ORDER_ID);

        assertTrue(vo.isRefundExists());
        assertFalse(vo.isEligible(), "已有退款记录时不应再判为可退");
    }

    @Test
    void shouldRefundFullOrderAmountNotPartial() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order("SHIPPED", 1));
        when(refundMapper.selectOne(any())).thenReturn(null);

        RefundEligibilityVO vo = service.check(ORDER_ID);

        assertEquals(new BigDecimal("199.99"), vo.getRefundableAmount());
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -Dtest=RefundEligibilityServiceImplTest
```

Expected: 编译失败，`找不到符号: 类 RefundEligibilityServiceImpl`

- [ ] **Step 4: 实现接口与实现类**

`RefundEligibilityService.java`：

```java
package com.mall.module.order.service;

import com.mall.module.order.entity.vo.RefundEligibilityVO;

public interface RefundEligibilityService {

    /** 只读：判断订单的售后资格与可退金额，无任何副作用。 */
    RefundEligibilityVO check(Long orderId);
}
```

`RefundEligibilityServiceImpl.java`：

```java
package com.mall.module.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.module.order.entity.po.Order;
import com.mall.module.order.entity.po.Refund;
import com.mall.module.order.entity.vo.RefundEligibilityVO;
import com.mall.module.order.enums.AfterSalesPolicy;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.module.order.mapper.RefundMapper;
import com.mall.module.order.service.RefundEligibilityService;
import com.mall.security.utils.UserContext;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class RefundEligibilityServiceImpl implements RefundEligibilityService {

    private final OrderMapper orderMapper;
    private final RefundMapper refundMapper;

    public RefundEligibilityServiceImpl(OrderMapper orderMapper, RefundMapper refundMapper) {
        this.orderMapper = orderMapper;
        this.refundMapper = refundMapper;
    }

    @Override
    public RefundEligibilityVO check(Long orderId) {
        Long userId = UserContext.getUserId();
        Order order = orderMapper.selectById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(ResultStatus.ORDER_NOT_EXIST);
        }

        RefundEligibilityVO vo = new RefundEligibilityVO()
                .setOrderId(orderId)
                .setRefundableAmount(order.getTotalAmount());

        Refund existing = refundMapper.selectOne(
                new LambdaQueryWrapper<Refund>().eq(Refund::getOrderId, orderId));
        vo.setRefundExists(existing != null);
        if (existing != null) {
            return vo.setEligible(false).setReason("该订单已有退款记录，不能重复申请");
        }

        long daysSinceReceipt = daysSince(order.getCreatedAt());
        AfterSalesPolicy policy = AfterSalesPolicy.resolve(order.getStatus(), daysSinceReceipt);
        if (policy == null) {
            return vo.setEligible(false)
                    .setReason("订单当前状态（" + order.getStatus() + "）不符合任何售后政策");
        }

        return vo.setEligible(true)
                .setPolicyCode(policy.name())
                .setPolicyTitle(policy.getTitle());
    }

    /**
     * 距签收天数。当前订单表没有签收时间字段，用创建时间近似——
     * 这是本阶段的已知简化，签收时间落地后应替换。
     */
    private long daysSince(LocalDateTime from) {
        if (from == null) {
            return 0;
        }
        return Duration.between(from, LocalDateTime.now()).toDays();
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -Dtest=RefundEligibilityServiceImplTest
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 6: 提交**

```bash
git add mall-server/src/main/java/com/mall/module/order/ mall-server/src/test/java/com/mall/module/order/
git commit -m "feat: add read-only refund eligibility check"
```

---

## Task 5: 退款执行服务（幂等 + 原子）

**Files:**
- Create: `mall-server/src/main/java/com/mall/module/order/service/RefundExecutionService.java`
- Create: `mall-server/src/main/java/com/mall/module/order/service/impl/RefundExecutionServiceImpl.java`
- Test: `mall-server/src/test/java/com/mall/module/order/service/impl/RefundExecutionServiceImplTest.java`

**设计要点**：执行是**写**操作。重复调用必须返回同一结果而不是报错——Agent 会重试。

- [ ] **Step 1: 写失败的测试**

```java
package com.mall.module.order.service.impl;

import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.module.order.entity.po.Order;
import com.mall.module.order.entity.po.Refund;
import com.mall.module.order.entity.vo.RefundEligibilityVO;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.module.order.mapper.RefundMapper;
import com.mall.module.order.service.RefundEligibilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundExecutionServiceImplTest {

    private static final Long ORDER_ID = 9001L;

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private RefundMapper refundMapper;
    @Mock
    private RefundEligibilityService eligibilityService;

    private RefundExecutionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RefundExecutionServiceImpl(orderMapper, refundMapper, eligibilityService);
    }

    private void givenEligible() {
        when(eligibilityService.check(ORDER_ID)).thenReturn(new RefundEligibilityVO()
                .setOrderId(ORDER_ID)
                .setEligible(true)
                .setPolicyCode("SEVEN_DAY_NO_REASON")
                .setRefundableAmount(new BigDecimal("199.99")));
        when(orderMapper.selectByIdForUpdate(ORDER_ID))
                .thenReturn(new Order().setId(ORDER_ID).setStatus("RECEIVED")
                        .setTotalAmount(new BigDecimal("199.99")));
    }

    @Test
    void execute_shouldWriteRefundAndAdvanceOrderToRefunded() {
        givenEligible();
        when(refundMapper.selectOne(any())).thenReturn(null);

        service.execute(ORDER_ID, "不想要了");

        ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
        verify(refundMapper).insert(captor.capture());
        assertEquals(new BigDecimal("199.99"), captor.getValue().getAmount());
        assertEquals("REFUNDED", captor.getValue().getStatus());

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).updateById(orderCaptor.capture());
        assertEquals("REFUNDED", orderCaptor.getValue().getStatus());
    }

    @Test
    void execute_shouldBeIdempotentWhenRefundAlreadyCompleted() {
        givenEligible();
        when(refundMapper.selectOne(any())).thenReturn(
                new Refund().setId(1L).setOrderId(ORDER_ID).setStatus("REFUNDED"));

        service.execute(ORDER_ID, "重复请求");

        // 幂等：不再插入、不再改订单
        verify(refundMapper, never()).insert(any());
        verify(orderMapper, never()).updateById(any());
    }

    @Test
    void execute_shouldRefuseWhenNotEligible() {
        when(eligibilityService.check(ORDER_ID)).thenReturn(new RefundEligibilityVO()
                .setOrderId(ORDER_ID).setEligible(false).setReason("不符合政策"));

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.execute(ORDER_ID, "试试"));

        assertEquals(ResultStatus.ORDER_NOT_REFUNDABLE, exception.getStatus());
        verify(refundMapper, never()).insert(any());
    }

    @Test
    void execute_shouldIgnoreClientSuppliedAmount() {
        givenEligible();
        when(refundMapper.selectOne(any())).thenReturn(null);

        service.execute(ORDER_ID, "我要求退 99999");

        ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
        verify(refundMapper).insert(captor.capture());
        // 金额来自服务端计算，与用户说法无关
        assertEquals(new BigDecimal("199.99"), captor.getValue().getAmount());
    }

    @Test
    void execute_shouldLockOrderRowBeforeWriting() {
        givenEligible();
        when(refundMapper.selectOne(any())).thenReturn(null);

        service.execute(ORDER_ID, "并发测试");

        // 先锁行再写，防并发重复执行
        verify(orderMapper).selectByIdForUpdate(ORDER_ID);
        verify(orderMapper, never()).selectById(anyLong());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -Dtest=RefundExecutionServiceImplTest
```

Expected: 编译失败，`找不到符号: 类 RefundExecutionServiceImpl`

- [ ] **Step 3: 实现**

`RefundExecutionService.java`：

```java
package com.mall.module.order.service;

import com.mall.module.order.entity.vo.RefundEligibilityVO;

public interface RefundExecutionService {

    /**
     * 执行退款。幂等：重复调用返回首次结果，不报错也不重复退款。
     *
     * @param reason 用户给出的退款原因，仅作记录，不影响金额
     */
    RefundEligibilityVO execute(Long orderId, String reason);
}
```

`RefundExecutionServiceImpl.java`：

```java
package com.mall.module.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.module.order.entity.po.Order;
import com.mall.module.order.entity.po.Refund;
import com.mall.module.order.entity.vo.RefundEligibilityVO;
import com.mall.module.order.mapper.OrderMapper;
import com.mall.module.order.mapper.RefundMapper;
import com.mall.module.order.service.RefundEligibilityService;
import com.mall.module.order.service.RefundExecutionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class RefundExecutionServiceImpl implements RefundExecutionService {

    private static final String REFUNDED = "REFUNDED";

    private final OrderMapper orderMapper;
    private final RefundMapper refundMapper;
    private final RefundEligibilityService eligibilityService;

    public RefundExecutionServiceImpl(OrderMapper orderMapper,
                                      RefundMapper refundMapper,
                                      RefundEligibilityService eligibilityService) {
        this.orderMapper = orderMapper;
        this.refundMapper = refundMapper;
        this.eligibilityService = eligibilityService;
    }

    @Override
    @Transactional
    public RefundEligibilityVO execute(Long orderId, String reason) {
        // 先判资格：不符合就直接拒绝，不进入写路径
        RefundEligibilityVO eligibility = eligibilityService.check(orderId);
        if (!eligibility.isEligible()) {
            throw new BusinessException(ResultStatus.ORDER_NOT_REFUNDABLE);
        }

        // 锁订单行，防并发重复执行；锁后再查一次退款记录才可靠
        Order order = orderMapper.selectByIdForUpdate(orderId);
        if (order == null) {
            throw new BusinessException(ResultStatus.ORDER_NOT_EXIST);
        }

        Refund existing = refundMapper.selectOne(
                new LambdaQueryWrapper<Refund>().eq(Refund::getOrderId, orderId));
        if (existing != null) {
            // 幂等分支：Agent 重试会走到这里。不报错、不重复退款，返回既有结果。
            return new RefundEligibilityVO()
                    .setOrderId(orderId)
                    .setEligible(false)
                    .setRefundExists(true)
                    .setRefundableAmount(existing.getAmount())
                    .setReason("该订单已完成退款");
        }

        refundMapper.insert(new Refund()
                .setId(SnowflakeIdUtil.nextId())
                .setOrderId(orderId)
                .setUserId(order.getUserId())
                // 金额一律取服务端算出的值，绝不用入参
                .setAmount(eligibility.getRefundableAmount())
                .setReason(reason)
                .setStatus(REFUNDED)
                .setCreatedAt(LocalDateTime.now()));

        order.setStatus(REFUNDED);
        orderMapper.updateById(order);

        return eligibility;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -Dtest=RefundExecutionServiceImplTest
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add mall-server/src/main/java/com/mall/module/order/ mall-server/src/test/java/com/mall/module/order/
git commit -m "feat: add idempotent refund execution with server-side amount"
```

---

## Task 6: 暴露两个 HTTP 端点

**Files:**
- Modify: `mall-server/src/main/java/com/mall/module/order/controller/OrderController.java`
- Test: `mall-server/src/test/java/com/mall/module/order/controller/OrderControllerTest.java`（新建）

- [ ] **Step 1: 写失败的测试**

```java
package com.mall.module.order.controller;

import com.mall.common.result.Result;
import com.mall.module.order.entity.vo.RefundEligibilityVO;
import com.mall.module.order.service.RefundEligibilityService;
import com.mall.module.order.service.RefundExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderControllerRefundTest {

    private static final Long ORDER_ID = 9001L;

    @Mock
    private RefundEligibilityService eligibilityService;
    @Mock
    private RefundExecutionService executionService;

    @InjectMocks
    private OrderController controller;

    @Test
    void eligibilityEndpoint_shouldReturnServiceResult() {
        when(eligibilityService.check(ORDER_ID)).thenReturn(new RefundEligibilityVO()
                .setOrderId(ORDER_ID).setEligible(true)
                .setPolicyCode("SEVEN_DAY_NO_REASON")
                .setRefundableAmount(new BigDecimal("199.99")));

        Result<RefundEligibilityVO> result = controller.refundEligibility(ORDER_ID);

        assertEquals(0, result.getCode());
        assertEquals("SEVEN_DAY_NO_REASON", result.getData().getPolicyCode());
    }

    @Test
    void executeEndpoint_shouldDelegateWithReasonOnly() {
        when(executionService.execute(ORDER_ID, "不想要了")).thenReturn(
                new RefundEligibilityVO().setOrderId(ORDER_ID).setEligible(true));

        controller.executeRefund(ORDER_ID, new RefundReasonDTO("不想要了"));

        // 端点只透传原因，金额由服务端决定
        verify(executionService).execute(ORDER_ID, "不想要了");
    }
}
```

同时创建原因 DTO：

`mall-server/src/main/java/com/mall/module/order/entity/dto/RefundReasonDTO.java`：

```java
package com.mall.module.order.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 退款原因。刻意只有一个字段——金额不接受客户端传入。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundReasonDTO {

    @NotBlank
    @Size(max = 512)
    private String reason;
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -Dtest=OrderControllerRefundTest
```

Expected: 编译失败，`找不到符号: 方法 refundEligibility`

- [ ] **Step 3: 在 OrderController 中新增端点**

在 `OrderController` 中注入两个新服务并添加端点：

```java
    @Autowired
    RefundEligibilityService refundEligibilityService;

    @Autowired
    RefundExecutionService refundExecutionService;

    /** 查询该订单的售后资格与可退金额。只读。 */
    @GetMapping("/{id}/refund-eligibility")
    public Result<RefundEligibilityVO> refundEligibility(@PathVariable Long id) {
        Result<RefundEligibilityVO> result = Result.build();
        result.success(refundEligibilityService.check(id));
        return result;
    }

    /**
     * 执行退款。幂等——Agent 会重试，重复调用返回既有结果而非报错。
     * 金额由服务端决定，请求体只携带原因。
     */
    @PostMapping("/{id}/refund/execute")
    public Result<RefundEligibilityVO> executeRefund(@PathVariable Long id,
                                                     @Valid @RequestBody RefundReasonDTO dto) {
        Result<RefundEligibilityVO> result = Result.build();
        result.success(refundExecutionService.execute(id, dto.getReason()));
        return result;
    }
```

- [ ] **Step 4: 运行测试确认通过**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -Dtest=OrderControllerRefundTest
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: 跑全量测试确认没影响既有行为**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test
```

Expected: `BUILD SUCCESS`，既有 188 个测试全绿

- [ ] **Step 6: 提交**

```bash
git add mall-server/src/main/java/com/mall/module/order/
git commit -m "feat: expose refund eligibility and execution endpoints"
```

---

## Task 7: 暴露政策条款端点（供 Agent 侧建 RAG 索引）

**为什么单独一个端点**：spec §4.2 要求「判定规则与解释文本同源」。条款文本存在 `AfterSalesPolicy` 枚举里，Agent 侧需要把它拉过去索引——**这是同源得以成立的那条通路**，缺了它，RAG 的文本就只能另找来源，一致性就没了保障。

**Files:**
- Create: `mall-server/src/main/java/com/mall/module/order/entity/vo/PolicyClauseVO.java`
- Create: `mall-server/src/main/java/com/mall/module/order/controller/AfterSalesPolicyController.java`
- Test: `mall-server/src/test/java/com/mall/module/order/controller/AfterSalesPolicyControllerTest.java`

- [ ] **Step 1: 创建 VO**

```java
package com.mall.module.order.entity.vo;

import lombok.Data;
import lombok.experimental.Accessors;

/** 政策条款。供 Agent 侧建立检索索引。 */
@Data
@Accessors(chain = true)
public class PolicyClauseVO {

    private String code;
    private String title;
    private String clauseText;
}
```

- [ ] **Step 2: 写失败的测试**

```java
package com.mall.module.order.controller;

import com.mall.common.result.Result;
import com.mall.module.order.entity.vo.PolicyClauseVO;
import com.mall.module.order.enums.AfterSalesPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AfterSalesPolicyControllerTest {

    private final AfterSalesPolicyController controller = new AfterSalesPolicyController();

    @Test
    void shouldExposeEveryPolicyWithCodeAndClauseText() {
        Result<List<PolicyClauseVO>> result = controller.listPolicies();

        assertEquals(0, result.getCode());
        List<PolicyClauseVO> clauses = result.getData();
        assertEquals(AfterSalesPolicy.values().length, clauses.size());

        for (PolicyClauseVO clause : clauses) {
            assertNotNull(clause.getCode());
            assertFalse(clause.getClauseText().isBlank(),
                    clause.getCode() + " 的条款文本为空，RAG 将无据可依");
        }
    }

    @Test
    void codeShouldMatchEnumNameSoDecisionAndExplanationShareAKey() {
        Result<List<PolicyClauseVO>> result = controller.listPolicies();

        // 资格接口返回的 policyCode 必须能在这里找到对应条款
        boolean found = result.getData().stream()
                .anyMatch(c -> "SEVEN_DAY_NO_REASON".equals(c.getCode()));
        assertTrue(found, "资格接口给出的政策码在条款列表里找不到对应项");
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -Dtest=AfterSalesPolicyControllerTest
```

Expected: 编译失败，`找不到符号: 类 AfterSalesPolicyController`

- [ ] **Step 4: 实现控制器**

```java
package com.mall.module.order.controller;

import com.mall.common.result.Result;
import com.mall.module.order.entity.vo.PolicyClauseVO;
import com.mall.module.order.enums.AfterSalesPolicy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 售后政策条款。直接来自 {@link AfterSalesPolicy} 枚举——
 * 与判定逻辑同一份定义，Agent 侧索引的就是这里返回的文本。
 */
@RestController
@RequestMapping("/api/after-sales")
public class AfterSalesPolicyController {

    @GetMapping("/policies")
    public Result<List<PolicyClauseVO>> listPolicies() {
        List<PolicyClauseVO> clauses = Arrays.stream(AfterSalesPolicy.values())
                .map(policy -> new PolicyClauseVO()
                        .setCode(policy.name())
                        .setTitle(policy.getTitle())
                        .setClauseText(policy.getClauseText()))
                .toList();

        Result<List<PolicyClauseVO>> result = Result.build();
        result.success(clauses);
        return result;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -Dtest=AfterSalesPolicyControllerTest
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 6: 提交**

```bash
git add mall-server/src/main/java/com/mall/module/order/
git commit -m "feat: expose after-sales policy clauses for agent-side indexing"
```

---

## Task 8: 真实环境端到端验证

**Files:** 无代码改动，仅验证

- [ ] **Step 1: 重新打包并重启应用**

```bash
# 停掉正在运行的应用（记录其 task id），然后：
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" clean package -DskipTests

export MERCHANT_JWT_SECRET=$(grep '^MERCHANT_JWT_SECRET=' /c/Users/hou16/AppData/Local/Temp/mall-itest/merchant-credentials.txt | cut -d= -f2-)
export MALL_WORKER_ID=1
export MALL_DATACENTER_ID=1
java -jar mall-server/target/mall-server-1.0.0.jar --server.port=8081 --spring.profiles.active=loadtest
```

Expected: 日志出现 `Started MallApplication`，且 `Snowflake identity configured: workerId=1, datacenterId=1`

- [ ] **Step 2: 造一张已签收的订单**

完整链路：注册用户 → 建地址 → 下单 → 支付 → 用商家端发货 → 商家端送达 → 用户确认收货，得到一张 `RECEIVED` 订单。

调用顺序（`$BASE=http://localhost:8081`）：

```bash
# 1. 注册 + 登录，拿 $CT
curl -s -X POST $BASE/api/auth/register -H 'Content-Type: application/json' \
  -d "{\"username\":\"aftersales_t1\",\"password\":\"$PW\",\"phone\":\"13700001234\"}"

# 2. 建地址（中文必须走 UTF-8 文件）
printf '%s' '{"receiver":"售后验证","phone":"13700001234","province":"广东省","city":"深圳市","district":"南山区","detail":"测试路1号","isDefault":1}' > /tmp/addr.json
curl -s -X POST -H "Authorization: Bearer $CT" -H 'Content-Type: application/json' \
  --data-binary @/tmp/addr.json "$BASE/api/address"

# 3. 下单
printf '%s' "{\"addressId\":$ADDR,\"items\":[{\"skuId\":930000000000000001,\"quantity\":1}]}" > /tmp/order.json
curl -s -X POST -H "Authorization: Bearer $CT" -H 'Content-Type: application/json' \
  --data-binary @/tmp/order.json "$BASE/api/orders"      # → 记下 orderId 与 orderNo

# 4. 支付
curl -s -X POST -H "Authorization: Bearer $CT" "$BASE/api/orders/$ORDER_ID/pay"

# 5. 商家端发货 + 送达（需商家 token，见 jmeter/prepare-admin-token.ps1 同源的管理员/商家账号）
printf '%s' '{"company":"顺丰速运","trackingNo":"SF-AFTER-1"}' > /tmp/ship.json
curl -s -X POST -H "Authorization: Bearer $MT" -H 'Content-Type: application/json' \
  --data-binary @/tmp/ship.json "$BASE/api/merchant/orders/$ORDER_NO/ship"
curl -s -X POST -H "Authorization: Bearer $MT" "$BASE/api/merchant/orders/$ORDER_NO/deliver"

# 6. 用户确认收货
curl -s -X PUT -H "Authorization: Bearer $CT" "$BASE/api/orders/$ORDER_ID/receive"
```

Expected: `mysql_q "SELECT status FROM mall.\`order\` WHERE id=$ORDER_ID;"` 返回 `RECEIVED`

> **两个已知的坑**：中文必须走 UTF-8 文件（直接 `curl -d` 会被 Git Bash 按 GBK 发出，服务端报 `Invalid UTF-8 start byte`）；`AddressDTO.isDefault` 是 `Integer`，传 `1` 不是 `true`。

- [ ] **Step 3: 查资格**

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8081/api/orders/$ORDER_ID/refund-eligibility"
```

Expected: `eligible: true`，`policyCode: "SEVEN_DAY_NO_REASON"`，`refundableAmount` 等于订单实付金额

- [ ] **Step 4: 执行退款**

```bash
printf '%s' '{"reason":"不想要了"}' > /tmp/refund.json
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary @/tmp/refund.json \
  "http://localhost:8081/api/orders/$ORDER_ID/refund/execute"
```

Expected: 成功，订单状态变为 `REFUNDED`

- [ ] **Step 5: 验证幂等——重复执行**

再调一次同样的请求。

Expected: 返回成功（不报错），且：

```bash
mysql_q "SELECT COUNT(*) FROM mall.refund WHERE order_id=$ORDER_ID;"
```

Expected: **1**（不是 2）

- [ ] **Step 6: 验证越权被挡**

用**另一个用户**的 token 请求同一订单。

Expected: `ORDER_NOT_EXIST(50000)`

- [ ] **Step 7: 验证金额不可指定**

用 `{"reason":"...","amount":99999}` 调用（多余字段），确认落库金额仍是订单实付金额。

- [ ] **Step 8: 提交验证记录**

在 `docs/` 下记录本轮验证结果（造了哪些数据、每步的返回、最终一致性核对），提交。

---

## 完成标准

- [ ] 全量测试通过且既有 188 个测试未受影响
- [ ] 新建的 5 个测试类全绿：`AfterSalesPolicyTest`(7)、`RefundEligibilityServiceImplTest`(5)、`RefundExecutionServiceImplTest`(5)、`OrderControllerRefundTest`(2)、`AfterSalesPolicyControllerTest`(2)
- [ ] 真实环境下：查资格 → 执行 → 幂等 → 越权 → 金额不可指定，五项全部符合预期
- [ ] `refund` 表有 `uk_refund_order` 唯一索引
- [ ] 订单可进入 `REFUNDED` 状态
- [ ] `GET /api/after-sales/policies` 返回的条款码与资格接口返回的 `policyCode` 能对上

---

## 已知简化（写进代码注释与验证记录）

1. **签收时间用 `order.created_at` 近似** —— 订单表没有签收时间字段。这会让"7 天窗口"的判定偏严（下单到签收通常有 1~3 天）。签收时间落地后应替换。
2. **只支持整单退款** —— 金额恒等于订单实付金额，不支持部分退款。
3. **退款即时完成** —— 没有对接真实支付网关，`REFUNDED` 是终态。

这三条都是**有意为之**，不影响 agent 侧的评测设计（评测判的是"动作是否正确"，不是"退款是否到账"）。

---

## 做完之后

**本计划只是第一步。** 项目全貌与进度见仓库根目录的 `README.md`。

| 下一步 | 文档 | 依赖 |
|---|---|---|
| **计划 B：MCP Server** | `docs/plans/2026-09-18-plan-b-mcp-server.md` | **本计划的三个端点** |
| 计划 C：Agent | `docs/plans/2026-09-18-plan-c-agent.md` | 计划 B |
| 阶段 3：RAG 解释层 | 尚未编写——待设计 | 计划 C |
| 阶段 4：240 条评测集 | 尚未编写——待设计 | 计划 C |

**本计划完成后**：`refund` 表有唯一索引、订单可进入 `REFUNDED`、三个售后端点可用（资格查询 / 执行退款 / 政策条款）。此时**用 curl 就能完整验证售后链路，全程不涉及 AI**。

请顺手更新 `README.md` 的进度表，把 A 标为已完成。
