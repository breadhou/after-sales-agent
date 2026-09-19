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

**Maven 命令为什么带 `-am -Dsurefire.failIfNoSpecifiedTests=false`（2026-09-19 修订）**

本计划原文的命令是 `test -pl mall-server -Dtest=X`，**在本机跑不通**。

原因：`mall-common` / `mall-security` / `mall-infra` **从未 install 到本地仓库**——`~/.m2/repository/com/mall/` 下**只有 `.lastUpdated` 失败标记，没有真正的 jar/pom**（那是某次失败的远程解析留下的）。而 `-pl`（project list）会把这三个模块**从 reactor 里剔除**，于是 `mall-server` 的依赖只能去本地仓库找——找不到，直接 `Could not resolve dependencies`，**连编译都到不了**。

两个参数各自解决一半问题：

- **`-am`（also make）**：把被依赖的模块**加回 reactor**，按依赖顺序先构建它们。于是它们从源码参与解析，**根本不需要本地仓库里有它们**。
- **`-Dsurefire.failIfNoSpecifiedTests=false`**：`-Dtest=X` 对 reactor 里**每个模块**都生效；兄弟模块没有叫 `X` 的测试类，surefire 默认会报 `No tests were executed!` 而失败。这个参数让它别为此报错。

> **不要改成「先 `mvn install` 一次，然后照抄原命令」**——那是另一种可行解，但有个**静默陷阱**：`install` 把构件写进全局的 `~/.m2`，之后 `-pl` 就从那里取。**只要有人改了 `mall-common` 等模块而忘了重装，测试就会静默地跑在旧代码上**（签名变了会编译失败、响亮；签名没变而行为变了则完全无声）。`-am` 没有这个失效模式。
>
> 详见 `docs/known-issues.md` 的 **K-11**。

---

## 背景：为什么这些改动是必要的

Agent 要"真执行"退款，而 supermall 现在的退款能力有三个硬缺口：

1. **没有执行** —— `POST /api/orders/{id}/refund` 只插入一条 `PENDING` 记录，不改订单状态、没有后续
2. **没有幂等** —— 调两次插两条。Agent 会重试、会循环，**一次重试就是一笔重复退款**
3. **没有政策** —— 7 天无理由之类的规则完全不存在，agent 无从判断

本计划补齐这三项。**主体是纯新增**，但有一处对既有行为的必要修正，见下面的说明。

> **修订说明（2026-09-19）**：原文此处写的是「**不改动任何现有接口的行为**，全部是新增」。
> 这句话在唯一索引落地后**即不成立**——数据库约束本身就会改变重复提交的可见行为。
> 与其保留一句不准确的声称，不如把它写准：
>
> - **既有接口的正常路径行为不变**。
> - **重复提交的行为变了**：`POST /api/orders/{id}/refund` 第二次调用不再是「静默写入第二条
>   PENDING」，而是返回业务错误 `REFUND_ALREADY_EXISTS(50003)`。
>   这是**有意的**——原行为正是本计划要消除的「一次重试就是一笔重复退款」。
>
> 相应地，`OrderServiceImpl.requestRefund` 需要捕获 `DuplicateKeyException` 并转成上面的业务错误。
> 若不修，调用方看到的会是 `-1 系统异常`，与真实故障无法区分（详见 `docs/known-issues.md` 的 K-6）。
>
> **该声称必须在真实环境可演示**——见 Task 8 的 Step 6。
>
> **第二次修订（2026-09-19 晚，Task 6 质量审查后）：改变既有行为的地方有 G 处，不是一处。**
>
> 原文写「这是全计划**唯一**改变既有接口行为的地方」。Task 6 之后这句话又不成立了，
> 因为 `OrderController` 里新增的局部 `@ExceptionHandler` **按控制器生效、不是按端点生效**：
>
> | # | 改变的行为 | 来源 |
> |---|---|---|
> | 1 | `POST /api/orders/{id}/refund` 重复提交 → `50003` | Task 3 / K-6 |
> | 2 | **订单控制器内所有 `@Valid` 参数校验失败** → `10000`（原为 `-1`） | Task 6 / K-31 |
>
> 第 2 项**含与售后无关的 `POST /api/orders`（建订单）**——这是局部方案在 Spring 机制下的必然结果
> （控制器级 `@ExceptionHandler` 无法只作用于某几个端点），**已被接受**，不是疏漏。
>
> **注意第 2 项写的是「所有 `@Valid` 参数」而不是「请求体」**（2026-09-19 晚订正）：
> 初稿写「非法请求体」，并声称 `GET /api/orders`（查询参数校验）**不受影响、仍是 `-1`**。
> **那个声称是错的**——spring-web 6.2.5 的 `ModelAttributeMethodProcessor:157-158` 表明，
> 非请求体的 `@Valid` 失败**同样抛 `MethodArgumentNotValidException`**，
> 故 `GET /api/orders?pageNum=abc` 也返回 `10000`。源码证据与错误来源见 K-31 的「第二次订正」。
>
> 其余 13 个用 `@Valid` 的文件确实仍是 `-1`（它们都不在 `OrderController` 里）。

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
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -am -Dtest=AfterSalesPolicyTest -Dsurefire.failIfNoSpecifiedTests=false
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
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -am -Dtest=AfterSalesPolicyTest -Dsurefire.failIfNoSpecifiedTests=false
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

> **关于 50004 的用途（2026-09-19 注）**：它目前**全计划没有消费者**。它描述的是「记录存在、但状态不允许执行」——而现有的 `PENDING`/`REFUNDED` 两态下**不存在**这种状态。
>
> **不要为了用掉一个错误码而编造语义。** 它真正需要等的是商家审批流（见文末「已知简化」第 4 条，`APPROVED`/`REJECTED` 落地时它才有意义）。**保持预留即可**——计划 B 的工具契约可能引用它。

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
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -am -Dtest=RefundEligibilityServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false
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
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -am -Dtest=RefundEligibilityServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false
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
- Modify: `mall-server/src/main/java/com/mall/module/order/mapper/RefundMapper.java`（新增一个锁定读方法）
- Test: `mall-server/src/test/java/com/mall/module/order/service/impl/RefundExecutionServiceImplTest.java`

**设计要点**：执行是**写**操作。重复调用必须返回同一结果而不是报错——Agent 会重试。

