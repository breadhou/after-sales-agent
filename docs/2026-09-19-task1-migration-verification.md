# Task 1 迁移脚本验证记录

**日期**：2026-09-19
**范围**：计划 A / Task 1（refund 唯一索引）的迁移脚本
**被验证文件**：`supermall/mall-server/src/main/resources/db/migration/2026-09-18-refund-unique.sql`
**supermall 提交**：`23eadf7`（本次修复）· `1fc8a5d`（删除冗余索引）· `ab3ad5b`（加唯一索引）

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

**关键限制**：线上 `refund` 是空表，所以下面的验证证明的是**脚本行为正确**，
**不证明**真实数据下的表现。历史重复数据的存在与否，必须由使用方在执行前
自己跑第 4 节的检查查询确认。

---

## 2. 脚本设计：自判断的四状态

旧版是一条写死的合并 ALTER，在任何现存环境上都跑不起来（详见 `known-issues.md` K-1）。
新版用 `information_schema.statistics` 读出当前索引状态，动态拼出需要执行的子句，
再用 `PREPARE`/`EXECUTE` 执行（MySQL 不支持 `DROP INDEX IF EXISTS`，只能这样做）。

| 环境状态 | 期望行为 | 生成的子句 |
|---|---|---|
| 全新（无 uk、有 idx） | 加 uk，删 idx | `ADD UNIQUE KEY … , DROP KEY idx_order_id` |
| prod 形态（有 uk、有 idx） | 只删 idx | `DROP KEY idx_order_id` |
| 已迁移（有 uk、无 idx） | 无操作 | 空 → `DO 0` |
| 异常（无 uk、无 idx） | 加 uk | `ADD UNIQUE KEY …` |

---

## 3. 逐状态实测（原始输出）

测试方式：用临时库（`mall_st1` / `mall_st4` / `mall_dup`）构造各种状态。
脚本里有 `USE mall;`，因此测试副本**只替换了 `USE` 那一行的库名**，其余逐字未动：

```
=== 测试副本与正式文件的差异（应只有 USE 一行） ===
47c47
< USE mall;
---
> USE mall_st1;
```

### 状态 1：全新（无 uk、有 idx）→ 应加 uk、删 idx

前置（建表语句取自 `66f6785`，即迁移前形态）：
```
INDEX_NAME	NON_UNIQUE
idx_order_id	1
idx_user_id	1
PRIMARY	0
```

运行脚本（stderr 未吞）：
```
EXIT CODE: 0
```

结果：
```
INDEX_NAME	NON_UNIQUE
idx_user_id	1
PRIMARY	0
uk_refund_order	0
```
✅ `uk_refund_order` 出现（`non_unique=0`），`idx_order_id` 消失。

### 状态 3：已迁移（有 uk、无 idx）→ 应无操作、不报错

在上一步的库上再跑一次同一个脚本：
```
EXIT CODE: 0  (无任何报错)
```
```
INDEX_NAME	NON_UNIQUE
idx_user_id	1
PRIMARY	0
uk_refund_order	0
```
✅ 幂等，`DO 0` 空操作路径生效。旧版在此报 `ERROR 1091`。

### 状态 2：prod 形态（有 uk、有 idx）→ 应只删 idx

前置（在已有 uk 的库上手工加回 `idx_order_id`）：
```
INDEX_NAME	NON_UNIQUE
idx_order_id	1
idx_user_id	1
PRIMARY	0
uk_refund_order	0
```

运行脚本：
```
EXIT CODE: 0
```

结果：
```
INDEX_NAME	NON_UNIQUE
idx_user_id	1
PRIMARY	0
uk_refund_order	0
```
✅ `idx_order_id` 被删、`uk_refund_order` 保留。旧版在此报 `ERROR 1061` **且不执行 DROP**，
冗余索引会永久留存——这正是本次修复要解决的场景。

### 状态 4：异常（无 uk、无 idx）→ 应加 uk

前置：
```
INDEX_NAME	NON_UNIQUE
idx_user_id	1
PRIMARY	0
```

运行脚本：
```
EXIT CODE: 0
```
```
INDEX_NAME	NON_UNIQUE
idx_user_id	1
PRIMARY	0
uk_refund_order	0
```
✅ 只加 uk，不尝试 DROP。

### 补充：有重复数据时的行为（原子性）

构造「迁移前形态 + 重复 order_id」的库，先跑运行前检查查询：
```
step
--- 运行前检查查询结果 ---
order_id	c
888888	2
```

运行脚本（stderr 未吞，期望失败）：
```
ERROR 1062 (23000) at line 68: Duplicate entry '888888' for key 'refund.uk_refund_order'
EXIT CODE: 1
```

失败后表的状态（**关键**）：
```
INDEX_NAME	NON_UNIQUE
idx_order_id	1
idx_user_id	1
PRIMARY	0
```
```
rows_now
2
```
✅ 整条 ALTER 原子回滚：**`idx_order_id` 仍在、未创建 uk、数据完好**。
表没有落入「两个索引都没了」的中间状态。这是刻意的设计取舍——宁可原索引留着，
也不要迁移失败时把表改坏。

---

## 4. 线上库最终状态

`USE mall;` 的实际效果（用**字面文件**、不带 `-D` 执行，验证不再报 `ERROR 1046`）：
```
EXIT CODE: 0
```

线上 `mall.refund` 索引：
```
INDEX_NAME	NON_UNIQUE
idx_user_id	1
PRIMARY	0
uk_refund_order	0
```
行数：
```
refund_rows
0
```
最终 DDL：
```
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

其他表的 `idx_order_id` 未被波及：
```
TABLE_NAME	INDEX_NAME
order_item	idx_order_id
seckill_order	idx_order_id
```

✅ 与本次修复前一致，修复过程未改变线上最终状态。

---

## 5. 数据清理确认

- 临时库 `mall_st1` / `mall_st4` / `mall_dup` 已全部 DROP，确认无残留：
  ```
  SELECT schema_name FROM information_schema.schemata
   WHERE schema_name LIKE 'mall\_%' AND schema_name <> 'mall';
  （空）
  ```
- 线上重复插入测试数据已删除，`mall.refund` 行数为 0。
- 测试用 `USE` 替换副本已从临时目录删除。

---

## 6. 现有环境部署 runbook

对新环境执行本迁移时：

1. **先查重复**：
   ```sql
   SELECT order_id, COUNT(*) c FROM refund GROUP BY order_id HAVING c > 1;
   ```
   若查出重复行，**先由人工决定哪一条为准**（比金额？比状态？比时间？），
   清理或合并之后再迁移。不要用自动规则猜。

2. **执行**（无需 `-D`，脚本内已有 `USE mall;`）：
   ```bash
   mysql -uroot -p --default-character-set=utf8mb4 < 2026-09-18-refund-unique.sql
   ```

3. **确认结果**：
   ```sql
   SELECT index_name, non_unique FROM information_schema.statistics
    WHERE table_schema='mall' AND table_name='refund';
   ```
   期望 `uk_refund_order`（`non_unique=0`）存在、`idx_order_id` 不存在。

4. 脚本可重复执行，安全重跑。
