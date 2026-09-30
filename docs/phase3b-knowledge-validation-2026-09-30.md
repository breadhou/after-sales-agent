# 阶段 3B：FAQ、当前演示商品与跨链路验证

执行日期：2026-09-29 至 2026-09-30（Asia/Shanghai）。本轮工作树为 `codex/phase3b-knowledge-qa`，Task 6 起点为 `b39c9eb`；Tasks 1–6 均已通过独立 SDD 任务审查。supermall 使用已验收阶段 3A 的 `878ac93`。整分支审查发现由 `4af9420` 修复，四项发现均通过独立定向复审；主控最终验收已完成。2026-09-30 已按用户授权集成到两个仓库的 `main`，后续合并结果验证见[主线集成记录](phase3-main-integration-2026-09-30.md)。

## 环境与资料数量

使用 JDK 22，supermall 在本机 8081 端口以 `loadtest` profile 运行；MySQL80 是 Windows 服务，Redis / RabbitMQ 是 WSL Docker 容器。按 supermall `AGENTS.md` 启动，后端使用忽略的本地环境文件中选出的后端变量；后台 Java 进程隐藏窗口，测试期间保持一个 WSL exec 会话存活。启动标记与 3306 / 6379 / 5672 / 8081 监听均确认。WSL 提示已有 localhost 代理限制，实测业务端点可用。

模型通过已配置的 OpenAI 兼容端点调用，温度为 0。模型密钥、商家凭据、C 用户 JWT、账号口令、订单标识与原始输入都只存于本地忽略文件或进程环境；本文不包含这些值。Agent 使用启动器允许的模型配置、C 用户令牌、可选后端地址与清单路径。商家 token 未传给 Agent 或 MCP。

| 资料 | 实测数量与范围 |
|---|---|
| 原创 FAQ | 恰好 32 条，连续来源 ID `FAQ-001` 至 `FAQ-032`，均含正文、依据和复核日期 |
| 原创演示商品定义 | 21 条，仓库内 `data/demo-products.json` |
| 真实创建记录 | 商家业务 API 已确认创建 21 条，ID 仅在忽略的环境清单 |
| 最终上架状态 | 2026-09-30 重新读取，清单内 21/21 为 `ON_SHELF`；目录共 22 条，上架列表需 2 页，清单外商品不进入索引 |
| 当前权威政策 | 3 条：`QUALITY_ISSUE`、`SEVEN_DAY_NO_REASON`、`SHIPPED_NOT_RECEIVED` |

## 自动化验证

`$Mvn` 指向 `D:\JetBrains\IntelliJ IDEA 2026.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd`；PowerShell `-D` 参数均加引号。本表保留 Task 6 审查前的验证输出（整分支修复起点 `6b344b5`）；该时期为 216 个 Maven 测试，整分支审查修复后的输出单列于下节。测试日志在忽略的 `agent/target`。

| 命令 | 退出码与结果 | 证据范围 |
|---|---|---|
| `& $Mvn -pl agent '-Dtest=ExplanationServiceTest#currentCatalogReadFaqIsPublished+currentSkuReadFaqIsPublished' test`，发布改动前 | 1；2 个测试均失败，目标 FAQ 引用缺失，返回其他 FAQ | 发布门禁的 RED |
| `& $Mvn -pl agent '-Dtest=ExplanationServiceTest#currentCatalogReadFaqIsPublished+currentSkuReadFaqIsPublished,FaqCorpusTest' test`，发布改动后 | 0；4/4，`BUILD SUCCESS` | FAQ 引用可达、正文无未来能力描述、32 条依据有效 |
| `& $Mvn test` | 0；MCP 46 + Agent 170 = **216/216**，0 失败 / 错误 / 跳过，`BUILD SUCCESS` | 完整 Maven reactor，含退款回归 |
| `python -m unittest scripts.test_run_agent scripts.test_seed_demo_products scripts.test_mcp_stdio_smoke` | 0；**14/14**，`OK` | 启动环境、假 HTTP 种子与恢复、stdio 子进程环境 |
| `& $Mvn '-DskipTests' package` | 0；`BUILD SUCCESS` | 最终 MCP / Agent 运行包；测试已由上一全量命令执行 |

