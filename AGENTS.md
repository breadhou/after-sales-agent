# AGENTS.md

本文件是本仓库面向 Codex、Claude Code 及其他代码代理的协作指南。

> ⚠️ **不要在本仓库新增 `CLAUDE.md`。** Claude Code 默认在「`CLAUDE.md` 与 `AGENTS.md`」之间**二选一**：
> 路径上只要出现 `CLAUDE.md`，`AGENTS.md` 就**不再被读取**。本仓库靠「只有 `AGENTS.md`」来同时服务两个 agent。

## 这个仓库是什么

售后决策与执行 Agent 的**设计与计划仓库**。业务后端是独立的另一个项目
[supermall](https://github.com/breadhou/Supermall)（本地 `D:\sourcecode\supermall`），两者通过 **MCP** 通信，
**本项目不直连它的数据库**——直连会绕过业务规则，而业务规则正是评测的判定依据。

> **计划 A 的代码改动在 supermall；计划 B/C 的代码改动在本仓库**（`mcp-server` 与 `agent`）。计划 B 的 `mcp-server` 已完成 Task 1–6；计划 C 的 Task 1–4 已完成。

| 文档 | 内容 |
|---|---|
| `docs/specs/2026-09-18-after-sales-agent-design.md` | 设计基线：职责边界、三道防线、评测设计 |
| `docs/plans/2026-09-18-plan-a-supermall-after-sales.md` | 计划 A：supermall 售后能力（8 任务） |
| `docs/plans/2026-09-18-plan-b-mcp-server.md` | 计划 B：MCP Server（6 任务） |
| `docs/plans/2026-09-18-plan-c-agent.md` | 计划 C：决策 + 复核 Agent（6 任务） |
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
| **本仓库** | 文档直接提交到 `main`，**不开分支**（无远程、单分支） |
| **supermall** | **有远程，必须开分支**——两个仓库情况不同，处理不同 |

## 当前进度与下一步

**计划 A：Task 1–8 已完成。** Task 7 的实现提交为 supermall `b1ff494`；Task 8 的验证记录见
`supermall/docs/plan-a-task8-validation-2026-09-22.md`。
实现、验证与 K-36 的架构验收均已完成：supermall `bf2d59a` 已使政策条款与当前执行语义对齐。
Task 7 已实现不可变政策目录快照及其指纹；K-13 的 Agent 消费者仍待计划 C 的 RAG 阶段落地。
**计划 B：Task 1–6 已完成**（实现 `2e026da`、修复 `0391378`）。Task 6 的真实环境响应与数据库核对见 `docs/plan-b-task6-validation-2026-09-24.md`；34/34 Maven 测试通过。运行验证使用 JDK 22，JDK 17 运行尚未验证。
**计划 C：Task 1–4 已完成**（`62621da`、`e8cd154`、`0656eca`、`72fad2d`）；K-44 的 Agent 模块 Jackson 版本已对齐，K-46 的执行异常回执已在 Task 4 修复。Task 4 指定测试 19/19，通过根 Maven reactor 测试合计 67/67。下一步实施 Task 5 的 Agent 装配与 CLI；真实退款端到端验证仍在 Task 6。接续见 `docs/plan-c-handoff-2026-09-24.md`。

> ⚠️ **计划里的复选框没有被回填**（53 个全部未勾）——**不要拿它当进度依据**，否则会从 Task 1 重做。

**可靠的进度依据是 supermall 的分支历史**：

```bash
git -C D:/sourcecode/supermall log --oneline main..feat/after-sales-capability
```

**顺序不能乱：A → B → C。** B 依赖 A 的三个新端点，C 依赖 B 能跑起来。

## 怎么执行这些计划

三份计划的头部写着 `REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
superpowers:executing-plans`——那是 Claude Code 的插件技能，**Codex 没有**。没有该技能时，按此等价做法：

1. 打开计划，**按 Step 逐步执行**，一次一个 Task
2. **每一步都跑该步给出的验证命令**，以真实输出作为完成依据——不要凭「代码看起来对」宣布通过
3. 每完成一个 Task 回报用户；发现新问题按上面的「隐患清单纪律」处理
4. **发现计划文本与磁盘事实不符时，先核实、说出事实、把选择权交回，不要照抄。**
   本项目已因此发生过两次：一次是照抄一段 javadoc 会漏掉一个象限，一次是照做会产生一个空提交

## 怎么跑 supermall

**执行三份计划时 supermall 必须处于运行状态。** 启动流程繁琐，权威说明在
`supermall/AGENTS.md` 的「本地测试环境启动」一节。要点：

- **三个必需环境变量**，缺一个就起不来：`MERCHANT_JWT_SECRET`、`MALL_WORKER_ID`、`MALL_DATACENTER_ID`
- MySQL 是 Windows 服务，Redis / RabbitMQ 是 WSL 容器；**WSL 会在最后一条 `wsl.exe` 结束后约 60 秒关掉整个 VM**
- 模型走**任意 OpenAI 兼容端点**（DeepSeek、OpenRouter 均可），配 `MODEL_BASE_URL` / `MODEL_API_KEY` / `MODEL_NAME`
- **凭据一律走环境变量，不入库。** 这是本项目的硬约定
- 本工作区的本地启动变量保存在仓库根目录 `.env`，已被 `.gitignore` 排除。启动时须显式加载到进程环境；不要打印文件内容或提交它。

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
