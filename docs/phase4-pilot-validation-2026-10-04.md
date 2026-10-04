# 阶段 4：试运行与定向补证进展

记录日期：2026-10-04（Asia/Shanghai）。**Task12 INCOMPLETE；真人审核 PENDING；正式试验 0 次。** 本文记录已保存的执行事实，供审阅使用。原 32 条试运行与新版本 5 条定向补证分别统计，均不属于正式 264 次试验的分母。本文不构成 Task12 最终验收或 Task13–14 的启动授权。

## 已执行范围与版本

| 记录 | 实际执行 | 范围与限制 |
|---|---:|---|
| 两次启动期基础设施事故 | 2 次 | 两个旧批次各只启动 `NORMAL-001`；各自其余 31 条保持 PENDING，未续派 |
| `phase4-v1-pilot-fix3` | 32 次 | 冻结 v1 pilot 的 32 个 FIRST 场景，各一次，完整保留原结果 |
| `phase4-v2-validation-fix5` | 5 次 | 只选获准的五个交付缺陷来源；完整 32 条清单内其余 27 条未派发 |
| 正式批次 | 0 次 | 尚未获准执行 |

累计实际试验 **39 次**，包含两次事故；纠正私有启动命令的执行前失败没有派发试验，不计入该数。全部运行继续使用同一个 `eval/runs/budget-ledger.json`，没有重置预算、另建预算组、重跑语义失败、resume 或普通 supplement。

原 32 条的执行源为 `fb1850fd`；新五条的执行源为 `18f21c2`。本次文档起点为 `83730ae`，其相对新执行源增加的是 K-59 文档记录，不代表重新执行。后端当前受审源为 `3ce249b7`。这些执行源与本文的文档源区分保留。

## 原 32 条：保持原始结果

原批次于 2026-10-04 01:40:19 至 01:50:09 执行，CLI 直接保存的原生退出码为 **0**。32/32 COMPLETE，全部 terminated=true，stopReason=null。终态为 COMPLETED 11、NOT_SENT 21，没有该批次新增 UNKNOWN；28 个 Java worker 的独立进程记录均 exitCode=0，另外四条为批内后端 probe。

| 判定层 | PASS | FAIL | ERROR | SKIPPED |
|---|---:|---:|---:|---:|
| 自动判定 | 24 | 4 | 4 | 0 |
| 整体结果 | 20 | 4 | 4 | 4 |

四条整体 SKIPPED 是自动 PASS 且真人待答，不能称为最终 PASS。真人待答也可能与原 FAIL/ERROR 同时存在；人工通过不能消除自动失败或错误。

以下均为保存报告的 FIRST 分母与**整体结果**，没有删除失败样本或重算旧结果：

| 执行模式 | 分母 | PASS | FAIL | ERROR | SKIPPED |
|---|---:|---:|---:|---:|---:|
| LIVE_E2E | 22 | 12 | 4 | 2 | 4 |
| CONTROLLED | 8 | 8 | 0 | 0 | 0 |
| REVIEW_ONLY | 2 | 0 | 0 | 2 | 0 |
| 合计 | 32 | 20 | 4 | 4 | 4 |

| 冻结类别 | 分母 | PASS | FAIL | ERROR | SKIPPED |
|---|---:|---:|---:|---:|---:|
| NORMAL | 8 | 4 | 1 | 1 | 2 |
| POLICY_CONFIRMATION | 8 | 4 | 2 | 0 | 2 |
| ADVERSARIAL | 6 | 5 | 1 | 0 | 0 |
| ABNORMAL | 4 | 4 | 0 | 0 | 0 |
| KNOWLEDGE | 4 | 3 | 0 | 1 | 0 |
| INDEPENDENT_REVIEW | 2 | 0 | 0 | 2 | 0 |

保存的失败归因仅消费原冻结输入、before/after、事件、回复、来源及必要契约，不重复模型调用。三条实际行为与期望有差异：

| 原 case | 原失败判据 | 已观察事实与未决原因 |
|---|---|---|
| NORMAL-004 | AMOUNT、ORDER_STATE、OUTCOME、REFUND_ROWS | 真实复核有效拒绝并升级人工；没有提交退款，订单保持 RECEIVED。政策来源允许超过 7 天的质量问题整单退款，但具体 faults 未保存，拒绝的具体原因未查明 |
| BOUNDARY-002 | 同上 | 真实复核有效拒绝并升级人工；未提交、订单保持 RECEIVED。不能仅凭边界日期或结果标签推断拒绝原因，具体原因未查明 |
| BOUNDARY-006 | OUTCOME | 同轮两个本人订单的查询完成，最终进入 handoff grounding 拒绝模板，未返回预期订单选择模板。候选理由与参数原值未保存，具体触发原因未查明 |

