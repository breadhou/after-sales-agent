# after-sales-agent

一个**能真正执行售后动作**的电商客服 Agent：理解用户诉求 → 查明事实 → 选择政策 → **执行**（退款 / 拒绝 / 升级人工）。

业务后端是独立的另一个项目 [supermall](https://github.com/breadhou/Supermall)，两者通过 **MCP** 通信，本项目不直连它的数据库。

**为什么不是"又一个电商客服 demo"**：多数项目停在"查询并回答"。本项目要**真执行**——一旦涉及执行，就必须回答权限边界、幂等、失败恢复、审计，**这些是工程问题不是 prompt 问题**。

---

## 当前状态

**计划 A 的 Task 1–8 均已实现并完成验证**，代码位于 supermall 的 `feat/after-sales-capability` 分支。Task 7 的实现提交为 `b1ff494`；Task 8 的验证记录在 [`supermall/docs/plan-a-task8-validation-2026-09-22.md`](../supermall/docs/plan-a-task8-validation-2026-09-22.md)。

下一步是本仓库的计划 B（MCP Server）。K-13 的 Agent 消费者仍待计划 B/C 落地；K-36 的架构验收仍待用户裁定，因此不能宣称计划 A 的架构已完全验收。执行中发现的问题与取舍记录在 [`docs/known-issues.md`](docs/known-issues.md)。

### 文档地图

| 文档 | 内容 |
|---|---|
| [`docs/specs/2026-09-18-after-sales-agent-design.md`](docs/specs/2026-09-18-after-sales-agent-design.md) | **设计基线**。职责边界、三道防线、评测设计 |
| [`docs/plans/2026-09-18-plan-a-supermall-after-sales.md`](docs/plans/2026-09-18-plan-a-supermall-after-sales.md) | 计划 A：supermall 售后能力（8 任务） |
| [`docs/plans/2026-09-18-plan-b-mcp-server.md`](docs/plans/2026-09-18-plan-b-mcp-server.md) | 计划 B：MCP Server（6 任务） |
| [`docs/plans/2026-09-18-plan-c-agent.md`](docs/plans/2026-09-18-plan-c-agent.md) | 计划 C：决策 + 复核 Agent（6 任务） |

### 进度

| # | 阶段 | 产出 | 计划 | 状态 |
|---|---|---|---|---|
| A | supermall 售后能力 | 政策判定 + 资格查询 + 退款执行 + 幂等 | ✅ 已写 | ✅ Task 1–8 已实现并验证 |
| B | MCP Server | 6 个工具，stdio 传输 | ✅ 已写 | ⬜ 待执行（**依赖 A**） |
| C | Agent | 决策 Agent + 复核 Agent + 升级人工 | ✅ 已写 | ⬜ 待执行（**依赖 B**） |
| 3 | RAG 解释层 | 政策条款与 FAQ 的检索，**只解释不判定** | ❌ 未写 | ⏸ 待设计 |
| 4 | 评测集 | 240 条场景 + 自动判定 + 回归 | ❌ 未写 | ⏸ 待设计 |

> **阶段 3、4 为什么不先写计划**：RAG 的语料需要做厚（3 条政策做检索没有意义，会被问"为什么不直接塞进 prompt"），而评测集的用例必须**以真实运行结果为依据**——现在写会脱离实际。两者都要等 A/B/C 跑通。

---

## 下一步

从本目录开会话，说：

> 执行 `docs/plans/2026-09-18-plan-b-mcp-server.md`

计划头部声明了该用哪个子技能（`executing-plans` 或 `subagent-driven-development`），新会话读到即可接续，**跨会话不受影响**。

> **当前进度：计划 A 的 Task 1–8 均已实现并验证。** Task 7 的实现提交为 supermall `b1ff494`；Task 8 的验证记录在 `supermall/docs/plan-a-task8-validation-2026-09-22.md`。
>
> 下一步执行本仓库的计划 B（MCP Server）。K-13 的 Agent 消费者仍待计划 B/C 落地；K-36 的架构验收仍待用户裁定，因此不能宣称计划 A 的架构已完全验收。
>
> **Task 5 的提交链**（supermall，按顺序）：
> `8c52d91`（主实现）→ `e070723`（并发兜底修复）→ `b43e161`（注释订正）→ `bc94d5e`（返回契约 + 测试缺口）→ `71d8537`（PENDING 措辞）。
>
> **Task 6 的提交链**（supermall，按顺序）：
> `e15c05b`（两个端点 + DTO + 测试）→ `2a792c0` / `62e6ae1` / `d48a3d2` / `ecb7f37`（契约文本四轮订正）→ `e9adfa5`（校验失败返 `10000` + 补限定词）。
>
> ⚠️ **Task 6 的契约文本改了五轮**，每一轮修的都是「文本声称的事与代码实际行为不符」——
> 详见 `docs/known-issues.md` 的 K-28 / K-29 / K-30 / K-31。**读那几段比读代码更快理解这里的坑在哪。**
>
> ⚠️ **计划里的复选框没有被回填**（仍是初始状态，53 个全部未勾）——**不要拿它当进度依据**，否则会从 Task 1 重做。可靠的进度依据是 supermall 仓库的分支历史：
>
> ```bash
> git -C D:/sourcecode/supermall log --oneline main..feat/after-sales-capability
> ```
>
> 按提交信息与任务一一对应即可看出做到哪。
> Task 5 的代码块里有两处注释说明了两个坑（`@Transactional` 下的 read view 不会刷新、唯一索引才是并发裁判），照它写即可。

**顺序不能乱**：A → B → C。B 依赖 A 的三个新端点，C 依赖 B 能跑起来。

### 环境要求

- Java 17+（本机用 `D:\jdks\openjdk-22.0.2`）
- supermall 运行在 `localhost:8081`，且需要**三个环境变量**才能启动：
  `MERCHANT_JWT_SECRET`、`MALL_WORKER_ID`、`MALL_DATACENTER_ID`（缺一个就起不来）
- MySQL / Redis / RabbitMQ 需先启动（Redis 与 RabbitMQ 跑在 WSL 容器里）
- 模型：任意 **OpenAI 兼容**端点（DeepSeek、OpenRouter 均可），通过 `MODEL_BASE_URL` / `MODEL_API_KEY` / `MODEL_NAME` 配置

**凭据不入库**：用户口令、模型密钥一律走环境变量。这条是本项目的硬约定。

> **执行三份计划时 supermall 必须在运行**，而它的启动流程有些繁琐（三个环境变量 + 两套服务机制 + WSL 会自动关闭）。
> 已记录要做成项目 skill，见 supermall 仓库 `AGENTS.md` 的「待办」第 2 项。在此之前，按那个文件的「本地测试环境启动」手工启动。

---

## 设计要点（三分钟看懂）

### 职责边界

> **LLM 决定「该走哪条路」，代码决定「这条路怎么走、能不能走」。**

金额、资格、状态机、幂等全在 supermall（确定性代码）；意图理解、政策选择、工具编排在本项目。

### 三道防线

| 防线 | 位置 | 挡住什么 | 依赖模型表现 |
|---|---|---|---|
| 1. 工具面收窄 | 设计 | 没有"指定金额退款"这类工具 | **否** |
| 2. Agent 策略 + 复核 | prompt + 独立复核 Agent | 诱导话术、越权诉求 | 是 |
| 3. 系统不变量 | supermall | 状态机、金额服务端算、唯一索引幂等 | **否** |

评测要**诚实地分开声称**：跨用户越权由系统强制（不依赖模型），本用户权限内的操作只由第 1、2 道防线保证。

### 复核是结构上不可绕过的

**决策 Agent 的工具集里根本没有 `submit_refund`。** 它的 MCP 工具被过滤为 5 个只读工具，退款走本地工具 `request_refund`，复核与执行都在其内部：

```
决策 Agent 可见：get_order / list_user_orders / get_logistics /
                get_refund_eligibility / list_policy_clauses   ← 全只读
                request_refund(orderId, reason)                ← 本地工具
                     └─ 内部：复核 ──驳回──▶ escalate_to_human
                                └─通过──▶ 调用 MCP 的 submit_refund
                escalate_to_human
```

若模型手里就有 `submit_refund`，复核只是它"记得要调用"的一步，可以被跳过。现在它是唯一通路。

### 已知的坑

1. **stdio 下 stdout 被 JSON-RPC 独占**——日志必须走 stderr，否则协议被破坏。计划 B 有专门一步验证 stdout 为空。
2. **工具的错误是给模型看的**，不是给开发者看的——不要抛堆栈和异常类名。
3. **推理型模型（reasoner/thinking 类）通常不支持 `temperature`**——用它做评测基准，同一份用例两次结果不同，回归数字失去意义。**评测基准必须用支持固定采样的对话模型。**
4. **中文请求体必须用 UTF-8 文件**——Git Bash 直接 `curl -d` 会按 GBK 发出，服务端报 `Invalid UTF-8 start byte`。

---

## 与 supermall 的边界

Agent 通过 MCP 调用 supermall 的能力，**不直连数据库**——直连会绕过业务规则，而业务规则正是评测的判定依据。

**判断这条边界是否划对的测试**：把 supermall 拿掉，本项目还能不能讲？核心资产是工具设计、编排、评测、防线，换一个电商后端也成立。**supermall 是运行环境，不是项目本身。**

> 面试时可补一句「Agent 需要真实业务系统验证，所以后端是我自己写的」。这句话不写进简历——简历按关键字筛，两条独立条目命中两个方向。
