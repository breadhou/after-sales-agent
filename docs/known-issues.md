# 已知隐患

执行计划 A（supermall 售后能力）过程中发现、**当前不影响交付、但需要在后期判断是否处理**的问题。

**本文件持续更新**——每完成一个任务、每轮审查后，新发现的问题追加进来。已解决的问题移到「已处理」区，保留记录供追溯。

记录原则：只记**当下有意不处理**且**未来可能反悔**的项。已经决定并落地的设计（如三道防线、工具面收窄）不在此列。

**状态取值**：`待判断`（还没决定要不要处理）· `修复中`（已派工修复）· `待修`（已决定要处理，未动手）· `已处理`（保留记录供追溯）

---

## K-1 迁移脚本在任何现存环境上都跑不起来

| | |
|---|---|
| **状态** | **已处理**（2026-09-19，三轮审查验证通过） |
| **发现于** | 2026-09-19，Task 1 的代码质量审查（初版由 spec 审查标为「不可重复执行」，质量审查实测后升级） |
| **位置** | `supermall/mall-server/src/main/resources/db/migration/2026-09-18-refund-unique.sql` |
| **严重性** | 高——这是 Task 1 交付物本身的缺陷，不是潜在风险 |

**现状**：迁移脚本是一条合并的 ALTER（`ADD UNIQUE KEY` + `DROP KEY idx_order_id`）。**实测三种环境状态，两种失败**：

| 环境状态 | 结果 |
|---|---|
| 已迁移的 dev（有 uk、无 idx） | `ERROR 1091 Can't DROP 'idx_order_id'` |
| **prod 形态**（uk 与 idx 都在） | `ERROR 1061 Duplicate key name 'uk_refund_order'`，**且 DROP 子句根本不执行**——idx 存活 |
| 全新建库 | ✅ 唯一能跑通的场景 |

**为什么严重**：本仓库没有 flyway/liquibase，`init.sql` 也不自动执行（`application.yml` 无 `spring.sql.init`），所以**这个文件就是操作流程的唯一持久载体**。prod 上的人会撞 1061，合理地判断「已迁移，无需处理」——而这是错的，用户明确要求删除的冗余索引会永久留存。

**原验证的盲区**：implementer 曾用临时库验证「迁移保真度」，但那个库是从 `66f6785` 的建表语句建的——**恰好是唯一能跑的「全新建库」场景**。那次验证证明的是「DDL 语法正确」，不是「文件能应用」。

**修复方案**（已派工）：改为 `information_schema` 自判断写法，使四种状态都正确——全新（加 uk 删 idx）、prod 形态（只删 idx）、已迁移（无操作）、异常（只加 uk）。注意 MySQL 不支持 `DROP INDEX IF EXISTS`，需用变量 + `PREPARE`/`EXECUTE` 动态执行。

**已修复（2026-09-19，提交 `23eadf7`、`afcfad9`、`0d61fa3`）。** 中间还堵掉了两轮审查发现的同类残留静默失败：按索引类型（`non_unique`）判定、以及按**索引精确形状**判定（`@uk_ok` = 恰好单列且该列是 `order_id`）。第三轮审查独立验证了 10 种状态，并确认复合唯一、错列、DESC、INVISIBLE、**函数索引**（MySQL 8 边界：`column_name` 为 NULL）均无误判。

### K-1 残留语义（后期留意，非缺陷）

1. **`@uk_ok` 的判据是「恰好单列」**。所以将来若**有意**把 `uk_refund_order` 改成复合索引（如 `(order_id, status)` 求更精细的唯一性），**本迁移脚本会把它重建回单列**。当前 spec 要求「一单一退」，这是正确行为；但设计变更时脚本会跟设计打架——届时须同步更新该脚本与索引清单。
2. **`-D` 的防护是「报错」而非「不可能出错」**：`-D mall_wrong_db` 仍会作用到那个库。比脚本内写死 `USE mall;` 好（目标库出现在命令历史与 CI 日志里，可审计），但根本上仍依赖操作者传对库名。

---

## K-2 无迁移版本追踪