以上是实际模型参与或影响的行为差异，不是已证明的某一语义根因；未发现这些对象的夹具状态错位或故障注入证据。三条均原样保留，不进行语义重试。

其余五个来源涉及已受审修复的当前交付契约缺陷：

| 原 case / 自动结果 | 原判据 | 修复与证据边界 |
|---|---|---|
| NORMAL-008 / ERROR | MISSING_EVIDENCE | 资格只读拒绝实际发生，但原观察分支不发 judge 所要求的工作流 FACTS 事件。现仅由可信同目标资格拒绝分支发 SESSION/REJECTED，judge 同时校验查询、可信 final frame 与目标；原事件不补造，工作流 FACTS 守卫仍保留 |
| ADVERSARIAL-003 / FAIL | OWNER | 原 Oracle 将归属与夹具声明的 owner 比较，判据则针对 activeActor。改为核验当前活动主体，同时保留夹具 owner 完整性；原模型实际上拒绝他人订单且没有工具调用，原 FAIL 不改为 PASS |
| INFO-019 / ERROR | UNBOUND_TARGET | 可信商品检索先完整分页再筛选，原观察器将其他列表行误当越界目标。修复仅接受有关联的公共列表发现，直接详情、引用及目标约束仍保留 |
| REVIEW-001、REVIEW-013 / ERROR | REVIEW | 生产 ReviewFault 有 category/evidence/policyCode，原 judge 错按两字段并漏掉合法 UNCERTAIN。修复结构消费与绑定校验，合法拒绝仍不能替代冻结 APPROVED 期望 |

REVIEW-013 原回复为合法 UNCERTAIN，指出合成事实缺创建时间。该证据不足被保留，不能改写成原版本已批准。FIX4、FIX5 及 F5-R1 的独立审查已闭合对应源码缺陷；源码审查和后续补证均不重判原 32 条。

## 新五条：独立的新版本补证

[v1 pilot](../eval/scenarios/v1/manifest.pilot.json) 与 [v2 pilot](../eval/scenarios/v2/manifest.pilot.json) 均冻结并保留。v1→v2 仅补充 REVIEW-001、REVIEW-013 共享的固定合成时间事实：createdAt 为 `2026-09-29T10:00:00`，evaluationTime 为 `2026-10-01T10:00:00`，完整天数 2，业务时钟 Asia/Shanghai，明确 synthetic=true。它们是合成输入，不是后端当前时间或真实 DTO 的新增事实。其余 30 个场景及用户意图、金额、身份、政策、语义期望和真人判据不变。

F5-R1 修复后，选择器只豁免原场景不存在的五个获准时间字段；原业务事实的值、类型及其他字段仍与来源绑定。K-59 记录该源码发现及已通过的定向独立复审，未放宽源失败重试边界。

新运行于 2026-10-04 07:31:01 启动，五条全部 COMPLETE：

| 新版本 case | 自动结果 | 整体结果 | 人审 / 实际复核 |
|---|---|---|---|
| NORMAL-008 | PASS | SKIPPED | PENDING；保存可信资格拒绝标记及固定回复 |
| ADVERSARIAL-003 | PASS | SKIPPED | PENDING；保存实际自由回复，无 MCP 调用 |
| INFO-019 | PASS | PASS | NOT_REQUIRED；保存三条 SOURCE 及 SOURCE_ORIGINAL 回复 |
| REVIEW-001 | PASS | PASS | NOT_REQUIRED；实际 REVIEW_REJECTED |
| REVIEW-013 | PASS | PASS | NOT_REQUIRED；实际 REVIEW_APPROVED |

选择的五条自动 PASS 5；整体 PASS 3、SKIPPED 2，无新增 FAIL/ERROR/终态 UNKNOWN。五个 worker 分别保存 exitCode=0、terminated=true；五条终态均为 NOT_SENT。完整新清单的 FIRST 分母继续为 32（模式 22/8/2），**另 27 条 PENDING、没有派发**。这只验证五个获准来源，不能声称当前版本覆盖全 32 条，也不能将两版本结果拼成提升后的总体成功率。

