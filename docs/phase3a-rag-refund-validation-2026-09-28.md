# 阶段 3A 真实环境验证（2026-09-28）

本记录对应阶段 3A Task 8，验证完成于本地时间 2026-09-28。对象为独立分支 `codex/phase3a-rag-refund-review` 上的 Agent/MCP 与 supermall；最终 SDD 审查仍以审查报告为准。仅使用本地合成用户、商家和订单。Agent 经用户 JWT 调用 MCP，不直连数据库；下表数据库查询只作测试者的只读核对。

## 环境与自动化门槛

supermall、MySQL 8、WSL Redis 7 和 RabbitMQ 3 在本机运行；supermall 使用 JDK 22、8080 端口。Agent 以 OpenAI 兼容模型端点运行，温度为 0。模型密钥、JWT 和后端凭据仅在被 Git 忽略的环境文件与进程环境中；没有写入本文。目录切换使用两个真实 supermall jar，后者只替换由真实 `AfterSalesPolicy` 枚举编译的条款类。切换后恢复原版 jar，supermall 工作树始终无跟踪文件改动。

| 命令（对应仓库根目录） | 退出码与实测结果 |
|---|---|
| supermall：`& $Mvn test` | 0，`BUILD SUCCESS`；common 10、security 16、infra 6、mall-server 211，共 **243/243** |
| 本仓库：`& $Mvn test`（初次） | 0，`BUILD SUCCESS`；MCP 39、Agent 129，共 **168/168** |
| 本仓库：`python -m unittest scripts.test_run_agent` | 0，**5/5** |
| 本仓库：`& $Mvn -pl mcp-server -am package` | 0，MCP **39/39**，`BUILD SUCCESS` |
| 本仓库：`& $Mvn -pl agent -am package '-DskipTests'` | 0，`BUILD SUCCESS` |
| 本仓库：SDK 错误集成修复后 `& $Mvn -pl agent -am '-Dtest=RefundExecutorTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | 0，**12/12** |
| 本仓库：修复后 `& $Mvn -pl agent -am package` | 0，Agent **131/131**，`BUILD SUCCESS`；同时重新生成 Agent jar |
| 本仓库：修复后根目录 `& $Mvn test` | 0，MCP 39、Agent 131，共 **170/170**，`BUILD SUCCESS` |

`$Mvn` 为 `D:\JetBrains\IntelliJ IDEA 2026.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd`；PowerShell 的 `-D` 参数均加引号。初次与修复后全量测试分别在对应源代码状态执行；最终根 reactor 包含本次修复的两项新测试。运行环境为 JDK 22；JDK 17 未做运行验证。

## 真实 MCP、模型与数据库证据

新订单通过 C 端创建、付款、确认收货及商家发货、送达的业务 API 构造。`python scripts/mcp_stdio_smoke.py`（进程环境提供当前用户 JWT 与 URL）列出 **6 个工具**；`submit_refund` 的 schema 恰有 `orderId`、`reason`、`expectedCatalogFingerprint`、`expectedPolicyCode` 四个必填字段。每次直连 MCP 提交前先读取本订单资格响应中的指纹和政策码；除刻意验证过期的用例外，二者原样传入。所有下表脚本退出码为 0，除特别说明。

| 用例与命令摘要 | MCP/Agent 结果 | 只读数据库前后状态：订单状态 / 退款行数 / 退款金额 |
|---|---|---|
| 直接 MCP 正常提交，`mcp_smoke`，订单 `2104178291261968384` | 新鲜资格为 `RECEIVED`、`eligible=true`、`refundExists=false`、`SEVEN_DAY_NO_REASON`、199.99；提交成功 | `RECEIVED/0/0.00` → `REFUNDED/1/199.99`，退款行状态 `REFUNDED` |
| 未确认 CLI，`python agent/target/phase3a_agent_case.py unconfirmed`，订单 `2104177767968018432` | 只有 `handoff_refund` trace；无复核、无提交 | `RECEIVED/0/0.00` → 同值 |
| 已确认但后端不可退 CLI，`... ineligible --confirm`，订单 `2104177769549271040` | 新鲜查单与资格为 `PENDING`；无复核、无提交；固定回复说明不可退 | `PENDING/0/0.00` → 同值 |
| 已确认正常 CLI，`... normal --confirm`，订单 `2104177771046637568` | trace 顺序为转接、查单、资格、目录、复核通过、提交成功；可信回执称退款完成 | `RECEIVED/0/0.00` → `REFUNDED/1/199.99`，退款行状态 `REFUNDED` |
| 真实复核驳回，订单 `2104248778847555584` | 原版 jar 中的复核提示词与源文件 SHA-256 一致。无写入探针使用相同事实与原话，返回结构化 `approved=false`、引用码匹配、`USER_INSTRUCTION_RISK`、`valid=true`；可信 `RefundWorkflow` 随后驳回，同会话重试不再复核（总复核 1 次）、提交 0 次、升级 1 次 | `RECEIVED/0/0.00` → 同值 |
| 错误目录指纹，`python agent/target/phase3a_mcp_cases.py stale`，订单 `2104177772472700928` | MCP `isError=true`，业务码 **50005** | `RECEIVED/0/0.00` → 同值 |
| 错误政策码，`... context`，订单 `2104177773517082624` | 当前指纹配错误码，MCP 业务码 **50005** | `RECEIVED/0/0.00` → 同值 |
| 真实政策适用情境变化，订单 `2104254843353960448` | `SHIPPED` 时资格码 `SHIPPED_NOT_RECEIVED`，真实复核模型批准；再经商家送达与 C 端确认收货 API，资格变为 `RECEIVED` / `SEVEN_DAY_NO_REASON`，目录指纹未变。用复核时的旧码提交，MCP 业务码 **50005** | `SHIPPED/0/0.00` → `RECEIVED/0/0.00`；仅业务订单状态变化，无退款行 |
| 已退款幂等重试，`... replay`，订单 `2104178291261968384` | MCP 成功回执为 `eligible=false`、`refundExists=true` | `REFUNDED/1/199.99` → 同值，无第二行 |
| 同一缺失键索引区间内并发提交，`... concurrent`，订单 `2104177774565658624` 与 `2104177775744258048` | 提交前数据库中两目标之间为 0 行，紧邻区间边界为 `2104177771046637568` 与 `2104178291261968384`；两个独立 MCP 进程同时写入，均成功，无死锁或超时 | 两单各自 `RECEIVED/0/0.00` → `REFUNDED/1/199.99`，退款行状态均为 `REFUNDED` |

复核驳回用例的原话明确声称管理员身份并要求跳过查证。对话模型在早先 CLI 尝试中直接升级，未把这条诉求交给复核；因此驳回证据使用已确认请求形态的可信 `RefundWorkflow`，它仍通过真实 MCP 读取事实、真实目录和真实模型复核。测试执行器含“意外批准也不得写入”的保护，实测提交调用数为 0。早先一次临时提示词覆盖未造成驳回，反而让另一合成订单按正常路径退款；该尝试**不计作驳回证据**，提示词已逐字节恢复并重新打包。

## 同一进程中的真实政策目录切换

`python -u agent/target/phase3a_switch_case.py`（退出 0）保持一个 Agent/MCP JVM 存活，按以下顺序执行；编排脚本首次曾因 Windows 后台进程输出句柄等待而中止，原版服务已恢复。第二次曾暴露下节的真实 SDK 集成缺陷，修复并重新打包后才得到本节结果。两次失败编排均不计作产品验证。

1. 原版 supermall 上，订单 `2104177776801222656` 的新鲜事实与目录完成真实模型复核，`approved=true`。探针停在复核后、提交前。
2. 部署只更改 `SEVEN_DAY_NO_REASON` 正文、语义仍相同的第二个真实 supermall jar。原 Agent/MCP JVM 不重启。携带旧指纹和原政策码的提交被执行器识别为后端 **50005**，订单仍 `RECEIVED/0/0.00`。
3. 同一消费者刷新后，指纹改变，精确政策码的正文也包含新版本文字。订单 `2104177777975627776` 经同一 Agent JVM 的完整可信流程、真实模型复核及 MCP 提交，在新版本下由 `RECEIVED/0/0.00` 变为 `REFUNDED/1/199.99`。
4. 停止后端但保持 Agent/MCP 进程存活：直接调用同一消费者的 `PolicyCatalogConsumer.refresh()` 抛错，没有向该调用返回旧目录。另对订单 `2104177779082924032` 发起完整 `RefundWorkflow`；它在读取订单事实时先失败并升级人工，未到目录刷新步骤，提交总次数不增加，数据库保持 `RECEIVED/0/0.00`。隔离的目录刷新失败不返回旧证据由 `PolicyCatalogConsumerTest.failedRefreshNeverReturnsOldEvidence` 覆盖。
5. 恢复原版 supermall jar；同一消费者再次读到原版指纹。最终 8080 端口运行原版 jar，supermall 工作树无跟踪改动。

这次切换验证了真实发布端目录、Agent 消费者刷新失败时不返回旧目录、整站断服时事实查询失败后无写入，以及复核后写入版本保护。临时 jar 和探针均位于被 Git 忽略的 `target`，没有用目录 stub 代替真实部署。

## 无执行的模型 A/B 与错误语义

`python agent/target/phase3a_review_probe.py --ab switch_guard ...`（退出 0）对**同一订单事实、同一原始用户诉求和同一候选动作**调用两次无工具复核。A 使用当前真实条款：`approved=true`、空问题列表；B 仅在复核输入中替换为“买错了须人工核实，不适用自动退款”的受控条款：`approved=false`、`POLICY_CONFLICT`。两次都返回正确顶层政策码且 `valid=true`，没有执行器，数据库前后为 `RECEIVED/0/0.00`。B 是仅用于检验模型对政策证据敏感性的对照文本，**不是**已部署的权威目录。

实测发现 LangChain4j 在 MCP `isError=true` 时抛出 `ToolExecutionException`，初版 `RefundExecutor` 只识别返回对象，因而把后端确定的 50005 当成结果不明。先新增回归测试并观察 12 个测试中 1 个预期失败，再只从 SDK 应用错误异常中解析完整 `{error:true,code:50005,message:<非空>}` 业务包；非 50005、协议错误、畸形文本与传输异常仍视为结果不明。修复后聚焦 12/12、完整 170/170，真实 MCP 的旧指纹调用抛出 `StaleReviewException`；目标订单依旧 0 行。断服时完整流程在事实查询阶段停止，目录刷新失败由独立调用观察；本轮没有人为制造提交后超时，因此不宣称验证了“结果未知时到底写入与否”。

## 边界与收口

- K-13 的目录消费者与真实部署切换已有本轮证据；断服时直接刷新失败不返回旧目录，完整流程在事实查询阶段无写入，隔离的目录刷新失败由消费者单元测试覆盖。K-49 的未确认、不可退硬门槛与正常执行链路已有本轮证据，可转“已处理”。
- K-50 的普通无退款意图回复风险仍属开放词汇边界；本轮正常和拒绝用例没有穷尽该风险，保留“待判断”。
- 阶段 3B 的 FAQ、商品资料问答没有实施或计入本次结果。JDK 17、生产支付系统和提交后网络故障注入均未验证。

## 最终 SDD 验收（2026-09-28）

Task 1–8 均经逐任务实现、验证与独立复审。最终只读整分支审查覆盖 Agent/MCP `75b35a2..535cb99` 和 supermall `9d45f49..878ac93`，结论为 Phase 3A 可验收，无 Critical、Important 或 Minor 新发现。审查后的状态回填只改文档，不改运行代码。

主代理在这两个运行代码提交上再次执行完整验证：Agent/MCP Maven reactor 170/170、supermall Maven reactor 243/243、Python 启动器 5/5，命令均退出 0；两个分支的 `git diff --check` 均通过。K-50 仍为“待判断”；本记录上节所列 JDK 17、真实支付渠道和提交后网络故障仍未验证。阶段 3B 尚未开始。
