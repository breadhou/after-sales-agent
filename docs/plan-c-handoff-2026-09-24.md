# 计划 C 接续记录（2026-09-24）

本记录初建于限额警报收尾，恢复任务后持续更新。计划 C 已在 2026-09-25 完成，Task 6 的完整证据见 `docs/plan-c-task6-validation-2026-09-25.md`。

## 已完成

- 计划 A Task 1–8、计划 B Task 1–6 已完成；B 的真实环境验证见 `docs/plan-b-task6-validation-2026-09-24.md`。
- 计划 C Task 1：`62621da`，Agent Maven 模块骨架；JDK 22 下 `mvn -q validate` 退出码 0。
- 计划 C Task 2：`e8cd154`，模型配置；先见到缺类编译失败，再运行 `ModelPropertiesTest` 4/4 通过。
- 计划 C Task 3：`0656eca`，复核结论模型和提示词；先见到缺类编译失败，后经独立后端契约核查补上既有退款记录、政策目录与资格解释。`PromptTest` 9 个和 `ReviewVerdictTest` 1 个，共 10/10 通过。提示词现在区分 `PENDING` 与已完成退款，也不把 `eligible=true` 当成退款完成回执。
- K-44：只在 Agent 子 POM 对齐 Jackson；依赖树选中 annotations 2.22、core/databind 2.22.1，Agent 14/14、MCP Server 34/34 测试通过。
- 计划 C Task 4：`72fad2d`，复核与退款唯一通路；K-46 的错误回执与执行异常均按结果不确定处理。红灯测试先因主类缺失而失败，实施后 Task 4 指定测试 19/19、根 Maven reactor 67/67 通过。真实 MCP/后端退款仍待 Task 6。
- 计划 C Task 5：双 Agent 装配、CLI、工具面请求级测试与隔离凭据启动器已完成；根 Maven reactor 81/81、Python 启动器 5/5 通过。仓库根目录使用真实本地配置运行 `exit` 烟测退出码 0，中文提示正常；尚未调用真实模型或执行退款。
- 计划 C Task 6：最终脚本退出码 0。以 N3、A、B、P、R2 五笔订单完成真实模型、MCP 与退款端到端验证；N3/R2 均首次退款成功且客服明确完成，A 由复核业务驳回，B 转人工，P 经资格查证拒绝，R2 的强制驳回与恢复对照均成立。订单快照、stdout、过滤后的 trace 及提示词恢复校验均记录在 `docs/plan-c-task6-validation-2026-09-25.md`。根 Maven reactor 98/98、启动器测试 5/5 通过。
- K-45：supermall `9d45f49` 将入库 datasource/RabbitMQ 密码改为严格环境变量占位。独立临时目录 Maven 打包成功；显式注入本地凭据后，18081 端口出现 `Started MallApplication` 和 Hikari 数据库连接。健康端点未验证 200。之后按用户要求停止了本次启动的 8081 进程和 WSL 保活命令，已确认 8081 端口释放。

## 后续阶段

计划 A、B、C 已全部完成。后续工作应从计划外的阶段 3 RAG 解释层和阶段 4 系统化评测集开始设计。

K-13 仍为待修：阶段 3 RAG 首项必须实现目录指纹消费者，拉取、比对并在变化时用同次条款快照重建索引。计划 C 当前没有索引，不应提前关闭 K-13。

## 环境与验证