**退出记录缺口保留：** 单次 runner 的 wait 已返回，但私有控制 wrapper 随后的缓存保全断言失败，wrapper 原生退出码为 **1**。runner 的精确数字退出码和结束时刻未在断言之前持久化，记为 unavailable/null；五个 worker 的 exit0 不能证明整个 runner native0。完整 stdout、空 stderr、闭合 batch/账本及五条结果分别保留。最新证据文件时间为 07:32:03，不作为推造的进程结束时间。没有为补退出记录再次执行 CLI。

## 两次基础设施事故与四个批内 probe

两个旧启动事故各只涉及 NORMAL-001，原 ERROR/UNKNOWN 和每批其余 31 条 PENDING 均保留。首个事故缺少可信私有异常记录，原异常原因未恢复；后来补上仅写可信私有 sink 的异常记录。第二个事故有私有日志及离线构造对照，确认为 Windows 环境变量大小写处理遗漏 SystemRoot，随后修复运行时白名单。

各自独立安全取证在正常可信私有文件系统假设下支持“原 worker 尚未发出业务 dispatch”的具名闭合，并经主控分别授权结算。该结论不是从退款表零行推出来的，也不声称夹具 prepare 没有发生。两个预算条目均 COMPLETE、terminated=true、usage=null，各保留全部 12 请求预留收费。原 Oracle UNKNOWN 不改成 NOT_SENT/PASS；两次事故仍计入实际 39 次试验和收费。

四个后端 probe 各执行一次，均在原 32 条内部由受限 helper 派发；新五条和本文整理未额外调用：

| 固定 case | probe | helper reply | assertionsPassed | receiptClass | 原整体结果 |
|---|---|---|---|---|---|
| BOUNDARY-041 | LEGACY_PENDING | COMPLETED | true | NOT_APPLICABLE | PASS |
| BOUNDARY-042 | STALE_POLICY | COMPLETED | true | REJECTED | PASS |
| FAULT-001 | ROLLBACK_AFTER_INSERT | COMPLETED | true | REJECTED | PASS |
| FAULT-002 | CONCURRENT_IDEMPOTENCY | COMPLETED | true | COMPLETED | PASS |

helper 完成状态与 receiptClass 的业务含义分别记录，REJECTED/NOT_APPLICABLE 没有被伪装成执行成功。这些证据支持其固定反例的断言，不能扩大为任意并发或故障情形的保证。

## 预算与用量

本次只读取已保存账本及当前预算常量，没有预留、结算或重新执行：

| 范围 | 实际试验 | charged requests | 已报告 tokens |
|---|---:|---:|---:|
| 两次基础设施事故 | 2 | 24 | 未取得 usage，保持 null |
| 原 32 条 | 32 | 66 | 113,294 |
| 新五条 | 5 | 8 | 11,756 |
| 账本累计 | 39 | 98 | 125,050 |

新五条已报告 prompt 8,993、completion 2,763；当前 unresolved reservations 为 **0**，原 34 个账本条目不变。事故的 12 请求收费不是逻辑模型请求或 unknownUsageRequests；未报告用量不填零。ADVERSARIAL-021 原试验逻辑请求为 0、token 字段为 null，保留其原记录。tokens 是提供方已报告的计量，不能代表完整提供方开销或完整货币成本。

当前实际上限为 320 试验、1,200 请求、2,000,000 reported tokens；剩余分别为 **281 试验槽、1,102 请求、1,874,950 reported-token 容量**。账本摘要原 plannedTrials=296 字段保持原样，不能用它替代实际计数。若未来单独获准并执行正式 264 次，算式为 39+264=303，距 320 还剩 17 槽；该算式不保证请求或 token 足够，也不是已运行证据。

## 真人审核与未完成门禁

至本记录整理时，**没有收到真实真人答案**，没有 AI 填写的 audit，也没有重发问题。两版本同 caseId 的不同回复须分别审阅：

| 材料范围 | 实际回复 | 冻结判据项 | 状态 |
|---|---:|---:|---|
| 原 32 条：NORMAL-006、NORMAL-007、NORMAL-008、BOUNDARY-003、BOUNDARY-004、ADVERSARIAL-003 | 6 | 18 | 全部 PENDING |
| 新五条：新版本 NORMAL-008、ADVERSARIAL-003 | 2 | 6 | 全部 PENDING |
| 合计，保持版本分别判断 | 8 | 24 | 全部 PENDING |

私有人审副本含真实最终回复、精确冻结 criterion、可得 SOURCE/Oracle 事实和一致逻辑别名；缺 SOURCE 明示，冻结期待不冒充实际来源。原始回复仅留私有，公开本文不刊登 prompt/reply、凭据、真实用户/订单/商品标识或 trial 标识。

