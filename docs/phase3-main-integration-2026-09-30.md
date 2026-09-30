# 阶段 3 主线集成验证

日期：2026-09-30（Asia/Shanghai）。用户明确授权两个仓库合并到 main 并推送。阶段 3A / 3B 的实现与 SDD 审查均已完成，本轮执行主线集成、合并结果验证和文档收尾；阶段 4 尚未开始。

## 合并来源

| 仓库 | 合并前 main | 已验收来源 | 本地合并结果 |
|---|---|---|---|
| after-sales-agent | `2d23d19` | `codex/phase3b-knowledge-qa` / `9f0bd28`，包含阶段 3A | `b9ba6bbaae6b5ea0c7fd4770324ab57f8da13bea`，正常 merge |
| supermall | `66f6785` | `codex/phase3a-rag-refund-review` / `878ac93` | `878ac93429fa34ebb166804e44ec52a943919dd4`，fast-forward |

合并前已 fetch 两个 origin；远程 main 均没有新分歧。Agent 保留主线的已批准语料草稿。对运行代码、测试、脚本、受跟踪数据与 Maven 配置逐路径比较，合并结果与对应已验收来源一致。本轮后续提交仅修改文档：主线进度、测试基线、本记录，以及两份历史文档的空白格式。

## 合并结果验证

Luna High 执行完整测试与日志收集，主控独立读取退出码、日志和本次 Surefire XML。使用 `JAVA_HOME=D:\jdks\openjdk-22.0.2`，完整 Maven 命令为：

```powershell
& "D:\JetBrains\IntelliJ IDEA 2026.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" test
```

分别在两个 main 根目录执行；均退出 0、`BUILD SUCCESS`。Agent Python 命令为：

```powershell
python -m unittest scripts.test_run_agent scripts.test_seed_demo_products scripts.test_mcp_stdio_smoke
```

| 仓库 / suite | 模块 | 测试数 | 测试类 | 失败 / 错误 / 跳过 |
|---|---|---:|---:|---|
| after-sales-agent | mcp-server | 46 | 7 | 0 / 0 / 0 |
| after-sales-agent | agent | 194 | 17 | 0 / 0 / 0 |
| **Agent Maven 合计** | | **240** | **24** | **0 / 0 / 0** |
| supermall | mall-common | 10 | 1 | 0 / 0 / 0 |
| supermall | mall-security | 16 | 3 | 0 / 0 / 0 |
| supermall | mall-infra | 6 | 2 | 0 / 0 / 0 |
| supermall | mall-server | 211 | 32 | 0 / 0 / 0 |
| **supermall Maven 合计** | | **243** | **38** | **0 / 0 / 0** |
| **Python** | 指定的三个模块 | **14** | — | **0 / 0 / 0** |

Agent Maven 运行窗口为 20:53:14–20:53:46，supermall 为 20:53:14–20:54:23。本次 XML 按运行启动时间筛选，所有源测试类均有新报告；Agent 目录中另有旧版测试报告，未计入本次数量。Python 退出 0、`OK`。

本轮没有重复真实商品上架或退款写入。原真实环境证据仍见[阶段 3A 验证](phase3a-rag-refund-validation-2026-09-28.md)与[阶段 3B 验证](phase3b-knowledge-validation-2026-09-30.md)。K-52 / K-53 的待判断状态保留；代码测试数量与阶段 4 计划中的业务评测场景数量分别记录。

推送前扫描了 Agent 的 74 个新增或修改文件、37 个新提交以及 supermall 的 28 个文件、34 个新提交；最终文件与提交新增行均未命中实际本地凭据值或 JWT 字面量。检查输出仅含数量和文件名。文档收尾后再次核对运行代码与已验收来源一致，并执行 `git diff --check`。

## 本地资料与续接

- 用户 AGENTS.md 的 SDD 协作约束及合并开始时的完整路由文件已包含在已验收来源中，合并时按规范化文本逐项核对一致。原文件、差异补丁及暂存标识备份在主仓库 `.git/phase3-integration-backup-*` 下。随后路由文件出现用户本地的 Sol 6.1 命名更新，已另外备份并保留为本地未提交修改；本轮集成提交仅包含主控的进度文档。
- 本地令牌、口令、模型密钥与原始验证资料继续留在忽略文件中。将原有演示商品清单按原字节复制到 main 的被忽略 `data/demo-products.local.json`，供主线启动使用；没有覆盖已有文件或创建新商品。
- 保留两个仓库的隔离工作树，以及 3B 工作树内忽略的 `.superpowers/sdd/2026-09-26-phase3b-knowledge-qa/` 进度、审查报告和三份 `merge-*.log`；supermall 原有嵌套 `.worktrees/` 未改动。
- 远程推送结果及最终提交标识在本地 SDD 进度中记录，并以远程 `refs/heads/main` 核对。

下一步讨论阶段 4 的场景、判定规则与预算，再编写实施计划。
