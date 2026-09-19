# Task 1 迁移脚本验证记录

**日期**：2026-09-19
**范围**：计划 A / Task 1（refund 唯一索引）的迁移脚本
**被验证文件**：`supermall/mall-server/src/main/resources/db/migration/2026-09-18-refund-unique.sql`

**supermall 修订历史**（本文所有原始输出均来自 **`0d61fa3`**，即当前提交版）：

| 提交 | 内容 |
|---|---|
| `ab3ad5b` | 加唯一索引 |
| `1fc8a5d` | 删除冗余索引 idx_order_id |
| `23eadf7` | 自判断重写（information_schema + PREPARE） |
| `afcfad9` | uk 撞名按类型判断 |
| **`0d61fa3`** | **uk 按精确形态判定（覆盖复合索引）；删除脚本内 USE；补运行后核对** |

> 行号说明：§3 的报错行号（`at line 118/119`）对应 **`0d61fa3`** 版本。
> 早期修订的报错行号不同（如 `afcfad9` 是 line 85），不要跨版本对照行号。

**为什么单独记这份**：这个仓库没有 flyway/liquibase，也没有迁移版本追踪表，
`init.sql` 也不随应用启动执行。**迁移是否已应用无法从仓库事后还原**，所以
把验证证据固定下来，供后续环境部署和回溯使用。

---

## 1. 验证环境

| 项 | 值 |
|---|---|
| MySQL | 8.0（Windows，`/d/MySQL/MySQL Server 8.0/bin/mysql`） |
| 线上库 | `mall`（此前已是「有 uk、无 idx」状态） |
| 分支 | `feat/after-sales-capability` |
| 验证时 `mall.refund` 行数 | 0（空表） |
| 验证方式 | 临时库（`mall_v1` … `mall_v12`），用 `-D <临时库>` 指定目标，**无需改动脚本文件** |

**关键限制**：线上 `refund` 是空表，所以下面的输出证明的是**脚本行为正确**，
**不证明**真实数据下的表现。历史重复数据的存在与否，必须由使用方在执行前
按 §6 第 1 步自行确认。

---

## 2. 脚本设计

### 2.1 uk 可用性的判据

脚本用 `information_schema.statistics` 读出当前索引状态，动态拼出需要执行的
子句再 `PREPARE`/`EXECUTE`（MySQL 不支持 `DROP INDEX IF EXISTS`）。

核心判据是 **`@uk_ok`：「存在一个名叫 `uk_refund_order` 的索引，且它恰好是
`(order_id)` 上的单列唯一索引」**。实现为两个量：

- `@uk_rows` — 该索引名下的 statistics 行数。**每个索引列一行**，所以 0=不存在、
  1=单列、≥2=复合索引。
- `@uk_head` — 该索引名下 `non_unique=0 AND seq_in_index=1 AND column_name='order_id'`
  的行数（它是否以 order_id 作为唯一索引的第一列）。

`@uk_ok := (@uk_rows = 1 AND @uk_head = 1)`。

### 2.2 执行矩阵

| 环境状态 | uk 可用? | 生成的子句 |
|---|---|---|
| 索引不存在 | 否 | `ADD UNIQUE KEY …` |
| 单列唯一 `(order_id)` | **是** | 空 → `DO 0`（无操作） |
| 单列唯一但列不对（如 `(user_id)`） | 否 | `DROP KEY … , ADD UNIQUE KEY …` |
| 单列非唯一（撞名） | 否 | `DROP KEY … , ADD UNIQUE KEY …` |
| 复合唯一 `(order_id, status)` | 否 | `DROP KEY … , ADD UNIQUE KEY …` |
| 复合非唯一 `(order_id, status)` | 否 | `DROP KEY … , ADD UNIQUE KEY …` |

三种「名字在但形态不对」的情况由**同一条重建分支**覆盖。

### 2.3 为什么判据必须这么细

按索引名判断、或只判 `non_unique`、或用 `= 1` 比对一个按列展开的行数，都会
产生**静默或误导**的失败。以下三个坑都在实测中复现过：

- **只按索引名**：撞名的**非唯一**索引被当成「已就位」→ 跳过 ADD，**却仍删掉
  `idx_order_id`** → 退出码 0、无报错，而 `order_id` 上没有任何唯一约束，
  幂等保证落空，还比迁移前更糟（原索引也没了）。