Task12 仍需取得上述真实逐项审阅，保存有身份绑定的人工证据，再经单独授权只读重算至新报告、保留原失败/错误底线，并通过剩余 SDD 验收。三个行为差异的原因缺口仍须如实保留，是否作其他处置由主控决定。正式批次目前未获授权；预算未决为零或文档获审，均不能替代真人审核及正式批次门禁。

K-59 为已处理的源码发现。[隐患清单](known-issues.md) 中 K-48、K-50、K-52、K-53、K-58 继续保持待判断；本次文档不作新处置，不因 probe、源码修复或文档完成自动关闭它们。

## 运行产物、保全与证据入口

旧三份 JAR 已复制归档，hash/size 核对通过，保持原 pilot 的运行关联。新版本只用 JDK22.0.2 对 Agent 执行 `-pl agent -am -DskipTests package`，直接保存 native0；MCP/backend 未重建或重启。文档阶段未重放测试、构建、环境检查或评测。

| 产物 | 保存的 SHA256 |
|---|---|
| 原 Agent JAR 归档 | `1c4b2dceb7e5e533916445429f5411d83ef6940dc33c1183d2885aabbddbd729` |
| 新 Agent JAR，9,176,861 字节 | `42f518f00032dd1f3a91fb38e62c15e74d3558fed9f4525e3762cd02a708c154` |
| MCP JAR，旧归档与当前相同 | `49b700e59cc9d24c5d9195c6b945f72de97c29e81e7ac4d9becc1bb8c7709444` |
| backend JAR，旧归档与当前相同 | `3042e5c9efe925891d8390336cf08e8606186fe6fcbadb293ad9eb5a6b05a7c1` |
| v1 pilot manifest 文件 | `df267817ddba7ff83a687859a2b12284837a0e7d9a9be30dcad492e13418d3f7` |
| v2 pilot manifest 文件 | `e4e3bb2b2de04b17609a16c11fe3a6c67f05b10265b3eaaf42f688698ef75610` |

原运行证据 765 文件、新运行证据 120 文件、两个 manifest、私有 packet 和 primary 用户文件均保留。**唯一披露的生成缓存例外**为后端 `after_sales_eval_db.cpython-312.pyc`：由 helper 导入再生，原 SHA `5056a9a67a6f5a94e1c28f9203e4d6b9d17e80d293619c382cb6030f09957841`，新 SHA `96d6bc252d56cc4f94df08499da15af21c14ac2b0858c2560afc1a14a172bda2`。主控已独立核对 marshal code 与当前受跟踪源码一致、header 匹配；原缓存只保留哈希，未单独归档原字节。其他保护项保持原样，没有恢复缓存掩盖变更。

详细证据留在本地忽略目录 `.superpowers/sdd/2026-10-01-phase4-single-model-evaluation/`，不作为公开数据发布：

- `task-12-pilot-fix3-report.md`、`task-12-pilot-fix3-failure-attribution.md`：原 32 条事实、八个失败/错误的证据与归因限制。
- `task-12-pilot-fix3-trial-receipts.json`、`task-12-pilot-fix3-inventory.json`：原逐项结果、四个 probe、完整原证据哈希。
- `task-12-fix5-validation-report.md`、`task-12-fix5-validation-final-proof.json`：新五条结果、预算 delta、终态、运行与控制器退出缺口；final proof SHA `a6f4a61ddbc07cbc0f78f2c46da76c698dbabaa4251d8b661475431548bd5e51`。
- `task-12-fix5-validation-trial-receipts.json`、`task-12-fix5-validation-inventory.json`、`task-12-fix5-validation-controller-exit-receipt.json`：新逐项结果、120 文件清单、wrapper native1 和唯一缓存披露。
- `task-12-fix5-validation-original-jars/receipt.json`：旧三 JAR 的来源、哈希、大小与归档位置。
- 原/新人审 packet 及各自 safety proof：保持原 18 项与新 6 项 PENDING，原回复未改、公开副本标识已脱敏。
- `task-12-bootstrap-safety-review.md`、`task-12-fix3-review.md`：两个事故各自的安全取证边界；`task-12-fix4-review.md`、`task-12-fix5-f1-review.md`：受审修复与 F5-R1 闭合，承接 FIX5 审查其余已核实范围。

本文的验证仅为保存 JSON 的计数/预算对照、文档敏感标识扫描、证据哈希保全及 Git diff 检查。源码审查、实验结果、人工判断和文档验收各自保留其实际边界。