> **修订说明（2026-09-19）：并发兜底必须用锁定读，普通 SELECT 在此处恒失败。**
>
> 初稿的 catch 分支用普通 `selectOne` 复查赢家，并注释「第二次查询读得到（新语句，新快照）」——
> **那是 READ COMMITTED 的语义**。本项目 MySQL 实测为 `REPEATABLE-READ`（`application.yml` 未覆盖），
> 而 `check()` 开头的 `orderMapper.selectById`（`RefundEligibilityServiceImpl.java:33`）已经在事务里
> 建立了 read view，**普通 SELECT 只会复用它，不会刷新**。
>
> 推论比「偶尔读不到」更强——**两个分支互补，互为充要**：
>
> - 赢家提交**早于**本事务 read view → 上方 `existing` 复查就看得见 → 提前正常返回，**进不了 catch**
> - 赢家提交**晚于**本事务 read view → 进 catch → catch 里也是普通 SELECT，复用同一旧 view → **必定读不到**
>
> 所以「进入 catch」与「复查能成功」互斥。原注释写的「理论上不可达」恰好说反了：
> **`throw e` 才是唯一可达的出口**，这条分支对它声称要处理的场景**从未生效**，并发重试会稳定漏成 `-1 系统异常`。
>
> （补充：两个事务都会先 `selectByIdForUpdate` 锁订单行，故 T2 会**阻塞**到 T1 提交——这恰是最常见的
> 并发重试形态，bug 必现而非罕见交错。也正因如此，同一订单的请求在订单行上串行化，catch 里加锁不引入死锁。）
>
> **修法**：catch 改用锁定读（`SELECT ... FOR UPDATE` 读最新已提交版本，不受快照约束），
> 按本仓库既有约定（`OrderMapper.selectByIdForUpdate`）落成 `RefundMapper` 上的专用方法。
> **不采用**改隔离级别：那会动摇本方法其余对 REPEATABLE READ 的推理，代价远大于收益。
>
> 单测抓不到这个 bug（读被 Mockito stub 掉了），因此测试里增加一条**结构性断言**钉住它，
> 并在 **Task 8 增补并发验证步骤**——声称必须可演示。

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

    /**
     * 顺序重试的真实形态：check() 会如实报告「已有退款记录」、eligible=false。
     *
     * <p><b>这个用例是前一个用例的对照。</b>上一个用 {@link #givenEligible()} 把 check()
     * stub 成了 eligible=true——而现实中只要退款行存在，check() 绝不会返回 true。
     * 那个 stub 恰好把会拦住它的组件换掉了，所以它能通过**不代表**真实组合能通过。
     * 本用例不 stub check()，直接喂入真实形态，守住守卫条件里的
     * {@code !eligibility.isRefundExists()} 这一半。</p>
     */
    @Test
    void execute_shouldTreatExistingRefundAsRetryNotRejection() {
        when(eligibilityService.check(ORDER_ID)).thenReturn(new RefundEligibilityVO()
                .setOrderId(ORDER_ID)
                .setEligible(false)
                .setRefundExists(true)
                .setReason("该订单已有退款记录，不能重复申请"));
        when(orderMapper.selectByIdForUpdate(ORDER_ID))
                .thenReturn(new Order().setId(ORDER_ID).setStatus("RECEIVED")
                        .setTotalAmount(new BigDecimal("199.99")));
        when(refundMapper.selectOne(any())).thenReturn(
                new Refund().setId(1L).setOrderId(ORDER_ID).setStatus("REFUNDED"));

        RefundEligibilityVO vo = service.execute(ORDER_ID, "重复请求");

        // 不得抛 ORDER_NOT_REFUNDABLE
        assertTrue(vo.isRefundExists());
        verify(refundMapper, never()).insert(any());
    }

    /**
     * 顺序重试且既有行仍是 PENDING（旧端点落的）——文案必须与事实相符。
     */
    @Test
    void execute_shouldNotClaimCompletedWhenExistingRefundIsStillPending() {
        when(eligibilityService.check(ORDER_ID)).thenReturn(new RefundEligibilityVO()
                .setOrderId(ORDER_ID)
                .setEligible(false)
                .setRefundExists(true)
                .setReason("该订单已有退款记录，不能重复申请"));
        when(orderMapper.selectByIdForUpdate(ORDER_ID))
                .thenReturn(new Order().setId(ORDER_ID).setStatus("RECEIVED")
                        .setTotalAmount(new BigDecimal("199.99")));
        when(refundMapper.selectOne(any())).thenReturn(
                new Refund().setId(1L).setOrderId(ORDER_ID).setStatus("PENDING"));

        RefundEligibilityVO vo = service.execute(ORDER_ID, "重复请求");

        // 钱没退、订单没推进，绝不能说「已完成退款」
        assertNotEquals("该订单已完成退款", vo.getReason());
        assertTrue(vo.getReason().contains("处理中"), vo.getReason());
    }

    /**
     * 并发兜底：复查读到过期快照（返回 null），insert 撞 uk_refund_order。
     * 必须被翻译成幂等返回，而不是漏成 -1 系统异常。
     */
    @Test
    void execute_shouldTranslateDuplicateKeyIntoIdempotentResult() {
        givenEligible();
        Refund winner = new Refund().setId(1L).setOrderId(ORDER_ID).setStatus("REFUNDED")
                .setAmount(new BigDecimal("199.99"));
        // 上方那次复查是普通 SELECT，在本事务的 read view 下读不到（见 Task 5 的修订说明）
        when(refundMapper.selectOne(any())).thenReturn(null);
        // catch 里的兜底**必须走锁定读**——只有它读最新已提交版本，不受本事务快照约束
        when(refundMapper.selectByOrderIdForUpdate(ORDER_ID)).thenReturn(winner);
        when(refundMapper.insert(any(Refund.class)))
                .thenThrow(new DuplicateKeyException("uk_refund_order"));

        RefundEligibilityVO vo = service.execute(ORDER_ID, "并发重试");

        assertTrue(vo.isRefundExists());
        assertFalse(vo.isEligible());
        // 结构性断言：本 bug 对行为断言完全不可见（读被 stub 掉了，不 stub 就必然是 null），
        // 只有这条能守住「兜底走了锁定读」。真实环境的证明在 Task 8。
        verify(refundMapper).selectByOrderIdForUpdate(ORDER_ID);
        // 下面两条把「用的是锁定读的**结果**」也钉住（2026-09-19 补）。
        // 只有上面那条 verify 是不够的：把返回值丢掉、改用硬编码的 Refund 构造结论，
        // 8/8 照样全绿——**变异测试实测确认过这个缺口**，这两条才把它堵上。
        assertEquals(new BigDecimal("199.99"), vo.getRefundableAmount());
        assertEquals("该订单已完成退款", vo.getReason());
    }

    /**
     * catch 的另一条出口：撞上的**不是**这条唯一索引时，必须原样抛出，不得吞成业务结论。
     *
     * <p>2026-09-19 补。catch 有两条出口，上面那个用例守的是「翻译成业务结论」，
     * 本用例守的是「翻译不了就别把真故障说成业务结论」——K-21 第 2 条记的正是这个家族
     * （撞主键被误报成「已有退款记录」）。</p>
     */
    @Test
    void execute_shouldRethrowWhenTheDuplicateIsNotTheOrderUniqueIndex() {
        givenEligible();
        when(refundMapper.selectOne(any())).thenReturn(null);
        when(refundMapper.selectByOrderIdForUpdate(ORDER_ID)).thenReturn(null);
        when(refundMapper.insert(any(Refund.class)))
                .thenThrow(new DuplicateKeyException("PRIMARY"));

        assertThrows(DuplicateKeyException.class, () -> service.execute(ORDER_ID, "并发重试"));
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
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -am -Dtest=RefundExecutionServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 编译失败，`找不到符号: 类 RefundExecutionServiceImpl`

- [ ] **Step 3: 实现**

先给 `RefundMapper` 补一个锁定读方法（它目前是空的裸 `BaseMapper`）。写法与既有的
`OrderMapper.selectByIdForUpdate` 同构，注意 `FOR UPDATE` 在 `LIMIT 1` **之后**：

`RefundMapper.java`（**修改**）：

```java
    /**
     * 锁定读该订单的退款行。
     *
     * <p>并发重试的兜底专用：进入 catch 时本事务的 read view **早于赢家提交**（见 Task 5 修订说明），
     * 普通 SELECT 复用旧 view 必然读不到赢家，只有锁定读才读最新已提交版本。</p>
     */
    @Select("SELECT * FROM refund WHERE order_id = #{orderId} LIMIT 1 FOR UPDATE")
    Refund selectByOrderIdForUpdate(@Param("orderId") Long orderId);
```

`RefundExecutionService.java`：

```java
package com.mall.module.order.service;

import com.mall.module.order.entity.vo.RefundEligibilityVO;

public interface RefundExecutionService {

    /**
     * 执行退款。幂等：重复调用不报错、不重复退款。
     *
     * <p>返回值是 {@link RefundEligibilityVO}，但它描述的是<b>本次调用后的结论</b>，
     * 不是「当前是否可退」的查询结论。两种形态：</p>
     *
     * <ul>
     *   <li><b>本次执行了退款</b>：{@code eligible=true}、{@code reason=null}，
     *       {@code refundableAmount} 为<b>本次退款金额</b>，订单已推进到 {@code REFUNDED}；
     *       {@code refundExists=false} 说的是<b>本次调用之前</b>没有既有退款记录——它是写前的事实，
     *       不代表此刻的状态。</li>
     *   <li><b>此前已有退款记录，本次未重复执行</b>：{@code eligible=false}、
     *       {@code refundExists=true}，{@code refundableAmount} 为<b>该既有记录的金额</b>。
     *       调用方据此判断「此前已有记录」，<b>不要当成失败</b>。
     *       <b>注意是「已有记录」而不是「已经退过了」</b>——记录可能仍在处理中、钱未必已退，
     *       具体看下一段的 {@code reason} 文案。</li>
     * </ul>
     *
     * <p>第二种形态的 {@code reason} 文案按<b>既有行的状态</b>区分，两者不可混为一谈：
     * 行已是 {@code REFUNDED} 则是「该订单已完成退款」（钱已退、订单已推进）；
     * 行仍是 {@code PENDING}（旧端点 {@code POST /api/orders/{id}/refund} 落的）则是
     * 「该订单已有退款申请在处理中」——<b>钱没退、订单状态也没推进</b>。</p>
     *
     * <p>{@link RefundEligibilityVO} 的字段注释是按<b>查询</b>语义写的；在本方法的返回值里，
     * 请以本方法陈述的两种形态为准。</p>
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
import org.springframework.dao.DuplicateKeyException;
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
        // 先判资格：不符合就直接拒绝，不进入写路径。
        //
        // ⚠️「已有退款记录」**不等于**「不可退」——那是**重试**，必须落到下面的幂等分支
        // 返回既有结果，而不是报错。计划要求「重复调用返回同一结果而不是报错」，
        // 若这里只判 isEligible()，顺序重试会在这一行被 50002 挡掉，幂等分支永远不可达。
        RefundEligibilityVO eligibility = eligibilityService.check(orderId);
        if (!eligibility.isEligible() && !eligibility.isRefundExists()) {
            throw new BusinessException(ResultStatus.ORDER_NOT_REFUNDABLE);
        }

        // 锁订单行，防并发重复执行。
        //
        // ⚠️ 不要指望「锁后再查一次」能看见并发赢家刚提交的退款行：本方法是 @Transactional，
        // 而上面的 check() 里的 selectById 已经建立了本事务的 read view；InnoDB 在
        // REPEATABLE READ 下不会刷新它。selectByIdForUpdate **只刷新被锁的那一行，不刷新快照**。
        // 所以下面的复查可能读到 null，随后撞上 uk_refund_order。
        // **真正的并发裁判是唯一索引**，不是这次复查——见下面的 catch。
        Order order = orderMapper.selectByIdForUpdate(orderId);
        if (order == null) {
            throw new BusinessException(ResultStatus.ORDER_NOT_EXIST);
        }

        Refund existing = refundMapper.selectOne(
                new LambdaQueryWrapper<Refund>().eq(Refund::getOrderId, orderId));
        if (existing != null) {
            // 幂等分支：Agent 重试会走到这里。不报错、不重复退款，返回既有结果。
            return idempotentResult(orderId, existing);
        }

        try {
            refundMapper.insert(new Refund()
                    .setId(SnowflakeIdUtil.nextId())
                    .setOrderId(orderId)
                    .setUserId(order.getUserId())
                    // 金额一律取服务端算出的值，绝不用入参
                    .setAmount(eligibility.getRefundableAmount())
                    .setReason(reason)
                    .setStatus(REFUNDED)
                    .setCreatedAt(LocalDateTime.now()));
        } catch (DuplicateKeyException e) {
            // 并发重试的兜底：另一个事务抢先插入了同一订单的退款行，唯一索引已经
            // 替我们挡住了重复退款，这里只需把它翻译成业务语义。
            // 与 OrderServiceImpl.requestRefund 的写法同源（Task 3 已批准落地）。
            //
            // ⚠️ 必须用**锁定读**，不能再用普通 SELECT（2026-09-19 修订，见 Task 5 修订说明）。
            // 这是上面那段注释的直接推论：本方法的 read view 在 check() 时就已定型，而进入本分支
            // **意味着**赢家的提交发生在那之后——两者互补。于是本事务里任何普通 SELECT 都
            // **必定**读不到那行，winner 恒为 null，分支只会走 throw e，把并发重试漏成
            // -1 系统异常——正是这条分支存在所要避免的结果。
            // 锁定读读的是最新已提交版本，不受本事务快照约束。
            Refund winner = refundMapper.selectByOrderIdForUpdate(orderId);
            if (winner == null) {
                // 能撞上 uk_refund_order 就说明那行存在；走到这里说明挡住 insert 的
                // 不是这条唯一索引，别把异常吞掉。
                throw e;
            }
            // 注意：这里**不能**吞掉「订单状态没推进」这件事。但「谁推进」取决于赢家是谁：
            // 赢家若是本服务，订单状态已由它推进到 REFUNDED；赢家若是旧端点
            // （POST /api/orders/{id}/refund），它只落一行 PENDING，**本就不该**推进订单状态。
            // 两种情况都不需要本事务补写——本事务只是没有写入成功，正常返回即提交，
            // 而提交一个什么都没写的事务等于无操作。
            return idempotentResult(orderId, winner);
        }

        order.setStatus(REFUNDED);
        orderMapper.updateById(order);

        return eligibility;
    }

    /**
     * 幂等返回：把一条既有退款记录翻译成调用方能读懂的结论。
     *
     * <p><b>文案按状态区分是必须的，不是措辞讲究。</b>旧端点
     * {@code POST /api/orders/{id}/refund} 落的行是 {@code PENDING}——钱没退、订单状态
     * 也没推进。若对它也回「该订单已完成退款」，那就是一句<b>与事实相反</b>的话，
     * 而这句话会经 MCP 传到 agent，成为给用户的解释。</p>
     */
    private RefundEligibilityVO idempotentResult(Long orderId, Refund existing) {
        boolean completed = REFUNDED.equals(existing.getStatus());
        return new RefundEligibilityVO()
                .setOrderId(orderId)
                .setEligible(false)
                .setRefundExists(true)
                .setRefundableAmount(existing.getAmount())
                .setReason(completed ? "该订单已完成退款" : "该订单已有退款申请在处理中");
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -am -Dtest=RefundExecutionServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 9, Failures: 0, Errors: 0`

（用例数的演变：原计划 5 个 → Task 4 的质量审查发现计划给的幂等分支在真实组合下不可达，
补 3 个守住修订后的语义，成 8 个 → Task 5 的质量审查发现结构性断言只钉住「调用了锁定读」、
钉不住「用的是它的结果」，补 1 个守住 catch 的另一条出口，成 9 个。）

- [ ] **Step 5: 提交**

```bash
git add mall-server/src/main/java/com/mall/module/order/ mall-server/src/test/java/com/mall/module/order/
git commit -m "feat: add idempotent refund execution with server-side amount"
```

---

## Task 6: 暴露两个 HTTP 端点

**Files:**
- Modify: `mall-server/src/main/java/com/mall/module/order/controller/OrderController.java`
- Test: `mall-server/src/test/java/com/mall/module/order/controller/OrderControllerRefundTest.java`（新建）

> **本任务兼负契约文档的职责**（2026-09-19 补，关闭 K-25 与 K-28）。
>
> 这两个端点返回的 `RefundEligibilityVO` 有两个字段**语义重载**，而它经 MCP 传给 Agent，
> 是 Agent 判断「要不要重试 / 怎么向用户解释」的**唯一依据**。两处必须**一次说清**，
> 不能留到第三个地方再补：
>
> | 字段 | 含义一 | 含义二 |
> |---|---|---|
> | `refundableAmount` | `eligible=true` 时是**可退**金额 | `refundExists=true` 时它仍**有值**，但 `eligible=false`——**这个数字不代表现在还能退，更不代表已退**。两个端点的取值来源不同：资格查询取**订单实付金额**、执行接口幂等分支取**既有行的金额**，当前同值（只支持整单退款）。**既有行是 PENDING 时，这笔钱还没退**（K-25） |
> | `eligible` | 查询语义下是「当前是否可退」 | `execute` 的返回值里，`false` 意味着「此前已有退款记录、本次未重复执行」，**不是失败**（K-28）。既有记录是**已完成**还是**仍在处理中**，看 `reason` 文案——**别说成「已退过」，PENDING 时钱没退** |
>
> Step 3 给的端点 javadoc 已经把这两条写全了，**照抄即可，不要精简掉**。
> 交接时要说明：`RefundEligibilityVO` 自身的字段注释是按**查询**语义写的，
> 在 `execute` 的返回值里以端点 javadoc 为准。

- [ ] **Step 1: 写失败的测试**

```java
package com.mall.module.order.controller;

import com.mall.common.result.Result;
import com.mall.module.order.entity.dto.RefundReasonDTO;
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
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -am -Dtest=OrderControllerRefundTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 编译失败，`找不到符号: 方法 refundEligibility`

- [ ] **Step 3: 在 OrderController 中新增端点**

在 `OrderController` 中注入两个新服务并添加端点：

```java
    @Autowired
    RefundEligibilityService refundEligibilityService;

    @Autowired
    RefundExecutionService refundExecutionService;

    /**
     * 查询该订单的售后资格与可退金额。只读，无副作用。
     *
     * <p><b>{@code refundableAmount} 的语义是重载的，调用方必须结合 {@code eligible}
     * 与 {@code refundExists} 判读。它<b>只要订单存在就一定有值</b>（恒为订单实付金额），
     * 但含义随象限变化：</p>
     *
     * <ul>
     *   <li>{@code eligible=true}：它是<b>可退</b>金额，此刻确实可退；</li>
     *   <li>{@code refundExists=true}（已有退款记录，此时 {@code eligible=false}）：
     *       <b>这个数字不代表现在还能退</b>——它仍是订单实付金额（当前只支持整单退款，
     *       故与那条既有记录的金额相同）。<b>既有行还是 {@code PENDING} 时，这笔钱还没退</b>，
     *       别读成「已退」；</li>
     *   <li>两者皆为 {@code false}（订单状态不符合任何售后政策）：<b>这个数字更不是承诺</b>——
     *       字段仍填了订单实付金额，但该订单当前不可退。
     *       <b>别把这一支读成「超期」</b>：签收超过 7 天<b>不会</b>落到这里——
     *       {@code QUALITY_ISSUE} 对 {@code RECEIVED} 订单无期限兜底，
     *       天数只决定命中哪条政策，不决定有没有政策（见 {@code AfterSalesPolicy}）。</li>
     * </ul>
     *
     * <p>⚠️ <b>本端点区分不了既有记录是「已完成」还是「仍在处理中」</b>——
     * {@code refundExists=true} 时它只报告「有记录」，{@code reason} 也不带状态。
     * 要判断退款走到了哪一步，调 {@code POST /api/orders/{id}/refund/execute}
     * 看它返回的 {@code reason}。</p>
     */
    @GetMapping("/{id}/refund-eligibility")
    public Result<RefundEligibilityVO> refundEligibility(@PathVariable Long id) {
        Result<RefundEligibilityVO> result = Result.build();
        result.success(refundEligibilityService.check(id));
        return result;
    }

    /**
     * 执行退款。幂等——Agent 会重试，重复调用返回既有结论而非报错。
     * 金额由服务端决定，请求体只携带原因。
     *
     * <p><b>两种响应形态，调用方必须都能正确处理</b>（与
     * {@link RefundExecutionService#execute} 的契约一致）：</p>
     *
     * <ul>
     *   <li><b>本次执行了退款</b>：{@code eligible=true}、{@code reason=null}，
     *       {@code refundableAmount} 为本次退款金额，订单已推进到 {@code REFUNDED}；
     *       此时 {@code refundExists=false} 说的是<b>本次调用之前</b>没有既有退款记录——
     *       它是写前的事实，<b>不代表此刻</b>（这一刻刚写入了一条）；
     *       本形态还会带上 {@code policyCode} / {@code policyTitle}；形态二这两项为 {@code null}。</li>
     *   <li><b>此前已有退款记录、本次未重复执行</b>：{@code eligible=false}、
     *       {@code refundExists=true}，{@code refundableAmount} 为<b>该既有记录的金额</b>
     *       （取值来源与资格查询不同：那边取订单实付金额，当前两者同值）。<b>这不是失败</b>，
     *       不要读成「退款没成功」而重试或升级。</li>
     * </ul>
     *
     * <p>⚠️ <b>第二种形态必须再看 {@code reason} 才知道既有记录走到了哪一步，
     * 两者不可混为一谈：</b></p>
     *
     * <ul>
     *   <li>「该订单已完成退款」：既有行已是 {@code REFUNDED}，<b>钱已退、订单已推进</b>；</li>
     *   <li>「该订单已有退款申请在处理中」：既有行仍是 {@code PENDING}（旧端点
     *       {@code POST /api/orders/{id}/refund} 落的），<b>钱没退、订单状态也没推进</b>。
     *       这同样不是失败——它是「已有申请、尚未执行」。</li>
     * </ul>
     *
     * <p>⚠️ 最容易被误读的是第二种形态返回的 {@code eligible=false}：把它当失败，
     * 幂等路径就会在调用方那边被读成错误，本端点做幂等就白做了。</p>
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
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -am -Dtest=OrderControllerRefundTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: 跑全量测试确认没影响既有行为**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test
```

Expected: `BUILD SUCCESS`，**零失败**

> ⚠️ **不要照特定数字核对。** 计划原文此处写「既有 188 个测试全绿」，但 2026-09-19 实测该数字**对不上**——`mall-server` 当时的实际全量是 **166**（Task 2 加 2 个后为 168，Task 3 后为 171，Task 4 后仍为 171）。基线数字会随每个任务增长，**判据应当是「零失败」而不是「等于某个数」**，否则会为一个幽灵差异白花时间。
>
> 另注：跑全量时 `SeckillConsumerTest` 的负路径会打印 ERROR 日志，那是断言的一部分，不是失败。

- [ ] **Step 6: 提交**

```bash
git add mall-server/src/main/java/com/mall/module/order/ mall-server/src/test/java/com/mall/module/order/
git commit -m "feat: expose refund eligibility and execution endpoints"
```

> **注意 `src/test` 也要加**（2026-09-19 修订）：本步骤新建了测试类，只 `git add src/main` 会让
> 测试文件游离在提交之外——**测试全绿地留在工作区，而提交里没有它们**。

---

## Task 7: 暴露政策条款端点（供 Agent 侧建 RAG 索引）

**为什么单独一个端点**：spec §4.2 要求「判定规则与解释文本同源」。条款文本存在 `AfterSalesPolicy` 枚举里，Agent 侧需要把它拉过去索引——**这是同源得以成立的那条通路**，缺了它，RAG 的文本就只能另找来源，一致性就没了保障。

**Files:**
- Create: `mall-server/src/main/java/com/mall/module/order/entity/vo/PolicyClauseVO.java`
- Create: `mall-server/src/main/java/com/mall/module/order/entity/vo/PolicyCatalogVO.java`
- Create: `mall-server/src/main/java/com/mall/module/order/controller/AfterSalesPolicyController.java`
- Test: `mall-server/src/test/java/com/mall/module/order/controller/AfterSalesPolicyControllerTest.java`

> **修订说明（2026-09-19）：端点改为返回「条款 + 指纹」，关闭 K-13。**
>
> 初稿只返回条款数组。那样只做到了**空间维度**的同源（判定与文本出自同一个枚举），
> **时间维度**仍是破的：Agent 侧在启动时拉一次并索引（计划 B 原话：「无缓存……Plan C 会在启动时
> 拉一次即可」），此后 supermall 改了某条条款并重新部署，**没有任何机制告诉 Agent 它手里的文本已经过期**。
> 于是它可能引用一条**已不存在的条款**去解释一个**按新规则做出的决定**——
> 而这正是 §4.2 要防的那件事，只不过换了个维度。
>
> **用户 2026-09-19 选定方案一**：响应体由数组改为 `{fingerprint, clauses}`。
>
> - **指纹从枚举派生，不手写版本号**。本仓库没有任何版本追踪机制（K-2），手写的版本号
>   **自己就会漂移**——改了条款忘了改版本号，比没有还糟。派生出来的指纹改了条款就必然变。
> - **指纹由「本次实际返回的那批条款」算出**，不是另算一份。这样「指纹覆盖的内容」与
>   「响应里的内容」是**同一个列表**，不可能不同步——**结构性保证，不是约定**。这正是本项目的取舍准绳所在。
> - **指纹对顺序敏感**，而这是必须的：本项目的政策枚举**顺序即优先级**（K-16）。
>   调换顺序会改变判定结果，因此也必须改变指纹。
>
> **代价已核实**：计划 B 的 `listPolicyClauses()` 只是把响应体原样 `toString()` 透传给模型
> （计划 B 第 865 行），**没有任何测试断言其形状**——跨仓库契约实际上没有消费者。
> 代价只落在本任务自己的测试上。

- [ ] **Step 1: 创建两个 VO**

`PolicyClauseVO.java`：

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

`PolicyCatalogVO.java`——**指纹与条款绑在一起，构造时就派生**：

```java
package com.mall.module.order.entity.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 政策条款目录。供 Agent 侧建立检索索引并检测漂移。 */
@Data
@Accessors(chain = true)
public class PolicyCatalogVO {

    /**
     * 本批条款的指纹。Agent 侧重新拉取时比对，不一致即说明服务端的条款变了，重建索引。
     */
    private String fingerprint;

    private List<PolicyClauseVO> clauses;

    /**
     * 从一批条款构造目录。<b>指纹取自传入的这批条款本身</b>，而不是另算一份——
     * 于是「指纹覆盖的内容」与「响应返回的内容」是同一个列表，不可能不同步。
     *
     * <p>用工厂方法而不是裸 new + setter，是为了让这条不变量跟着数据走：
     * 想构造目录就得经过这里，绕不过指纹。</p>
     */
    public static PolicyCatalogVO of(List<PolicyClauseVO> clauses) {
        return new PolicyCatalogVO()
                .setFingerprint(fingerprintOf(clauses))
                .setClauses(clauses);
    }

    /**
     * 对条款求 SHA-256。
     *
     * <p>字段之间用 {@code \0} / {@code \1} 分隔，避免拼接歧义——
     * 否则 {@code ("ab","c")} 与 {@code ("a","bc")} 会得到同一个指纹。</p>
     *
     * <p><b>顺序敏感是必须的</b>：本项目的政策枚举顺序即优先级（见 K-16），
     * 调换顺序会改变判定结果，因此也必须改变指纹。</p>
     */
    private static String fingerprintOf(List<PolicyClauseVO> clauses) {
        StringBuilder canonical = new StringBuilder();
        for (PolicyClauseVO clause : clauses) {
            canonical.append(clause.getCode()).append('\0')
                    .append(clause.getTitle()).append('\0')
                    .append(clause.getClauseText()).append('\1');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现，走不到这里
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
```

- [ ] **Step 2: 写失败的测试**

```java
package com.mall.module.order.controller;

import com.mall.common.result.Result;
import com.mall.module.order.entity.vo.PolicyCatalogVO;
import com.mall.module.order.entity.vo.PolicyClauseVO;
import com.mall.module.order.enums.AfterSalesPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AfterSalesPolicyControllerTest {

    private final AfterSalesPolicyController controller = new AfterSalesPolicyController();

    @Test
    void shouldExposeEveryPolicyWithCodeAndClauseText() {
        Result<PolicyCatalogVO> result = controller.listPolicies();

        assertEquals(0, result.getCode());
        List<PolicyClauseVO> clauses = result.getData().getClauses();
        assertEquals(AfterSalesPolicy.values().length, clauses.size());

        for (PolicyClauseVO clause : clauses) {
            assertNotNull(clause.getCode());
            assertFalse(clause.getClauseText().isBlank(),
                    clause.getCode() + " 的条款文本为空，RAG 将无据可依");
        }
    }

    @Test
    void codeShouldMatchEnumNameSoDecisionAndExplanationShareAKey() {
        Result<PolicyCatalogVO> result = controller.listPolicies();

        // 资格接口返回的 policyCode 必须能在这里找到对应条款
        boolean found = result.getData().getClauses().stream()
                .anyMatch(c -> "SEVEN_DAY_NO_REASON".equals(c.getCode()));
        assertTrue(found, "资格接口给出的政策码在条款列表里找不到对应项");
    }

    @Test
    void shouldAlwaysCarryAFingerprint() {
        String fingerprint = controller.listPolicies().getData().getFingerprint();

        assertNotNull(fingerprint);
        assertFalse(fingerprint.isBlank(), "没有指纹，Agent 侧就无法发现条款漂移（K-13）");
    }

    /**
     * 指纹必须**稳定**：同一批条款每次都要算出同一个值。
     * 这条守的是「指纹不是时间戳/随机数/对象身份哈希」——那种实现会让 Agent 每次比对都判定「变了」，
     * 于是每次刷新都重建索引，指纹退化成一个恒真的告警。
     */
    @Test
    void fingerprintShouldBeStableAcrossCalls() {
        assertEquals(controller.listPolicies().getData().getFingerprint(),
                controller.listPolicies().getData().getFingerprint());
    }

    /**
     * 指纹必须**覆盖条款文本**。改了文本而指纹不变 = 漂移检测失效，这正是 K-13 要防的。
     * 直接喂两组只差一个字的条款，绕开枚举不可变的限制。
     */
    @Test
    void fingerprintShouldChangeWhenAnyClauseTextChanges() {
        PolicyClauseVO original = new PolicyClauseVO()
                .setCode("X").setTitle("标题").setClauseText("原文");
        PolicyClauseVO edited = new PolicyClauseVO()
                .setCode("X").setTitle("标题").setClauseText("改过的原文");

        assertNotEquals(PolicyCatalogVO.of(List.of(original)).getFingerprint(),
                PolicyCatalogVO.of(List.of(edited)).getFingerprint());
    }

    /**
     * 指纹必须**对顺序敏感**——因为本项目的政策枚举**顺序即优先级**（见 K-16）。
     * 调换两条政策的先后会改变判定结果，因此也必须改变指纹；否则一次「静默改了优先级」
     * 的服务端发布，Agent 侧会认为条款没变而继续用旧的顺序解释。
     */
    @Test
    void fingerprintShouldBeOrderSensitiveBecauseOrderIsPriority() {
        PolicyClauseVO first = new PolicyClauseVO()
                .setCode("A").setTitle("甲").setClauseText("甲条款");
        PolicyClauseVO second = new PolicyClauseVO()
                .setCode("B").setTitle("乙").setClauseText("乙条款");

        assertNotEquals(PolicyCatalogVO.of(List.of(first, second)).getFingerprint(),
                PolicyCatalogVO.of(List.of(second, first)).getFingerprint());
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -am -Dtest=AfterSalesPolicyControllerTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 编译失败，`找不到符号: 类 AfterSalesPolicyController`

- [ ] **Step 4: 实现控制器**

```java
package com.mall.module.order.controller;

import com.mall.common.result.Result;
import com.mall.module.order.entity.vo.PolicyCatalogVO;
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
 *
 * <p>响应里带一个由这批条款派生的指纹，Agent 侧重新拉取时比对即可发现漂移
 * （K-13：只做到空间维度的同源还不够，时间维度也要堵）。</p>
 */
@RestController
@RequestMapping("/api/after-sales")
public class AfterSalesPolicyController {

    @GetMapping("/policies")
    public Result<PolicyCatalogVO> listPolicies() {
        List<PolicyClauseVO> clauses = Arrays.stream(AfterSalesPolicy.values())
                .map(policy -> new PolicyClauseVO()
                        .setCode(policy.name())
                        .setTitle(policy.getTitle())
                        .setClauseText(policy.getClauseText()))
                .toList();

        Result<PolicyCatalogVO> result = Result.build();
        // 指纹由 PolicyCatalogVO.of 从**同一批** clauses 派生，不另算一份
        result.success(PolicyCatalogVO.of(clauses));
        return result;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
JAVA_HOME=/d/jdks/openjdk-22.0.2 "/d/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" test -pl mall-server -am -Dtest=AfterSalesPolicyControllerTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `Tests run: 6, Failures: 0, Errors: 0`

（初稿为 2 个用例；2026-09-19 的 K-13 修订加了 4 个守指纹的：非空、稳定、覆盖条款文本、对顺序敏感。
**「稳定」那一条不是凑数**——它守的是「指纹不是时间戳/对象身份哈希」，
那种实现会让 Agent 每次比对都判定「变了」而每轮都重建索引，指纹退化成恒真告警。）

- [ ] **Step 6: 提交**

```bash
git add mall-server/src/main/java/com/mall/module/order/ mall-server/src/test/java/com/mall/module/order/
git commit -m "feat: expose after-sales policy clauses for agent-side indexing"
```

> **注意 `src/test` 也要加**（2026-09-19 修订）：同 Task 6 的 Step 6，本步骤也新建了测试类。

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

- [ ] **Step 6: 验证幂等——并发执行（2026-09-19 增补）**

**为什么必须单独验这一步**：Step 5 验的是**顺序**重试——第二次调用时赢家早已提交，
上方那次普通复查就看得见它，走的是**正常幂等路径**。**并发**重试走的是**完全不同的分支**：
`catch (DuplicateKeyException)` 的兜底，而本事务的 read view 早于赢家提交，普通 SELECT 恒读不到，
**只有锁定读才救得回来**（见 Task 5 修订说明）。

单测守不住它：catch 里那次读被 Mockito stub 掉了，stub 什么就返回什么，行为断言永远绿。
**这个 bug 只有在真实 MySQL 上并发调用才会现形**——所以这一步是本修订唯一的证明手段。

拿一张新的 `RECEIVED` 订单（记为 `$ORDER_ID3`），**同时**发两个执行请求：

```bash
printf '%s' '{"reason":"并发重试验证"}' > /tmp/refund-conc.json

for i in 1 2; do
  curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    --data-binary @/tmp/refund-conc.json \
    "$BASE/api/orders/$ORDER_ID3/refund/execute" > /tmp/refund-conc-$i.json &
done
wait
cat /tmp/refund-conc-1.json; echo; cat /tmp/refund-conc-2.json
```

Expected:
- **两个响应都是 `code = 0`**。**任何一个出现 `-1` 就是本修订要修的那个 bug 复现了**——
  它意味着 catch 里的锁定读没生效（或日后又被改回了普通 SELECT）
- 落库仍只有一条：

```bash
mysql_q "SELECT COUNT(*) FROM mall.refund WHERE order_id=$ORDER_ID3;"
```

Expected: **1**

> **判据是「都不报错」，不是「两个响应一模一样」**：并发下谁先谁后不确定，两个响应里
> 哪一个带「已完成退款」也不确定（只有一个是赢家）。
> 若两个请求实际串行了（curl 启动有开销），可再跑一轮，或把并发数临时提到 4。

- [ ] **Step 7: 验证旧端点的重复提交返回业务错误**

这一步守的是 Task 3 那处唯一**既有行为改动**的声称：`POST /api/orders/{id}/refund` 第二次调用应返回 `REFUND_ALREADY_EXISTS(50003)`，而**不是** `-1 系统异常`。

**为什么必须在真实环境验**：单元测试 mock 掉了 `refundMapper.insert` 直接抛 `DuplicateKeyException`，它只能守住 **catch 分支**，**守不住异常翻译链**。若数据源/模板配置变化导致 MySQL 1062 不再被翻译成 `DuplicateKeyException`，那个 catch 会**静默变成死代码**——单测照样全绿，调用方又回到「系统异常」。声称必须可演示。

⚠️ **用一张新的 `PAID` 订单**（记为 `$ORDER_ID2`），**不要**用前面的 `$ORDER_ID`——它已进入 `REFUNDED`，第二次调用会在状态校验就被 `ORDER_STATUS_ERROR(50001)` 挡下，根本走不到这个分支。

```bash
printf '%s' '{"reason":"旧端点重复提交验证"}' > /tmp/refund-old.json

# 第一次：应成功落一条 PENDING
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary @/tmp/refund-old.json "$BASE/api/orders/$ORDER_ID2/refund"

# 第二次：应返回业务错误，不是 -1
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary @/tmp/refund-old.json "$BASE/api/orders/$ORDER_ID2/refund"
```

Expected:
- 第二次响应 `code = 50003`、`message = "该订单已有退款记录"`（**不是 `-1`**）
- 落库仍只有一条：

```bash
mysql_q "SELECT COUNT(*) FROM mall.refund WHERE order_id=$ORDER_ID2;"
```

Expected: **1**

- [ ] **Step 8: 验证越权被挡**

用**另一个用户**的 token 请求同一订单。

Expected: `ORDER_NOT_EXIST(50000)`

- [ ] **Step 9: 验证金额不可指定**

用 `{"reason":"...","amount":99999}` 调用（多余字段），确认落库金额仍是订单实付金额。

- [ ] **Step 9b: 验证非法 reason 不会被报成系统异常**（2026-09-19 补）

**为什么单独验**：`@Valid` 校验失败此前会落进 `GlobalExceptionHandler` 的**兜底**分支，返回 `-1 系统异常`——
而 `-1` 在 Agent 的判断里等于「售后系统故障」，会诱发重试与升级。这与 K-6 修 `DuplicateKeyException` 是同一论证。
Task 6 已在 `OrderController` 内加了局部 `@ExceptionHandler` 收口（**只覆盖售后端点**，仓库级缺口仍在，见 K-31）。

**必须用一张尚无退款记录的 `RECEIVED` 订单**（记为 `$ORDER_ID4`）。这一点是关键：
若订单已有 PENDING/REFUNDED 行，请求会走幂等分支，**即使校验完全没生效也不会写入**——
断言全绿却什么都没证明，正是本项目记过的「看起来全绿的失败」。

```bash
printf '%s' '{"reason":"   "}' > /tmp/refund-blank.json
curl -s -X POST -H "Authorization: Bearer $CT" -H 'Content-Type: application/json' \
  --data-binary @/tmp/refund-blank.json \
  "http://localhost:8081/api/orders/$ORDER_ID4/refund/execute"
```

Expected: `code: 10000`、`message: 参数错误`（**不是** `-1 系统异常`）；且

```bash
mysql_q "SELECT COUNT(*) FROM mall.refund WHERE order_id=$ORDER_ID4;"
```

Expected: **0**——校验挡住时不得落任何退款行，订单状态仍为 `RECEIVED`。

再用一条**超过 512 字**的 reason 重复一次，期望相同（`@Size(max=512)` 与 `refund.reason VARCHAR(512)` 逐字对齐）。

> 若拿到 `-1` 并看到 `unknown_failure` ERROR 日志，说明局部 handler 没生效——那是**失败**，不是预期现象。

- [ ] **Step 10: 提交验证记录**

在 `docs/` 下记录本轮验证结果（造了哪些数据、每步的返回、最终一致性核对），提交。

---

## 完成标准

- [ ] 全量测试通过，**零失败**（不要照某个具体数字核对，理由见 Task 6 Step 5）
- [ ] 新建的 5 个测试类全绿：`AfterSalesPolicyTest`(**9**)、`RefundEligibilityServiceImplTest`(5)、`RefundExecutionServiceImplTest`(**9**)、`OrderControllerRefundTest`(2)、`AfterSalesPolicyControllerTest`(**6**)

> 括号里的数字是 2026-09-19 的实测值，**与计划初稿不同**：`AfterSalesPolicyTest` 原为 7，Task 2 的重审补了 2 个（可达性守卫 + `resolve` 层的边界断言）；`RefundExecutionServiceImplTest` 原为 5，Task 4 的重审补了 3 个（见 Task 5 Step 1）。

- [ ] 真实环境下：查资格 → 执行 → 幂等（顺序） → **幂等（并发）** → **旧端点重复提交** → 越权 → 金额不可指定 → **非法 reason 不报系统异常**，**八项**全部符合预期
- [ ] `refund` 表有 `uk_refund_order` 唯一索引
- [ ] 订单可进入 `REFUNDED` 状态
- [ ] `GET /api/after-sales/policies` 返回的条款码与资格接口返回的 `policyCode` 能对上
- [ ] `GET /api/after-sales/policies` 的响应带 `fingerprint`，且同一份条款两次请求得到同一指纹（K-13）

---

## 已知简化（写进代码注释与验证记录）

1. **签收时间用 `order.created_at` 近似** —— 订单表没有签收时间字段。这会让"7 天窗口"的判定偏严（下单到签收通常有 1~3 天）。签收时间落地后应替换。
2. **只支持整单退款** —— 金额恒等于订单实付金额，不支持部分退款。
3. **退款即时完成** —— 没有对接真实支付网关，`REFUNDED` 是终态。

这三条都是**有意为之**，不影响 agent 侧的评测设计（评测判的是"动作是否正确"，不是"退款是否到账"）。

**2026-09-19 追加两条**（来自 Task 2 的代码质量审查）：

4. **被驳回的退款会永久阻塞该订单** —— 退款行永不删除，也没有逻辑删除列。所以一条最终为 `REJECTED` 的退款会让该订单**再也无法申请退款**。

   **当前不触发**：没有任何代码写 `REJECTED`，商家审批流（`/api/merchant/refunds/{id}/approve|reject`）在代码里不存在。但 `CLAUDE.md` 把退款生命周期描述为 `PENDING/APPROVED/REJECTED/COMPLETED`，说明审批流是**已规划**的，届时会触发。

   **要一并决定的**：Task 4 的 `refundExists` 对一条 `REJECTED` 行应当如何解释——按当前逻辑它会让订单永久不可退。

   ⚠️ **注意一个陷阱**：`application.yml` 配了 MyBatis-Plus **全局逻辑删除**（`logic-delete-field: deleted`）。**若有人给 `Refund` PO 加一个名为 `deleted` 的字段来「解决」本条，软删除的行依然占用 `order_id` 唯一索引**——看起来解决了、实际没解决、且没有报错提示原因。要用软删除必须先处理唯一索引。

5. **退款存在两条写路径** —— 既有端点 `POST /api/orders/{id}/refund`（只落一条 `PENDING`）与新增的 `POST /api/orders/{id}/refund/execute`（幂等、推进订单到 `REFUNDED`）并存。

   这是**纯增量**原则的结果：旧端点是既有能力，不动它。但要清楚其后果——**调用旧端点之后，新端点的资格查询会返回「已有退款记录」**。这是自洽的（一单一退，由唯一索引兜底），只是两条入口行为不同。

   **Agent 走的是新端点**（经 MCP 的 `submit_refund`）。旧端点保留是历史包袱，不是给 agent 用的。

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