- **只判 `non_unique`**：**复合**唯一索引 `(order_id, status)` 同样满足「非 0 即
  存在」→ 什么都不做。但 `order_id` 单独并不唯一——实测此时插两条同 `order_id`
  的行**都被接受**，一单多退毫无阻拦（见 §3 状态 11）。
- **用 `= 1` 比对行数**：复合索引返回 2 行，等值比较全部落空，得到静默的 `DO 0`
  或令人困惑的 `ERROR 1061`。

---

## 3. 逐状态实测（原始输出）

**全部来自 `0d61fa3`。** 测试方式：临时库 + `-D <临时库>`，脚本文件逐字未改
（这正是删除脚本内 `USE` 之后的直接好处）。

```
############ 被验证版本: supermall @ 0d61fa3 ############
```

### 状态 1：索引不存在 → 应只 ADD
```
before: idx_user_id(1),PRIMARY(0)
EXIT  : 0
after : idx_user_id(1),PRIMARY(0),uk_refund_order(0)
```

### 状态 2：单列唯一 (order_id)（正常态）→ 应无操作
```
before: idx_user_id(1),PRIMARY(0),uk_refund_order(0)
EXIT  : 0
after : idx_user_id(1),PRIMARY(0),uk_refund_order(0)
```

### 状态 3：单列非唯一（撞名）→ 应重建
```
before: idx_user_id(1),PRIMARY(0),uk_refund_order(1)
EXIT  : 0
after : idx_user_id(1),PRIMARY(0),uk_refund_order(0)
```

### 状态 4：复合唯一 (order_id, status) → 应重建
```
before: idx_user_id(1),PRIMARY(0),uk_refund_order(0),uk_refund_order(0)
EXIT  : 0
after : idx_user_id(1),PRIMARY(0),uk_refund_order(0)
```
（before 里同一索引名出现两行，正是「按列展开」的体现。）

重建后的列构成：
```
INDEX_NAME	SEQ_IN_INDEX	COLUMN_NAME	NON_UNIQUE
uk_refund_order	1	order_id	0
```
✅ 复合索引已被替换为 `order_id` 上的**单列**唯一索引。

### 状态 5：复合非唯一 (order_id, status) → 应重建
```
before: idx_user_id(1),PRIMARY(0),uk_refund_order(1),uk_refund_order(1)
EXIT  : 0
after : idx_user_id(1),PRIMARY(0),uk_refund_order(0)
```
✅ 不再出现旧版的 `ERROR 1061` 死胡同。

### 状态 6：单列唯一但列不对 (user_id) → 应重建
```
重建前:
INDEX_NAME	SEQ_IN_INDEX	COLUMN_NAME	NON_UNIQUE
uk_refund_order	1	user_id	0
   EXIT: 0
重建后:
INDEX_NAME	SEQ_IN_INDEX	COLUMN_NAME	NON_UNIQUE
uk_refund_order	1	order_id	0
```
✅ 索引从错误的列搬到了 `order_id`。

### 状态 7：prod 形态（uk 可用 + idx）→ 应只删 idx
```
before: idx_order_id(1),idx_user_id(1),PRIMARY(0),uk_refund_order(0)
EXIT  : 0
after : idx_user_id(1),PRIMARY(0),uk_refund_order(0)
```
✅ 冗余索引被删、uk 保留。旧版在此报 `ERROR 1061` 且不执行 DROP。

### 状态 8：全新（无 uk、有 idx）→ 应加 uk、删 idx
```
before: idx_order_id(1),idx_user_id(1),PRIMARY(0)
EXIT  : 0
after : idx_user_id(1),PRIMARY(0),uk_refund_order(0)
```

### 状态 9：幂等复跑（状态 8 迁移后再跑一次）→ 应无操作
```
before: idx_user_id(1),PRIMARY(0),uk_refund_order(0)
EXIT  : 0
after : idx_user_id(1),PRIMARY(0),uk_refund_order(0)
```

### 状态 10：有重复数据 → 应 1062 且原子回滚
```
before: idx_order_id(1),idx_user_id(1),PRIMARY(0)  汇总行: 2
ERROR 1062 (23000) at line 119: Duplicate entry '888' for key 'refund.uk_refund_order'
EXIT  : 1
after : idx_order_id(1),idx_user_id(1),PRIMARY(0)  汇总行: 2
```
✅ `DROP` 未执行、数据完好，表没有落入「两个索引都没了」的中间状态。

### 状态 11：复合唯一索引挡不住一单多退（关键回归）

