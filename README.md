# after-sales-agent

一个**能真正执行售后动作**的电商客服系统：对话 Agent 理解诉求，可信代码查证并编排退款，独立复核 Agent 审查敏感动作，supermall 最终执行。

业务后端是独立的另一个项目 [supermall](https://github.com/breadhou/Supermall)，两者通过 **MCP** 通信，本项目不直连它的数据库。

**为什么不是"又一个电商客服 demo"**：多数项目停在"查询并回答"。本项目要**真执行**——一旦涉及执行，就必须回答权限边界、幂等、失败恢复、审计，**这些是工程问题不是 prompt 问题**。

---

## 当前状态

**计划 A 的 Task 1–8 均已实现并完成验收**，代码已集成到 supermall 的 `main`。Task 7 的实现提交为 `b1ff494`；Task 8 的验证记录在 [`supermall/docs/plan-a-task8-validation-2026-09-22.md`](../supermall/docs/plan-a-task8-validation-2026-09-22.md)；政策条款与执行语义的最终对齐提交为 `bf2d59a`。

**计划 B 的 Task 1–6 已完成**：MCP Server 的实现提交为 `2e026da`，参数校验修复为 `0391378`；Task 6 的真实环境响应与数据库核对见[验证记录](docs/plan-b-task6-validation-2026-09-24.md)。34/34 Maven 测试通过；运行验证使用 JDK 22，JDK 17 尚未验证。执行中发现的问题与取舍记录在 [`docs/known-issues.md`](docs/known-issues.md)。

**计划 C 的 Task 1–6 已完成**：真实模型、MCP 与退款端到端验证见[验证记录](docs/plan-c-task6-validation-2026-09-25.md)。根 Maven reactor 98/98、启动器测试 5/5 通过；运行验证使用 JDK 22，JDK 17 尚未验证。

**阶段 3A 的 Task 1–8 已完成、真实环境验证与独立 SDD 审查均通过**：对话 Agent 在明确退款诉求下只转接；可信代码确认请求并执行资格门槛；同源政策条款进入独立复核。真实模型、MCP、数据库、真实目录部署切换及失败降级的证据见[阶段 3A 验证记录](docs/phase3a-rag-refund-validation-2026-09-28.md)。修复 SDK 错误语义后，根 Maven reactor 170/170，通过 JDK 22 验证。

**阶段 3B 的 Task 1–6 已完成，独立 SDD 任务审查、整分支审查修复及定向复审均通过**：32 条原创 FAQ、21 条经商家业务 API 创建且当前上架的演示商品、只读商品 MCP 与无动作解释已接线。真实 API、MCP、模型问答和正常退款证据见[阶段 3B 验证记录](docs/phase3b-knowledge-validation-2026-09-30.md)；最终主控验证 Maven reactor **240/240**、Python **14/14** 通过。解释补充只接受代码定义的固定句，任一最终商品引用复核失败则整次资料答复不可用。一次复核异常按失败降级且未提交，后续无动作诊断与新会话单次退款重试通过；旧异常原因未查明，K-52 / K-53 保持待判断。阶段 3A / 3B 已按用户授权集成到两个仓库的 `main`，见[主线集成验证记录](docs/phase3-main-integration-2026-09-30.md)。

### 文档地图

| 文档 | 内容 |
|---|---|
| [`docs/specs/2026-09-18-after-sales-agent-design.md`](docs/specs/2026-09-18-after-sales-agent-design.md) | **设计基线**。职责边界、三道防线、评测设计 |
| [`docs/specs/2026-09-26-rag-policy-review-design.md`](docs/specs/2026-09-26-rag-policy-review-design.md) | **阶段 3 当前设计**：确定性退款编排与 RAG 政策复核 |
| [`docs/specs/2026-09-26-rag-explanation-design.md`](docs/specs/2026-09-26-rag-explanation-design.md) | 阶段 3 历史方案，已被上项取代 |
| [`docs/plans/2026-09-18-plan-a-supermall-after-sales.md`](docs/plans/2026-09-18-plan-a-supermall-after-sales.md) | 计划 A：supermall 售后能力（8 任务） |
| [`docs/plans/2026-09-18-plan-b-mcp-server.md`](docs/plans/2026-09-18-plan-b-mcp-server.md) | 计划 B：MCP Server（6 任务） |
| [`docs/plans/2026-09-18-plan-c-agent.md`](docs/plans/2026-09-18-plan-c-agent.md) | 计划 C：决策 + 复核 Agent（6 任务） |
| [`docs/plans/2026-09-26-phase3a-rag-refund-review.md`](docs/plans/2026-09-26-phase3a-rag-refund-review.md) | 阶段 3A：确定性退款编排与 RAG 政策复核（8 任务） |
| [`docs/phase3a-rag-refund-validation-2026-09-28.md`](docs/phase3a-rag-refund-validation-2026-09-28.md) | 阶段 3A 真实模型、MCP、数据库及目录部署验证 |
| [`docs/plans/2026-09-26-phase3b-knowledge-qa.md`](docs/plans/2026-09-26-phase3b-knowledge-qa.md) | 阶段 3B：FAQ 与当前演示商品问答（6 任务，依赖 3A） |
| [`docs/phase3b-knowledge-validation-2026-09-30.md`](docs/phase3b-knowledge-validation-2026-09-30.md) | 阶段 3B 自动化、真实语料、API、MCP 与模型验证 |
| [`docs/phase3-main-integration-2026-09-30.md`](docs/phase3-main-integration-2026-09-30.md) | 两个仓库的 main 集成与合并结果验证 |

### 进度

| # | 阶段 | 产出 | 计划 | 状态 |
|---|---|---|---|---|
| A | supermall 售后能力 | 政策判定 + 资格查询 + 退款执行 + 幂等 | ✅ 已写 | ✅ Task 1–8 已实现并验证 |
| B | MCP Server | 6 个工具，stdio 传输 | ✅ 已写 | ✅ Task 1–6 已完成并验证 |
| C | Agent | 决策 Agent + 复核 Agent + 升级人工 | ✅ 已写 | ✅ Task 1–6 已完成并验证 |
| 3A | RAG 政策复核 | 确定性编排、同源政策复核、后端写入前版本校验 | ✅ 已写（8 任务） | ✅ Task 1–8 已验收；实测与 SDD 审查通过 |
| 3B | 资料问答 | 原创 FAQ、当前演示商品、无动作解释 | ✅ 已写（6 任务） | ✅ Task 1–6 已验收；实测与审查通过；已集成 main |
| 4 | 评测集 | 240 条场景 + 自动判定 + 回归 | ❌ 未写 | ⏸ 待设计 |

阶段 3 的权威政策只有三条，因此退款复核按政策编号精确检索；FAQ 与当前演示商品用于资料问答。阶段 4 的系统评测待讨论与设计。

---

## 下一步

阶段 3A / 3B 已验收并集成到 main；下一步讨论阶段 4 评测设计，明确场景、自动判定与运行预算，再编写实施计划。计划 A/B/C 已完成；历史计划复选框未回填，不作为进度依据。

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

> **阶段 3A 当前实现**：对话 Agent 只提出退款转接候选；可信代码绑定用户确认、重读事实、精确检索同源政策并调用独立复核；复核通过后由后端核对版本和业务不变量再执行。

金额、资格、状态机、幂等全在 supermall（确定性代码）；对话、转接、政策复核与工具编排在本项目。

### 三道防线

| 防线 | 位置 | 挡住什么 | 依赖模型表现 |
|---|---|---|---|
| 1. 工具面收窄 | 设计 | 没有"指定金额退款"这类工具 | **否** |
| 2. Agent 策略 + 复核 | prompt + 独立复核 Agent | 诱导话术、越权诉求 | 是 |
| 3. 系统不变量 | supermall | 状态机、金额服务端算、唯一索引幂等 | **否** |

评测要**诚实地分开声称**：跨用户越权由系统强制（不依赖模型），本用户权限内的操作只由第 1、2 道防线保证。

### 复核是结构上不可绕过的

**阶段 3A 当前实现**：决策 Agent 的 MCP 工具被过滤为 3 个只读工具，没有 `submit_refund`、资格或政策目录工具。退款只通过本地无写入能力的转接进入可信编排：

```
决策 Agent 可见：get_order / list_user_orders / get_logistics ← 只读 MCP
                handoff_refund / ask_refund_eligibility / escalate_to_human ← 无退款写入能力的本地工具
用户确认订单与理由 → 可信代码重读订单和资格 → 精确政策条款 → 无工具复核 Agent
                   └─驳回或事实不一致 → 人工升级
                   └─通过 → MCP submit_refund → supermall 锁内校验并执行
```

退款写入只能由可信编排在确认、资格门槛与独立复核后触发；对话 Agent 没有直接提交工具。

目录每次复核前刷新；资格指纹、复核条款与写入时版本须一致。任何一个环节失败都停止自动退款。

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