- 两仓库在收尾时：after-sales-agent `main`；supermall `feat/after-sales-capability`，比远端领先 6 个提交。supermall 的未跟踪 `.worktrees/` 为既有内容，未触碰。
- 进入实际测试前确认 supermall、Redis、RabbitMQ 与 MySQL 状态；已停止的进程按两仓 `AGENTS.md` 重新启动，凭据只经环境变量。
- 本工作区已按用户要求创建被 Git 忽略的根目录 `.env`，可保存后端及 `MODEL_*` 启动变量。supermall 启动时加载所需后端变量；Agent 从仓库根目录运行 `python scripts/run_agent.py`，该启动器只向 Agent 传入模型配置、用户令牌及可选后端地址。用户令牌从当前进程环境或被忽略的 `agent/target/task6.env`、`agent-token.env` 读取；不要把整份 `.env` 加载进 Agent 进程，也不要打印或提交凭据。`git check-ignore -v .env` 已验证命中。
- Windows PowerShell 的 Maven 使用 `D:\JetBrains\IntelliJ IDEA 2026.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd`；`-D` 参数加引号。构建使用 JDK 22；JDK 17 运行仍未验证。
- 本轮代码、计划与验证文档随 Task 6 验收提交；最终状态以 `git status` 为准。

## 历史记录：2026-09-25 Task 6 首轮实跑后的暂停点

- 本地合成用户原 access JWT 已过期；因无 refresh token/登录口令，在被忽略的 `agent/target/` 用一次性 Java helper 调用 supermall 的 `JwtUtil.generateAccessToken`，新令牌只写入被忽略的 `agent/target/task6.env`。它经过真实 JWT 过滤器与订单归属检查，**没有验证登录流程**；续接时先检查两小时有效期，不可打印令牌。
- 五笔原始订单均属用户 `2103140552634667008`。同 JWT 逐笔资格预检为 N/R/A/B 可退、P 不可退，五笔起初均无退款。真实模型运行后：N `2103140553419001856` 与 R `2103140554333360128` 各首次产生一条 `REFUNDED`、金额 199.99；A `2103141595204423680`、B `2103141595955204096` 仍 `RECEIVED/0`；P `2103140554937339904` 仍 `PENDING/0`。A 的申请被复核驳回，B 决策未申请，P 查询资格后未申请；R 强制复核驳回后恢复提示词、重新打包，在新会话完成退款。提示词源码与 JAR 资源 SHA-256 一致，备份已清理。原始 raw stderr 已删除。
- **Task 6 尚未通过验收**：N 与 R 成功退款后，客服 stdout 仍说等待复核/执行，与数据库和 trace 矛盾。Sol 已在 `agent` 代码及测试中修复回执归一化和 CLI 可信结果覆盖；定向测试从 13 项中 5 项失败到 23/23 通过，根 Maven 测试报告成功。此修复**尚未用新的首次退款订单重测真实模型**，不得复用已退款的 N/R 充当首次执行证据。
- Terra 修订了 Task 6 计划的前置断言、失败恢复、答复检查和安全快照落盘；`docs/known-issues.md` 新增 K-48（trace 无订单 ID，待判断）。首次运行时快照只显示在工具终端，未落盘；后续计划脚本已改为写入被忽略的 `agent/target/task6-state-snapshots.txt`。现有 `agent/target/task6-run.ps1` 是改动前生成的临时副本，**续接时须从最新计划重新生成**。
- 用户要求额度低时收尾。没有创建 N2/R2，也没有在暂停指令后执行任何业务写入。下一步先检查并验收 Sol 的未提交修复及最终测试；通过正常业务 API 新建至少两笔同用户 `RECEIVED/eligible=true/refundExists=false` 订单用于 N/R 首次执行复测，并在最终代码上复测仍无退款的 A/B/P。需要商家身份完成发货/送达，目前未找到商家登录材料；可评估已有 `MerchantJwtUtil` 的本地合成令牌方案，仍须走正常业务 API，不可直写数据库。按新计划持久化前后快照、stdout、trace，生成 `docs/` 验证记录，再决定提交。
- 收尾时已停止本轮启动的 supermall Java 进程、Redis/RabbitMQ 容器、WSL 保活进程和 MySQL80 服务；下次按两仓 `AGENTS.md` 重启。代码、计划、K-48 与本交接记录目前均为**未提交改动**，本轮没有 push。