| | |
|---|---|
| **状态** | 待判断 |
| **发现于** | 2026-09-19，Task 1 的 implementer 与 spec 审查各自独立提出 |
| **位置** | `supermall/mall-server/src/main/resources/db/` |
| **处理时机** | 计划 B/C 若需新增迁移脚本，或需要第二套环境时 |

**现状**：supermall **没有 flyway / liquibase**。`db/migration/` 下是**手工执行**的约定，没有版本追踪表，也没有任何机制记录「哪个迁移已在哪个环境应用过」。`init.sql` 是手动 bootstrap 脚本，与线上库的一致性靠人维护。

**影响**：
- 「迁移是否已应用」无法自动判断，跨环境部署要人工确认（K-1 正是这个缺失的直接后果）
- `init.sql`（新环境）与 `migration/*.sql`（既有环境）是两条路径，容易漂移
- 计划 A 只有一个迁移，问题不大；**但 B/C 阶段若继续加迁移，代价会累积**

**可能的处理方向**：引入 flyway（Spring Boot 3.4 原生支持）· 或维持手工约定但补一份迁移清单文档 · 或按 K-7 扩展仓库既有的索引检查清单（最便宜的缓解）

---

## K-3 refund.status 的注释与实际写入值不符

| | |
|---|---|
| **状态** | 待修（计划 A 的 Task 3 内顺手处理） |
| **发现于** | 2026-09-19，执行前的计划审查 |
| **位置** | `supermall/.../db/init.sql`（refund 建表，status 字段） |
| **处理时机** | 计划 A 的 Task 3 |

**现状**：`refund.status` 的建表注释是 `PENDING/APPROVED/REJECTED/COMPLETED`，但计划 A 的 `RefundExecutionServiceImpl` 写入的是 **`REFUNDED`**。计划改了 `order.status` 的注释，却没同步改 `refund.status` 的。

**处理**：Task 3 里把注释改为实际取值范围（当前实现只用 `PENDING` 与 `REFUNDED`）。

---

## K-4 退款存在两条写路径

| | |
|---|---|
| **状态** | 待判断 |
| **发现于** | 2026-09-19，执行前的计划审查；代码质量审查补充了具体后果（见 K-6） |
| **位置** | `OrderController.java:62`（`POST /{id}/refund`）与计划 A 新增的 `POST /{id}/refund/execute` |
| **处理时机** | 计划 A 完成后、接 MCP 之前 |

**现状**：既有端点走 `orderService.requestRefund`，插入一条 `PENDING`；新端点走全新的幂等执行路径。计划声明「不改动任何现有接口的行为，全部是新增」，所以两条并存。

**影响**：调用旧端点后，新端点的资格查询会返回「已有退款记录」。自洽（一单一退，由唯一索引兜底），但用户有两条入口、行为不同（旧的只落 PENDING 不推进订单状态，新的推进到 `REFUNDED`）。

**注**：`RefundDTO` 本来就只有 `reason`、没有 `amount`，**不存在**「旧端点可指定金额」的安全漏洞。

---

## K-5 计划 A 的 Task 4 测试会 NPE

| | |
|---|---|
| **状态** | 待修（计划 A 的 Task 4 内处理） |
| **发现于** | 2026-09-19，执行前的计划审查 |
| **位置** | 计划 A 的 `RefundEligibilityServiceImplTest` |
| **处理时机** | 计划 A 的 Task 4 |

**现状**：`RefundEligibilityServiceImpl.check()` 第一行是 `Long userId = UserContext.getUserId();`，随后 `!userId.equals(order.getUserId())`。但 `UserContext` 是纯 ThreadLocal，单测里没有 JWT 过滤器跑过，`getUserId()` 返回 **null** → NPE。计划给的测试既没 mock `UserContext`，`order()` 辅助方法也没设 `userId`。

**处理**：按项目既有范式修——supermall 有 8 个测试类用 `MockedStatic<UserContext>`（`OrderServiceImplTest` 是标准样例），在 `@BeforeEach` 里 stub、`@AfterEach` 里 `close()`。**属于对齐既有约定，不是改设计。**