Maven 的预期负路径会记录安全失败或升级日志，不能据此把通过的断言算成运行故障。Python 输出无失败。JDK 17 本轮未做运行验证。

单元测试使用受控 MCP / HTTP 响应，**不是**真实商品变更证据。它们覆盖商品正文变更后摘要变化、重复或不前进分页、失败/空/缺字段/非法状态详情导致整次引用不可用、合法下架商品排除、引用前再次读取、生成异常或无效来源导致整段草稿丢弃、恶意 FAQ/商品指令与英文交易误述、商品退换文案不成为政策、历史描述限制，以及退款/升级/资格答复优先且不调用解释生成器。

## 整分支审查修复（受控证据）

2026-09-30，按主控裁定修复 I-1、I-2、M-1、M-2。补充文本只接受代码定义的“请以所引资料原文为准。”或“如需进一步核实，请联系人工客服。”完整字符串；其他叙述整段丢弃并回退来源原文。生成器仍无工具，允许句与其结构化提示共用常量，候选来源校验保留。最终引用复核只要有一个商品候选读取失败、为空、畸形、内容变化或下架，就给出整次资料不可用答复，不返回部分商品。初始刷新合法下架/草稿记录的排除行为保留。

仅完整一般 FAQ-013 标题和可选中英文问号进入一般资料解释；个人或具体订单的退款状态仍使用可信查询/无法确认答复，退款、资格、升级优先级保留。商家测试 JWT 的无凭据来源在下一节补全。K-54 至 K-57 留存已处理追溯，独立定向复审已逐项确认 I-1、I-2、M-1、M-2 全部处理且未引入新的问题；K-52、K-53 保持待判断。

| 命令 | 退出码与结果 | 证据范围 |
|---|---|---|
| `& $Mvn -pl agent '-Dtest=ExplanationServiceTest,ConversationCoordinatorTest' test`，修改实现前 | 1；96 个测试，15 失败、0 错误、0 跳过 | RED：非法补充文本 5 项，最终商品复核 7 项，一般 FAQ 路由 3 项 |
| `& $Mvn -pl agent '-Dtest=ExplanationServiceTest,ConversationCoordinatorTest,FaqCorpusTest,CurrentProductIndexTest,AgentConfigTest' test` | 0；**115/115**，`BUILD SUCCESS` | 公共答复、来源回退、正对照、FAQ 发布、索引、工具隔离及退款优先级 |
| `& $Mvn test`，首次完整门禁 | 1；MCP 46/46，Agent 193/194；1 失败、0 错误 | `AgentMainTest.runtimeExplanationServiceUsesFaqWithoutMcpTools` 的旧假模型补充句不在允许集，正确回退 FAQ-008，但旧断言要求 FAQ-006 |
| `& $Mvn -pl agent '-Dtest=AgentMainTest#runtimeExplanationServiceUsesFaqWithoutMcpTools' test` | 0；**1/1**，`BUILD SUCCESS` | 将正对照假模型输出改为允许句，保留 FAQ-006 与无工具断言，增加接受补充句断言 |
| `& $Mvn test`，实际失败驱动的重跑 | 0；MCP 46 + Agent 194 = **240/240**，0 失败 / 错误 / 跳过，`BUILD SUCCESS` | 最终修复代码的完整 reactor；新增 24 个参数化/普通测试用例 |
| `python -m unittest scripts.test_run_agent scripts.test_seed_demo_products scripts.test_mcp_stdio_smoke` | 0；**14/14**，`OK`；本轮执行一次 | 启动环境、假 HTTP 种子与恢复、stdio 子进程环境 |
| `& $Mvn '-DskipTests' package` | 0；`BUILD SUCCESS` | 修复后的 MCP / Agent 运行包供限域复审使用 |

日志为忽略的 `agent/target/whole-branch-fix-*.log`；最终 Maven 数量由两模块 Surefire XML 汇总核对。修复实现与上述受控测试使用合成问题、商品和受控 MCP / 模型响应，没有读取凭据、运行新种子或产生真实业务写入。下文真实 API/MCP/模型/退款证据属于 Task 6 原有运行包；本节不把新的固定补充句或最终失败传播称为已做真实模型验证。