迁移**前**，`UNIQUE KEY uk_refund_order (order_id, status)` 下插入两条同 `order_id`、
不同 `status` 的行：
```
EXIT  : 0   (0 = 两条都被接受，即复合索引挡不住重复退款)
同 order_id 行数: 2
```
清掉重复数据后跑迁移：
```
EXIT  : 0
after : PRIMARY(0),uk_refund_order(0)
```
迁移后同一 `order_id` 再退一次：
```
ERROR 1062 (23000) at line 1: Duplicate entry '555' for key 'refund.uk_refund_order'
迁移后再插同 order_id EXIT: 1   (1 = 被挡，一单一退生效)
```
✅ 复现了「复合唯一 ≠ 一单一退」，并证明本迁移能识别并修正它。

### 状态 12：`-D` 下 `DATABASE()` 的解析
```
-- 带 -D 时 DATABASE() 的值 --
db_seen_by_DATABASE
mall_v12
-- 用 -D mall_v12 跑迁移 --
   EXIT: 0
idx
PRIMARY(0),uk_refund_order(0)
```
✅ `DATABASE()` 返回的正是 `-D` 指定的库，`information_schema` 查询因此命中正确目标。

### 状态 13：忘加 `-D`
```
ERROR 1046 (3D000) at line 118: No database selected
EXIT  : 1
```
✅ 响亮且无害——**不会**静默打到生产库。（这正是删掉脚本内 `USE mall;` 的收益：
若脚本里写死 `USE`，忘改库名时会直接迁移生产库。）

---

## 4. 线上库最终状态

```
indexes: idx_user_id(1),PRIMARY(0),uk_refund_order(0)
行数   : 0
  TABLE_NAME	INDEX_NAME
  order_item	idx_order_id
  seckill_order	idx_order_id
```

最终 DDL：
```sql
CREATE TABLE `refund` (
  `id` bigint NOT NULL COMMENT 'PK',
  `order_id` bigint NOT NULL COMMENT 'FK → order.id',
  `user_id` bigint NOT NULL COMMENT 'FK → user.id',
  `reason` varchar(512) NOT NULL COMMENT '退款原因',
  `amount` decimal(10,2) NOT NULL COMMENT '退款金额',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/COMPLETED',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_order` (`order_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退款表'
```

✅ 与修复前一致，历次修复未改变线上最终状态。其他表的 `idx_order_id` 未受波及。

---

## 5. 数据清理确认

- 全部临时库已 DROP，无残留：
  ```
  剩余非系统库:
  SCHEMA_NAME
  miaosha
  mall
  ```
  （`miaosha` 是本机原有的库，不属于本次工作，未触碰。）
- 线上重复插入的测试数据已删除，`mall.refund` 行数为 0。

---

## 6. 现有环境部署 runbook

1. **先查重复**：
   ```sql
   SELECT order_id, COUNT(*) c FROM refund GROUP BY order_id HAVING c > 1;
   ```
   若查出重复行，**先由人工决定哪一条为准**（比金额？比状态？比时间？），
   清理或合并之后再迁移。不要用自动规则猜。
   （若查不出来，也可能是因为存在复合索引 `(order_id, status)` —— 它挡不住
   一单多退，见 §3 状态 11。可另行确认索引形态。）

2. **执行**（`-D` 指定目标库，脚本内没有 `USE`）：
   ```bash
   mysql -uroot -p --default-character-set=utf8mb4 -D mall < 2026-09-18-refund-unique.sql
   ```

3. **运行后核对**（脚本成功时是**静默**的，不核对无法区分「跑了但没做事」和
   「根本没跑」）：
   ```sql
   SELECT index_name, non_unique FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'refund';
   ```
   期望恰好三行：`PRIMARY`(0)、`uk_refund_order`(0)、`idx_user_id`(1)，
   且 **`idx_order_id` 不应出现**。

4. 脚本可重复执行，安全重跑。

---

## 7. 仍有待处理的手工步骤

- **线上库需手工清理重复数据后再跑迁移**时才会遇到 1062；当前线上表为空，
  不会触发。这条留给未来有真实数据的库。
- 任务 3 范围内的**退款幂等写入**尚未实现；本迁移只保证数据库层面的约束。
  索引缺失时该约束会静默失效，`supermall` 的索引检查清单已补上 `refund`
  （见该仓库 `CLAUDE.md` / `AGENTS.md`）。