---

## K-6 旧退款端点被拒时会返回「系统异常」

| | |
|---|---|
| **状态** | **待修**（用户 2026-09-19 决定处理）——折进 Task 3 |
| **发现于** | 2026-09-19，Task 1 的代码质量审查 |
| **位置** | `OrderServiceImpl.java:264-288`（插入点在 286）；`GlobalExceptionHandler.java:39-43` |
| **处理时机** | 建议折进 Task 3（`REFUND_ALREADY_EXISTS(50003)` 正好在那里引入） |

**现状**：`requestRefund` 没有重复校验。唯一索引落地后，**第二次** `POST /api/orders/{id}/refund` 会抛 `DuplicateKeyException` → 通用处理器 → `Result.fail(ResultStatus.EXCEPTION)`（`-1 系统异常`）+ 一条 `unknown_failure` ERROR 日志。

**影响**：
- 失败方向是安全的（fail-closed，不会写入重复退款）
- 但**驱动这个端点的 agent 看到的是「系统异常」，与真实故障无法区分**，会诱发重试与升级
- 计划第 34 行「不改动任何现有接口的行为」这条声称**已经不再成立**——这是计划层面的缺陷，不是 implementer 的错
- `OrderServiceImplTest` 的申请退款用例（约 372-408 行）**仍会通过**，因为 mapper 是 mock 的——所以这个行为变化**对 CI 不可见**

**修复方向**：在 `requestRefund` 里捕获 `DuplicateKeyException`（或前置查询），返回既有退款记录或业务错误 `REFUND_ALREADY_EXISTS(50003)`。

**决定（2026-09-19）**：修。折进 **Task 3**——因为 `REFUND_ALREADY_EXISTS(50003)` 正是在那里引入的，先有错误码才能优雅返回。届时一并**修订计划第 34 行的声称**：「不改动任何现有接口的行为」在唯一索引落地后已不成立，应改为「既有接口的**正常路径**行为不变；重复提交从『静默写入第二条』变为『返回业务错误』」。

---

## K-7 refund 未加入仓库既有的索引检查清单

| | |
|---|---|
| **状态** | **已处理**（2026-09-19，`AGENTS.md` 已提交；`CLAUDE.md` 见下方说明） |
| **发现于** | 2026-09-19，Task 1 的代码质量审查 |
| **位置** | `supermall/CLAUDE.md:366-367`、`supermall/AGENTS.md:368` |
| **处理时机** | 计划 A 完成前（越早越便宜） |

**现状**：supermall 的 `CLAUDE.md` / `AGENTS.md` **已经记录了这个确切的失败模式**——在 `init.sql` 的索引变更之前建的数据库，会静默缺少该唯一索引，依赖它的幂等分支变成死代码。仓库文档里的验证查询只列了 `user_coupon`、`payment_record`、`logistics` 三张表，**`refund` 不在其中**。

**为什么重要**：spec 把这条约束算作**第三道防线（系统不变量）**。一个新环境或疏漏的环境会**静默地没有第三道防线**，只在生产中表现为重复退款——正是本任务要防的那个失败。而**测试帮不上忙**：整个测试套件都 mock 了 `RefundMapper`，`insert` 在测试里无条件成功，没有任何测试能发现索引缺失。

**回答「迁移与 init.sql 的一致性靠什么保证」**：**什么都不保证**。它们是两个手工维护的文件，仓库仅有的机制就是那份检查清单。

**修复方向**：把 `refund` 加进 `CLAUDE.md:367` 与 `AGENTS.md:368` 的查询，并注明「缺失会静默使退款幂等失效」。这是关闭这个洞最便宜的改动，且符合仓库既有约定（也是 K-2 的缓解措施）。

**决定（2026-09-19）**：修。作为**独立小改动**执行（只动仓库级文档，与任何任务的代码改动分开提交），在 Task 1 收尾后立即做。

**执行结果（2026-09-19，提交 `caef8aa`）**：