### 主控最终验收

主控在 `4af9420` 独立复跑完整 Maven reactor 与指定 Python 模块：退出码均为 0，MCP 46 + Agent 194 = **240/240**，0 失败 / 错误 / 跳过；Python **14/14**。完整阶段 3B 差异 `0de77c7..4af9420` 的 `git diff --check` 通过；40 个改动文件与本地凭据值和 JWT 字面量比对未发现命中，环境清单、令牌及 SDD 记录仍被 Git 忽略。主控还通过只读业务 API 独立核对 21/21 商品上架，以及演示订单 `REFUNDED`、`eligible=false`、`refundExists=true`。

独立整分支审查与最终定向复审报告、RED/GREEN、主控复跑日志及进度保留在忽略的 `.superpowers/sdd/2026-09-26-phase3b-knowledge-qa/`。`9f0bd28` 的验收收尾只修改文档，未改动通过上述验证的运行代码；该提交没有新业务写入、合并或推送。后续用户授权的主线集成保留其协作规则、路由文件及忽略的本地验证资料，详见主线集成记录。

## 真实 supermall 业务 API

2026-09-29 运行 `python scripts/seed_demo_products.py --category-id <本地已确认分类ID>`，退出 0。使用有效商家 JWT，21 个逻辑键均获得确定成功响应；清单确认 21 条，未留下不确定创建 journal。C 用户令牌与商家令牌分别使用，其跨端访问均被拒绝。没有直接数据库写入。

该商家 JWT 在核对既有测试商家和账号身份后，为本地测试签名生成；随后通过业务路由验证有效性与 C 端/商家隔离。本文不记录实际身份 ID、令牌或签名材料。

| 操作 | 实测结果 |
|---|---|
| 匿名上架列表 | HTTP 403，未把未授权响应当空目录 |
| C 用户分页列表 / 清单商品详情 | HTTP 200；21 个清单 ID 均在上架列表；详情包含实际 SKU |
| 商家 `PUT /api/merchant/products/{id}/off-shelf` | HTTP 200；新鲜详情为 `OFF_SHELF`，新鲜上架列表排除该商品 |
| 商家更新商品恢复上架 | HTTP 200；使用完整当前商品/SKU 数据恢复，新鲜详情为 `ON_SHELF`，最终清单内 21 条全部上架 |
| 2026-09-30 `python agent/target/task6_refresh_auth.py` | 退出 0；通过 `/api/auth/login` 刷新原有 demo 用户令牌，保留订单所有权；匿名 403，清单 21/21 上架、2 页、详情 200、无 journal |

恢复后没有重新创建演示商品。分类、商家和商品的实际环境 ID 均未写入本文。

## 真实 MCP 与模型问答

2026-09-29 的 Agent 进程通过真实模型与 MCP 完成下表问答；第一次误走固定资格答复的尝试不计入 FAQ 证据。最终运行包的发布验证与独立 stdio smoke 见下一段。当前商品输出均带代码附加的历史描述限制；以下资料答复没有声称执行退款。

| 场景 | 实测输出与核对 |
|---|---|
| FAQ 查单字段 | 引用 `[FAQ-002]`，正文为语料原文，说明当前列表字段以本次查询为准 |
| 当前演示商品 | 引用清单内真实 `PRODUCT-<id>`，描述、SKU 规格、价格和库存与新鲜详情一致；首条商品价格 18.8、当次库存 40；提示当前目录不能证明历史描述 |
| 一般政策 | 实际 MCP `list_policy_clauses`，引用三条当前政策码及原文；附带不能据此判断具体订单的限制 |
| 指定订单政策解释 | 当时订单为 `RECEIVED` 且可退；实际 MCP `get_refund_eligibility` 与目录读取，资格指纹匹配，精确引用 `[SEVEN_DAY_NO_REASON]` 的当前条款原文 |

FAQ-030/031 仅在上述实时商品能力已有证据后发布：移除 `ExplanationService` 的两个未发布 ID，正文改为当前能力，依据改为已接线的代码入口，复核日期更新为 2026-09-30。没有增加解释生成器工具或变更退款执行入口。

