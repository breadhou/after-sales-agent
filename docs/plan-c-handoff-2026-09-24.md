# 计划 C 接续记录（2026-09-24）

本记录初建于限额警报收尾，恢复任务后持续更新；只陈述已验证进度和下一步，不代表计划 C 已完成。

## 已完成

- 计划 A Task 1–8、计划 B Task 1–6 已完成；B 的真实环境验证见 `docs/plan-b-task6-validation-2026-09-24.md`。
- 计划 C Task 1：`62621da`，Agent Maven 模块骨架；JDK 22 下 `mvn -q validate` 退出码 0。
- 计划 C Task 2：`e8cd154`，模型配置；先见到缺类编译失败，再运行 `ModelPropertiesTest` 4/4 通过。
- 计划 C Task 3：`0656eca`，复核结论模型和提示词；先见到缺类编译失败，后经独立后端契约核查补上既有退款记录、政策目录与资格解释。`PromptTest` 9 个和 `ReviewVerdictTest` 1 个，共 10/10 通过。提示词现在区分 `PENDING` 与已完成退款，也不把 `eligible=true` 当成退款完成回执。
- K-44：只在 Agent 子 POM 对齐 Jackson；依赖树选中 annotations 2.22、core/databind 2.22.1，Agent 14/14、MCP Server 34/34 测试通过。
- 计划 C Task 4：`72fad2d`，复核与退款唯一通路；K-46 的错误回执与执行异常均按结果不确定处理。红灯测试先因主类缺失而失败，实施后 Task 4 指定测试 19/19、根 Maven reactor 67/67 通过。真实 MCP/后端退款仍待 Task 6。
- 计划 C Task 5：双 Agent 装配、CLI、工具面请求级测试与隔离凭据启动器已完成；根 Maven reactor 81/81、Python 启动器 5/5 通过。仓库根目录使用真实本地配置运行 `exit` 烟测退出码 0，中文提示正常；尚未调用真实模型或执行退款。
- K-45：supermall `9d45f49` 将入库 datasource/RabbitMQ 密码改为严格环境变量占位。独立临时目录 Maven 打包成功；显式注入本地凭据后，18081 端口出现 `Started MallApplication` 和 Hikari 数据库连接。健康端点未验证 200。之后按用户要求停止了本次启动的 8081 进程和 WSL 保活命令，已确认 8081 端口释放。

## 接续顺序

1. **计划 C Task 6**：用独立的新订单验证正常退款与对抗路径，记录工具调用序列并核对数据库，避免幂等路径造成假阳性。四笔新签收单与一笔待支付单已通过业务 API 准备；测试用户 JWT 在被忽略的 `agent/target/task6.env`，不得打印或提交。

K-13 仍为待修：阶段 3 RAG 首项必须实现目录指纹消费者，拉取、比对并在变化时用同次条款快照重建索引。计划 C 当前没有索引，不应提前关闭 K-13。

## 环境与验证

- 两仓库在收尾时：after-sales-agent `main`；supermall `feat/after-sales-capability`，比远端领先 6 个提交。supermall 的未跟踪 `.worktrees/` 为既有内容，未触碰。
- 进入实际测试前确认 supermall、Redis、RabbitMQ 与 MySQL 状态；已停止的进程按两仓 `AGENTS.md` 重新启动，凭据只经环境变量。
- 本工作区已按用户要求创建被 Git 忽略的根目录 `.env`，可保存后端及 `MODEL_*` 启动变量。supermall 启动时加载所需后端变量；Agent 从仓库根目录运行 `python scripts/run_agent.py`，该启动器只向 Agent 传入模型配置、用户令牌及可选后端地址。用户令牌从当前进程环境或被忽略的 `agent/target/task6.env`、`agent-token.env` 读取；不要把整份 `.env` 加载进 Agent 进程，也不要打印或提交凭据。`git check-ignore -v .env` 已验证命中。
- Windows PowerShell 的 Maven 使用 `D:\JetBrains\IntelliJ IDEA 2026.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd`；`-D` 参数加引号。构建使用 JDK 22；JDK 17 运行仍未验证。
- 两仓已提交的代码和文档无待提交改动；最终状态需用 `git status` 再核对。