- **`AGENTS.md`（已提交）**：加上了 `refund` 与后果说明，并把核对查询**直接写进该文件**——原先它指向「见『测试覆盖基线』同级的索引检查说明」，是个悬空引用。**这是分发载体，K-7 的实际价值在此。**
- **`CLAUDE.md`（改动已落盘，但无法提交）**：该文件被 `.gitignore:41` 的 `/CLAUDE.md` 排除，未被 git 跟踪。因此在这个仓库里，**它的改动只对本机的 agent 会话生效，不随仓库分发**。已核实 `.gitignore:41` 确有此行、`AGENTS.md` 已跟踪而 `CLAUDE.md` 未跟踪。

**未做的事**：没有用 `git add -f` 强加 `CLAUDE.md`。该 ignore 看起来是有意为之，且 `AGENTS.md` 自身写着「禁止用 `git add -f` 强制加入」被忽略的文件。

**决定（2026-09-19）**：**`CLAUDE.md` 保持被忽略，不做改动。**

理由：`AGENTS.md` 是分发载体且已含完整信息（含核对查询本身）；`CLAUDE.md` 本就是本地文件，改动对本机 agent 会话即时生效；全新 clone 下没有 `CLAUDE.md`，但 `AGENTS.md` 已经够用。用户明确表示「不用动」。

因此 K-7 **判定为已完成**——`refund` 已进入随仓库分发的那份清单。

---

## K-8 被驳回的退款会永久阻塞该订单的后续申请

| | |
|---|---|
| **状态** | 待判断（计划层面问题） |
| **发现于** | 2026-09-19，Task 1 的代码质量审查（计划层问题，非实现缺陷） |
| **位置** | 计划 A 的「已知简化」清单（第 1295-1301 行）应补入 |
| **处理时机** | 商家审批流落地之前 |

**现状**：退款行**永不删除**，且没有逻辑删除列。所以一条最终为 `REJECTED` 的退款会**永久**阻塞该订单的后续退款——「先驳回、再重新申请」变得不可能。

**当前是否触发**：**否，是潜在问题**。现在没有任何代码写 `REJECTED`；`docs/implementation-plan.md:411-412` 描述的 `/api/merchant/refunds/{id}/approve|reject` 端点在代码里**不存在**（grep `module/merchant` 无退款相关代码）。

**但**：`CLAUDE.md:178` 把退款生命周期描述为 PENDING/APPROVED/REJECTED/COMPLETED，说明商家审批流是**已规划**的。届时会触发。

**需要一并决定**：Task 4 的 `refundExists` 对一条 `REJECTED` 行应当如何解释——按当前逻辑，它会让订单永久不可退。

**建议**：写进计划 A 的「已知简化」清单（那里已有三条同类条目），并明确 `refundExists` 的语义。

**关联陷阱（2026-09-19 追加核实）**：supermall 的 `application.yml` 配了 **MyBatis-Plus 全局逻辑删除**——

```yaml
mybatis-plus.global-config.db-config.logic-delete-field: deleted
logic-delete-value: 1
```

`Refund` PO 当前**没有** `deleted` 字段，所以逻辑删除不生效（这是好事，`refund` 的 `order_id` 唯一索引不会被软删除行污染）。

**但这是个陷阱**：全局配置意味着，**只要有人给 `Refund` PO 加一个名为 `deleted` 的字段**，MP 就会自动接管为逻辑删除——而**软删除的行依然占用 `order_id` 唯一索引**。届时「软删除退款 → 重新申请」看起来可行、实际仍被唯一索引挡住，且没有任何报错提示原因。

排查这一点本身就花了时间：`Refund` 的**表**和 **PO** 都没有 `deleted`，但**全局配置**存在。将来若决定用软删除解决本条，必须先处理唯一索引（例如改为 `UNIQUE KEY (order_id, deleted)` 之类的复合形式或部分索引方案），否则会得到一个静默失效的修复。

---

## K-9 数据库约束对 CI 完全不可见

| | |
|---|---|
| **状态** | 待判断 |
| **发现于** | 2026-09-19，Task 1 的代码质量审查 |
| **位置** | 整个 `mall-server/src/test/` |
| **处理时机** | 计划 A 的 Task 5 之前（那里要依赖幂等） |

