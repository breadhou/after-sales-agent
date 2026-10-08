# 阶段4主线集成验证（2026-10-09）

用户授权将阶段4两个隔离分支合并到各自main并提交远程。两仓本地和远程main均为已验收分支的祖先，本次使用fast-forward，无冲突、无功能代码改动。

| 仓库 | 合并前main | 集成及测试源码 | 来源分支 |
| --- | --- | --- | --- |
| after-sales-agent | bd51f095fb0044775e97fd1af9c883f9e20137c0 | fa419d0fb57cba6f0625f1373d3c1d3475715700 | codex/phase4-single-model-evaluation |
| supermall | 4941c4cd65e205c5f9384b3f0b8299d24c15d212 | 25e7afb5fbd860bfe10b73ef6a0c09f1f80ac20d | codex/phase4-evaluation-fixtures |

验证在两个主工作区执行，JDK22.0.2、IDEA内置Maven3.9.16；所有下面的最终命令native exit0。

| 验证 | 命令 | 实际结果 |
| --- | --- | --- |
| Agent/MCP根reactor | mvn -o test | 320测试、30类，0失败/错误/跳过；Agent274、MCP46 |
| Agent Python | python -B -m unittest discover -s scripts -p 'test_*.py' -v | 210通过 |
| Backend reactor | mvn -o -pl mall-server -am test | 244测试、39类，0失败/错误/跳过 |
| Backend Python | python -B -m unittest discover -s scripts -p 'test_*.py' -v | 71通过 |

首次Agent Python与Maven并行，40项失败均为缺少EvaluationHarnessTest导出的合成证据（12项before-send、28项counterexamples检查）。这是执行顺序错误：Maven成功生成证据后重新运行Python，210项全部通过；第一次native1和原始日志保留。未修改源码、降低断言或复制旧合成证据代替本次生成结果。之后运行相同检查应先Maven、再Python。

本地未提交的docs/agent-routing.md保持原内容，不纳入本次提交。同名未追踪scripts/evaluation_contract.py原件为空文件，已在私有证据目录备份（SHA256 e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855），再由主线接纳已验收的跟踪版本。后端隔离工作树中其他任务的压测改动及嵌套历史工作树保留，不进入本次发布。

新提交仅更新本集成记录、进度/验证说明；已审功能HEAD5d2f66与机械文档HEADfa419d0、原真实三条运行源码6370保持区分。私有原生命令回执、UTC时间/PID、stdout/stderr SHA及保留证明在阶段4工作树被忽略的.superpowers/sdd/2026-10-01-phase4-single-model-evaluation/main-integration-20261009/；私有评测结果、凭据及账本不提交。

本次未重跑真实模型、264次正式试验或创建业务夹具。原正式FIRST214 PASS/21 FAIL/4 ERROR/1 SKIPPED及CLI1保持PARTIAL，新增三条派生PASS另列，真实共享预算306/672/1093786/unresolved0不变。集成测试通过不代表正式benchmark完全成功、真实支付/JDK17验证或普遍安全证明。阶段4结果见[公开报告](phase4-single-model-evaluation-2026-10-06.md)。
