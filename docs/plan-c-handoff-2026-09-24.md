# 计划 C 接续记录（2026-09-24）

用户收到限额警报后停止启动新任务。本记录只陈述已验证进度和下一步，不代表计划 C 已完成。

## 已完成

- 计划 A Task 1–8、计划 B Task 1–6 已完成；B 的真实环境验证见 `docs/plan-b-task6-validation-2026-09-24.md`。
- 计划 C Task 1：`62621da`，Agent Maven 模块骨架；JDK 22 下 `mvn -q validate` 退出码 0。
- 计划 C Task 2：`e8cd154`，模型配置；先见到缺类编译失败，再运行 `ModelPropertiesTest` 4/4 通过。
- 计划 C Task 3：`0656eca`，复核结论模型和提示词；先见到缺类编译失败，后经独立后端契约核查补上既有退款记录、政策目录与资格解释。`PromptTest` 9 个和 `ReviewVerdictTest` 1 个，共 10/10 通过。提示词现在区分 `PENDING` 与已完成退款，也不把 `eligible=true` 当成退款完成回执。
- K-45：supermall `9d45f49` 将入库 datasource/RabbitMQ 密码改为严格环境变量占位。独立临时目录 Maven 打包成功；显式注入本地凭据后，18081 端口出现 `Started MallApplication` 和 Hikari 数据库连接。健康端点未验证 200。之后按用户要求停止了本次启动的 8081 进程和 WSL 保活命令，已确认 8081 端口释放。

## 接续顺序

1. **K-44 已处理**：只在 `agent/pom.xml` 导入 `jackson-bom:2.22.1` 并覆盖父 POM 的 databind 版本。依赖树选中 annotations 2.22、core/databind 2.22.1；Agent 14/14、MCP Server 34/34 测试通过，未改父 POM。
2. **计划 C Task 4**：先修 K-46。计划中的 `RefundExecutor` 直接返回 MCP `resultText()`，未处理 null、`isError=true`、空回执或 SDK 抛出的异常；必须失败关闭，`RefundRequestTools` 在执行结果不确定时提示人工核实，不得说成成功或确定失败。测试覆盖错误回执和调用抛异常，再按 Task 4 的其它步骤实施、验证和审查唯一退款执行通路。
3. 继续 Task 5–6。Task 5 的配置占位符、MCP jar 相对路径和执行结果语义需结合实际启动目录检查；Task 6 应用新订单验证正常退款与对抗路径，核对数据库，避免幂等路径造成假阳性。

K-13 仍为待修：阶段 3 RAG 首项必须实现目录指纹消费者，拉取、比对并在变化时用同次条款快照重建索引。计划 C 当前没有索引，不应提前关闭 K-13。

## 环境与验证

- 两仓库在收尾时：after-sales-agent `main`；supermall `feat/after-sales-capability`，比远端领先 6 个提交。supermall 的未跟踪 `.worktrees/` 为既有内容，未触碰。
- 进入实际测试前确认 supermall、Redis、RabbitMQ 与 MySQL 状态；已停止的进程按两仓 `AGENTS.md` 重新启动，凭据只经环境变量。
- 本工作区已按用户要求创建被 Git 忽略的根目录 `.env`，存放本地启动的五个环境变量；应用本身不会自动加载该文件，启动进程须先显式读取。不要打印或提交其内容。`git check-ignore -v .env` 已验证命中。
- Windows PowerShell 的 Maven 使用 `D:\JetBrains\IntelliJ IDEA 2026.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd`；`-D` 参数加引号。构建使用 JDK 22；JDK 17 运行仍未验证。
- 两仓已提交的代码和文档无待提交改动；最终状态需用 `git status` 再核对。