`python agent/target/task6_publication_and_smoke.py` 使用最终运行包，最终退出 0。真实 stdio smoke 分别验证 8 个 MCP 工具的 schema、上架列表与清单内详情；每个 smoke 退出 0。调用前清除父环境的 `DEMO_MERCHANT_TOKEN`；另用非秘密 sentinel 独立检查 Java 子环境只含 OS/JVM 允许项、C 用户 token 与后端地址，商家 sentinel 被排除。运行脚本捕获原始响应，只输出校验摘要，不输出用户订单。

最终无动作发布探针退出 0：调用真实模型生成器及真实 MCP，只提供资料解释能力；FAQ-030/031 返回当前语料正文和对应来源，当前商品返回清单内来源及历史限制；索引包含 21 条，对同一未变目录的两次新鲜刷新得到相同的逐商品摘要。本轮没有再次通过商家 API 修改商品正文；**变更后摘要变化**的证据属于上述受控测试，不能称为真实商家变更验证。

## 阶段 3A 退款行为与复核失败

原有阶段 3A 的目录 v1→v2→v1 切换、版本失配拒写、不可退门槛与复核驳回已在[阶段 3A 验证记录](phase3a-rag-refund-validation-2026-09-28.md)验收，本轮没有重做这些任务。完整 Maven reactor 仍包含其确定性回归；另通过同一隔离 demo 订单做了真实正常退款验证。

订单由 C 用户业务 API 创建并通过模拟支付，支付响应为 `SIMULATED/SUCCESS`；商家发货、送达、C 用户确认收货均经业务 API。最初 `PAID` 时不可退，确认收货后 `RECEIVED` / `eligible=true` / `refundExists=false`，资格指纹非空。本轮不涉及外部支付渠道或真实配送。

第一次确认退款时，可信流程读取订单、资格与目录，但复核模型调用发生异常；`reviewSafely` 返回空结论并升级，未调用 `submit_refund`。只读业务 API 复查仍为 `RECEIVED`、可退且无退款记录。原通用日志只记录“复核调用失败，按驳回处理”，不能恢复异常类、HTTP 状态或旧模型响应，因此原因**未查明**，不归因为已证明的网络故障。

按 systematic-debugging 追踪实际 `reviewSafely` / 无工具复核调用后，2026-09-30 使用 `python agent/target/task6_run_diagnostic.py` 做无动作诊断，退出 0：从真实 MCP 刷新订单、资格与政策，调用相同复核入口，返回非空且可授权的结构化结论。诊断仅允许输出异常类和数字状态，不输出异常消息、提示词或响应正文；本次无异常，且诊断没有退款执行器。这证明当时复核可用，不能解释或消除原故障。

另一次新鲜只读查询再次确认无退款记录后，使用 `python agent/target/task6_retry_refund.py` 在**新 Agent 会话**进行唯一一次已授权重试，退出 0。可信确认后 trace 顺序为 `get_order` / `get_refund_eligibility` / `list_policy_clauses` 成功，`review called/ok`，再 `submit_refund called/ok`；代码回执说明完成。新鲜业务 API 复查为 `REFUNDED`、`eligible=false`、`refundExists=true`。正常退款使用 Task 5 已验收的 `b39c9eb` 运行包；随后 FAQ 发布改动没有更改退款代码，最终全量回归通过。最终运行包另做了上文无动作资料探针。本地尝试标记阻止自动重复重试；原会话升级语义未改。没有凭模型叙述推断退款状态。

## 本轮边界与后续

- 本轮验证了 32 FAQ、21 条真实清单商品、只读 MCP 接线、无动作资料解释和正常退款链路；Task 1–6、整分支审查修复、独立定向复审及主控最终验收均已完成，已按用户授权集成到 main。
- 原复核异常原因未查明，后续无动作诊断与单次重试通过；当前失败降级行为已有证据。K-53 记录诊断信息不足，保留“待判断”。
- 最终审查确认 K-52 的消费者已经失败降级，未发现该工具成功标记造成新的架构绕过；工具边界的额外校验保持“待判断”。K-48 / K-50 及 JDK 17、提交后网络故障、真实支付渠道不因本轮通过而关闭。
- 阶段 4 全量评测尚未设计或运行，不用本轮人工场景代替统计评测。