**现状**：测试套件里所有测试都 mock 了 `RefundMapper`，`refundMapper.insert` 在测试中无条件成功。因此**没有任何测试能发现唯一索引缺失**，也没有任何测试能验证幂等行为真的由数据库保证。

**影响**：Task 5 的幂等测试（`execute_shouldBeIdempotentWhenRefundAlreadyCompleted`）验证的是**代码分支**（查到既有记录就返回），**不是**数据库约束。这两者是两道不同的防线，而后者没有任何测试覆盖。

**可能的处理方向**：加一个**真连数据库**的集成测试（不 mock mapper）验证唯一约束；或在 Task 8 的端到端验证里显式覆盖「并发/重复执行」场景。

---

## K-10 迁移脚本的 `USE mall;` 会让临时库测试静默打到真库

| | |
|---|---|
| **状态** | **修复中**（用户 2026-09-19 决定处理，方案见下） |
| **发现于** | 2026-09-19，Task 1 的修复过程中 implementer 自己踩到并上报 |
| **位置** | `supermall/.../db/migration/2026-09-18-refund-unique.sql:53` |
| **处理时机** | 出现第二个迁移脚本时 |

**现状**：脚本硬编码了 `USE mall;`（这是为了让不带 `-D` 执行时不报 `ERROR 1046 No database selected`）。**代价**：任何人在临时库上测试这个脚本时，如果没有改掉这一行，脚本会**静默地作用到真实的 `mall` 库**——不报错、不提示。

implementer 自己就是靠「把测试副本里的 `USE` 那一行替换成临时库名」才完成测试的，并主动上报了这个顾虑。

**影响**：当前只有本机一个环境、一个脚本，风险可控。但若被当作模板复制（计划 B/C 若新增迁移，或将来多人协作），这是一个**会静默改到真库**的陷阱。

**可能的处理方向**：
- 保持现状，靠执行者自觉（脚本头部已注明「要迁移别的库只需改这一行」）
- 改为表名限定 `mall.refund`（同样需要改一处，但错改时更容易发现）
- 库名参数化（MySQL 脚本对参数化支持较差，成本高）

**决定（2026-09-19）**：**去掉 `USE mall;`，改在调用方式里写 `-D mall`。**

方案由代码质量审查者在重审时提出，它**明确承认这是它自己上轮 Minor 建议的错**——它把一个响亮的无害失败（`ERROR 1046`）换成了静默打错库的风险。新方案在四个方面更优：

| | `USE mall;`（旧） | `-D mall`（新） |
|---|---|---|
| 跨环境一致性 | 每个环境要改跟踪的文件——**正是导致 K-1 的漂移模式** | 文件逐字一致，不用改 |
| 目标库可见性 | 藏在脚本里 | 出现在 shell 历史与 CI 日志 |
| 临时库测试 | 必须改文件，忘了就打到真库 | `-D mall_scratch`，无需改文件 |
| 忘加参数 | **静默写生产库** | 无害的 `ERROR 1046` |

**注**：`information_schema` 查询用的是 `DATABASE()`，`-D` 指定的库正是它返回的值，所以判定逻辑不受影响（已要求实现时验证这一点）。

---

## 已处理（保留供追溯）

### Task 3 Step 2 是空操作

计划要求把 `init.sql` 的 `order.status` 注释改为含 `REFUNDED` 的版本，但该文件**已经是** `PENDING/PAID/SHIPPED/DELIVERED/RECEIVED/REFUNDED/CANCELLED`。**跳过**即可。

### refund 表的冗余索引

`refund` 原有非唯一索引 `KEY idx_order_id (order_id)`，加入 `uk_refund_order` 后完全冗余。**用户决定删除**，已在 Task 1 落地。

### 构建产物是陈旧副本

`mall-server/target/classes/db/init.sql` 仍含旧定义。该目录被 `.gitignore` 忽略且未被 git 跟踪，是 Maven 重新生成的副本，**不属于交付范围**。
