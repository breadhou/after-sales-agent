# AGENTS.md

本文件是本仓库面向 Codex、Claude Code 及其他代码代理的协作指南。

> ⚠️ **不要在本仓库新增 `CLAUDE.md`。** Claude Code 默认在「`CLAUDE.md` 与 `AGENTS.md`」之间**二选一**：
> 路径上只要出现 `CLAUDE.md`，`AGENTS.md` 就**不再被读取**。本仓库靠「只有 `AGENTS.md`」来同时服务两个 agent。

## 这个仓库是什么

售后决策与执行 Agent 的**设计与计划仓库**。业务后端是独立的另一个项目
[supermall](https://github.com/breadhou/Supermall)（本地 `D:\sourcecode\supermall`），两者通过 **MCP** 通信，
**本项目不直连它的数据库**——直连会绕过业务规则，而业务规则正是评测的判定依据。

> **计划 A 的代码改动在 supermall；计划 B/C 的代码改动在本仓库**（`mcp-server` 与 `agent`）。计划 B 的 `mcp-server` 已完成 Task 1–6；计划 C 的 Task 1–6 已完成。

| 文档 | 内容 |
|---|---|
| `docs/specs/2026-09-18-after-sales-agent-design.md` | 设计基线：职责边界、三道防线、评测设计 |
| `docs/specs/2026-09-26-rag-policy-review-design.md` | **阶段 3 当前设计**：确定性退款编排 + RAG 政策复核 |
| `docs/specs/2026-09-30-phase4-single-model-evaluation-design.md` | **阶段 4 已确认设计**：单模型、240 条场景、分层判定与评测证据 |
| `docs/specs/2026-09-26-rag-explanation-design.md` | 阶段 3 旧方案，已由上项取代 |
| `docs/plans/2026-09-18-plan-a-supermall-after-sales.md` | 计划 A：supermall 售后能力（8 任务） |
| `docs/plans/2026-09-18-plan-b-mcp-server.md` | 计划 B：MCP Server（6 任务） |
| `docs/plans/2026-09-18-plan-c-agent.md` | 计划 C：决策 + 复核 Agent（6 任务） |
| `docs/plans/2026-09-26-phase3a-rag-refund-review.md` | 阶段 3A：确定性退款编排 + RAG 政策复核（8 任务） |
| `docs/plans/2026-09-26-phase3b-knowledge-qa.md` | 阶段 3B：FAQ + 当前演示商品问答（6 任务，依赖 3A） |
| `docs/plans/2026-10-01-phase4-single-model-evaluation.md` | 阶段 4：单模型评测实施计划（14 任务，待书面审阅） |
| `docs/known-issues.md` | **隐患清单**，见下 |

## 判断「要不要修」的准绳

**本项目的目的是展现架构与技术栈，不是做生产级系统。**

判据全文在 `docs/known-issues.md` 的「取舍准绳」一节，**动手修任何问题之前先读那一节**。简言之：

- **该修**：关乎项目要展示的**架构主张**——同源约定、三道防线、幂等与原子性、复核的结构不可绕过性、工具面收窄
- **不必修**：纯生产硬化且不承载架构叙事——极端边界值守卫、类型系统洁癖、运维工具链补全、跨全仓重构
- **灰色地带：先记录，不要自行决定修或不修**

## 隐患清单纪律

`docs/known-issues.md` **持续更新**。每完成一个任务、每轮审查后，把新发现按既有格式追加：

- 编号 `K-N` **单调递增、不复用**；新条目追加到 K 区**末尾**（「已处理」区之前）
- 状态取值：`待判断` / `修复中` / `待修` / `已处理` / `不处理`
- **已处理的移到「已处理」区保留追溯，不要删**

**例外**：属于**当前任务交付物本身的缺陷**（判据：不修的话这个任务算不算完成），**当场修**，不能只记录。
只有潜在风险才走「记录待判断」。

## 提交约定

| 仓库 | 约定 |
|---|---|
| **本仓库** | 历史文档直接提交到 `main`；当前已配置 `origin`。阶段 3A / 3B 按 SDD 在隔离工作树的 `codex/phase3a-rag-refund-review` / `codex/phase3b-knowledge-qa` 分支实施与审查，现已按用户授权集成到 `main`。后续实现继续使用隔离分支，并按用户选择集成 |
| **supermall** | **有远程，必须开分支**——两个仓库情况不同，处理不同 |

## 当前进度与下一步

**计划 A：Task 1–8 已完成。** Task 7 的实现提交为 supermall `b1ff494`；Task 8 的验证记录见
`supermall/docs/plan-a-task8-validation-2026-09-22.md`。
实现、验证与 K-36 的架构验收均已完成：supermall `bf2d59a` 已使政策条款与当前执行语义对齐。
Task 7 已实现不可变政策目录快照及其指纹；阶段 3A 已加入 Agent 消费者。
阶段 3A Task 1–8 已完成并通过独立 SDD 任务及整分支审查；真实环境证据见
`docs/phase3a-rag-refund-validation-2026-09-28.md`。决策 Agent 保留普通售后对话与只读查询，明确退款请求转接可信编排；同源政策精确检索进入独立复核。根 Maven reactor 修复后 170/170，真实目录 v1→v2→v1 切换、版本失配拒写、无写入降级均通过。
阶段 3B Task 1–6 已完成并通过独立 SDD 任务审查，**整分支审查发现已由 `4af9420` 修复，定向复审与主控最终验收均通过**。阶段 3A / 3B 已按用户授权集成到两个仓库的 `main`，集成验证见 `docs/phase3-main-integration-2026-09-30.md`。32 FAQ、21 条清单商品在本轮真实验证时全部上架，真实 API/MCP/模型与正常退款证据见 `docs/phase3b-knowledge-validation-2026-09-30.md`；最终主控 Maven 240/240、Python 14/14 通过。解释补充仅允许固定的代码定义句；任一最终商品引用复核失败时整次资料答复不可用。一次复核异常未提交退款，后续无动作诊断与新会话单次退款重试通过，旧异常原因未查明；K-52 / K-53 保持待判断，K-54 至 K-57 已处理且独立复审通过。阶段 4 的已确认设计与下一步见下；不要把计划复选框当成已完成证据。
**阶段 4：Task 1–13 已完成；Task14的部分结果工程交付、验证与独立终审已闭环，正式benchmark仍PARTIAL。** 原240 FIRST+24 REPEAT共264 COMPLETE，FIRST地板214 PASS/21 FAIL/4 ERROR/1 SKIPPED、原CLI native1均不改；有限安全样本禁止新增退款行/非法尝试为0，同时rowCount违反10/225、violatingTrials17、正常退款完成24/32/升级人工7，不能推导普遍安全或全部效果成功。原32条/86判据真人来源已由当前用户答复及root可信receipt3dafa985...闭合，旧audit字节保留；不复制Task12真人24，不补原BOUNDARY-021缺失冻结REJECTION判据。F2/F3 source gate已接受；新v3五来源＝两离线记录+三独立FIRST，actual三条结束、CLI/supervisor native0/owned cleanup：FAULT-028、BOUNDARY-043 PASS，BOUNDARY-021 automatic PASS/rawSKIPPED不改，新四项真人由直接答复“全部pass”及root receipt1e4222...确认，另存三条派生PASS。原inner固定时钟Maven numeric exit仍UNOBSERVABLE；同一owned fixture的NEW独立只读Maven验证PID41772/native0单列，不重跑trial。共享账本现306 COMPLETE/672 charge/1,093,786已知tokens/unresolved0，新增仅三trial/五logical模型请求；原分母/错误地板不变，source240不代表新执行240。三条实际执行源码6370b0a、JAR/当时backend4028 dirty loadtest配置/政策边界已绑定；本轮WB修复源码只做离线验证，外部backend target现e8b3...未被本轮使用或重绑定，Agent273/Python202真实native0、unchanged MCP/backend旧回归继承；最终Astra整分支审查提出WB-I1/I2（0Critical/2Important），本轮修复声明注入因果和持久化known-token计量，Agent274/最终Python210/native0证据另列，K61/K62已处理，同任务Spec/Quality与同Astra定向终审APPROVED于功能HEAD5d2f66f/backend25e7（私有报告0e1b7d.../88086bd...）；本次后续文档提交未被Astra审过，root按三文档完整diff与其余11文件SHA不变继承源码gate。部分结果工程交付闭环不宣称完整/成功正式benchmark或普遍效果。报告见 `docs/phase4-single-model-evaluation-2026-10-06.md`；隔离分支不自动合并/推送。
**计划 B：Task 1–6 已完成**（实现 `2e026da`、修复 `0391378`）。Task 6 的真实环境响应与数据库核对见 `docs/plan-b-task6-validation-2026-09-24.md`；34/34 Maven 测试通过。运行验证使用 JDK 22，JDK 17 运行尚未验证。
**计划 C：Task 1–6 已完成**（Task 1–4 为 `62621da`、`e8cd154`、`0656eca`、`72fad2d`）。Task 6 已用新订单完成真实模型、MCP 与退款端到端验证：正常退款、施压、冒充授权、不可退订单、复核强制驳回与恢复均通过；完整证据见 `docs/plan-c-task6-validation-2026-09-25.md`。根 Maven reactor 98/98、启动器测试 5/5 通过；运行验证使用 JDK 22，JDK 17 运行仍未验证。

> ⚠️ **计划里的复选框没有被回填**（53 个全部未勾）——**不要拿它当进度依据**，否则会从 Task 1 重做。

**可靠进度依据是提交历史与验证记录**。阶段 3 主线集成见 `docs/phase3-main-integration-2026-09-30.md`；历史实施分支可追溯：

```bash
git -C D:/sourcecode/supermall log --oneline feat/after-sales-capability
git log --oneline codex/phase3b-knowledge-qa
```

**顺序不能乱：A → B → C。** B 依赖 A 的三个新端点，C 依赖 B 能跑起来。

## 怎么执行这些计划

执行实施计划时，以 **Superpowers SDD 作为唯一编排流程**；模型选择与升级规则见
`docs/agent-routing.md`。在 SDD 的各 Task 中按 Step 验证，以命令的真实输出作为完成依据，
每完成一个 Task 回报结果，并按上面的「隐患清单纪律」处理新发现。
计划中的契约文字和步骤如与实现或磁盘事实不符，先核实差异，再按 SDD 流程处理；
不要照抄未经核对的 javadoc，也不要为满足步骤制造空提交。

## 怎么跑 supermall

**执行三份计划时 supermall 必须处于运行状态。** 启动流程繁琐，权威说明在
`supermall/AGENTS.md` 的「本地测试环境启动」一节。要点：

- **三个必需环境变量**，缺一个就起不来：`MERCHANT_JWT_SECRET`、`MALL_WORKER_ID`、`MALL_DATACENTER_ID`
- MySQL 是 Windows 服务，Redis / RabbitMQ 是 WSL 容器；**WSL 会在最后一条 `wsl.exe` 结束后约 60 秒关掉整个 VM**
- 模型走**任意 OpenAI 兼容端点**（DeepSeek、OpenRouter 均可），配 `MODEL_BASE_URL` / `MODEL_API_KEY` / `MODEL_NAME`
- **凭据一律走环境变量，不入库。** 这是本项目的硬约定
- 本工作区的本地启动变量保存在仓库根目录 `.env`，已被 `.gitignore` 排除。**supermall** 启动时加载后端所需变量；**Agent** 从仓库根目录运行 `python scripts/run_agent.py`，启动器只向 Agent 传入 `MODEL_*`、用户 `SUPERMALL_TOKEN` 和可选 `SUPERMALL_BASE_URL`，不会把后端密钥交给 Agent。用户令牌可由当前进程环境或被忽略的 `agent/target/task6.env`、`agent-token.env` 提供。不要打印或提交这些文件。

### Maven

本机 `mvn` **不在 PATH**。用 IDEA 内置的那个：

```powershell
& "D:\JetBrains\IntelliJ IDEA 2026.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" `
  -pl mall-server -am "-Dtest=XxxTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

两个坑，**都实测踩过**：

1. **PowerShell 下 `-D` 参数必须加引号。** 不加会被拆成两个参数（`-Dsurefire` + `.failIfNoSpecifiedTests=false`），
   Maven 报 `Unknown lifecycle phase ".failIfNoSpecifiedTests=false"`。
2. **必须有 `-am`。** `mall-common` / `mall-security` / `mall-infra` 从未 install 到本地仓库，
   只带 `-pl mall-server` 会直接 `Could not resolve dependencies`。
   **不要**改成「先 `mvn install` 一次」——那会让 `-pl` 从 `~/.m2` 取陈旧构件，改了 `mall-common`
   却忘了重装就**静默跑在旧代码上**。详见 `docs/known-issues.md` 的 K-11。

## 契约文本纪律

**本项目反复出现的失败模式是「文字声称的行为 ≠ 代码实际行为」**（K-3、K-20、K-24、K-25、K-26、K-28、K-29）。
后果具体：这类 VO 经 MCP 传给 Agent，是它判断「要不要重试／怎么向用户解释」的**唯一依据**——
一句与实现不符的话会直接变成给用户的错误解释（例如对一条仍是 `PENDING` 的退款说「已退款」）。

**落地任何 javadoc / 注释 / 契约文本前，先打开对应实现逐条比对，不要照抄权威文本。**
权威文本（计划、任务书、别人的建议）给的是**声称**，不是事实。

## 两个 32 KiB 上限的坑

**Codex 对项目指令文件有 32 KiB（32,768 字节）上限，超出部分被静默丢弃、不报错**
（配置键 `project_doc_max_bytes`；TUI、`/stats`、`codex exec` 都不提示）。

- `supermall/AGENTS.md` 因此于 2026-09-22 拆分：历史进度叙事移到了
  `supermall/docs/progress-and-loadtest-log.md`。**改那份文件前先 `wc -c`。**
- 本文件同样受此限制。

## Development workflow

For implementation plans, use Superpowers SDD as the sole
implementation orchestration workflow.

For model-selection and escalation policy, read:

`docs/agent-routing.md`

Do not create an independent Worker/Reviewer orchestration layer
while SDD is active.
