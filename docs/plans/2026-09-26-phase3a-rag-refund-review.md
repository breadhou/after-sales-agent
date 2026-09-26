# 阶段 3A：确定性退款编排与 RAG 政策复核 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 对话 Agent 只转接明确退款诉求；可信代码确认并核定资格；同源政策检索进入唯一审批 Agent；通过后才由 supermall 执行退款。

**Architecture:** supermall 提供资格和写入时可核对的政策指纹，MCP 透传这两个契约。Agent 的转接工具无写入能力；确认后的可信编排按“事实硬门槛 → 政策精确检索 → 独立复核 → 后端执行”串行运行，所有结果用可信回复覆盖模型文本。

**Tech Stack:** Java 17 编译 / 本机 JDK 22 运行 / Spring Boot + MyBatis Plus（supermall）/ LangChain4j 1.20.0 + MCP（after-sales-agent）/ Maven / Python 启动器。

**Spec:** `docs/specs/2026-09-26-rag-policy-review-design.md`。实施前先读规格；计划 C Task 1–6 是已验收基线，不重做。

## Global Constraints

- 两个仓库分别改动：supermall 的后端代码从 `feat/after-sales-capability` 起步并使用独立分支或 worktree；after-sales-agent 的 MCP/Agent 代码另用独立分支或 worktree。不要把 supermall 的未跟踪 `.worktrees/` 当成可清理对象。
- Agent 只经用户 JWT 和 MCP 调 supermall；不直连数据库，不向 Agent 传后端或商家密钥。`.env`、用户 token 和演示环境材料保持 Git 忽略，不写进测试输出。
- 退款资格、金额、所有权、状态、幂等由后端决定；RAG 复核只有否决/转人工权。政策按 `policyCode` 精确检索，同次目录快照的 `fingerprint` 必须等于资格 `catalogFingerprint`；FAQ/商品文本不能进入复核。
- 新 Agent 路径的 MCP `submit_refund` 必填 `expectedCatalogFingerprint` 与 `expectedPolicyCode`；supermall 直接 HTTP 的原有 reason-only 请求继续可用。两个预期字段在 HTTP 中要么都省略、要么一起提供。版本与政策码校验只拦新退款写入，既有退款和唯一键冲突赢家继续返回幂等回执。
- 同会话同订单复核驳回后终止自动申请；人工升级仅记录并请用户联系人工客服。模型、MCP 或政策目录不可用时不得自动执行或声称已退款。
- PowerShell 每个终端先设 `$Mvn = 'D:\JetBrains\IntelliJ IDEA 2026.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd'`。所有 `-D` 参数加引号；supermall 指定 `mall-server` 测试必须带 `-am`，避免依赖本地旧构件。代码探索/测试/日志交 Luna，常规实现交 Terra，复杂门槛和安全逻辑交 Sol；主代理逐任务验收。

## Review Focus

以下输入由所属任务的测试锁定，不能留给最后的真实环境验收：

1. “不要退款”或模型误触发转接：Task 6 只展示待确认信息；没有明确确认命令时复核和写入均为零。
2. 超范围订单号、确认订单/理由与待确认项不一致或旧会话确认：Task 6 清除或拒绝待确认项，不向模型求“二次确认”。
3. 空理由、超过 512 字符的理由及包含换行/角色指令的理由：Task 6 在提交前拒绝或按原文安全保存，不把文本当命令或写进通用 trace。
4. 目录错误、重复 code、旧索引可用但本轮刷新失败：Task 4 失败关闭；不能用缓存或相似条款放行。
5. 复核后政策版本切换与已存在退款重试：Task 2 只拒绝新写入，保留幂等返回；Task 8 验证跨进程链路。

## File Map

| 交付单元 | 文件及职责 |
|---|---|
| 后端同源目录 | supermall `mall-server/src/main/java/com/mall/module/order/service/AfterSalesPolicyCatalog.java` 新建唯一目录构造入口；现有 `PolicyCatalogVO` 保留快照和哈希 |
| 后端契约 | supermall 的 `RefundEligibilityVO`、`RefundEligibilityServiceImpl`、`RefundReasonDTO`、`RefundExecutionServiceImpl`、`OrderController` 分别传资格时的订单状态与目录指纹、接可选预期指纹并在新写入前比较 |
| MCP 契约 | `mcp-server/src/main/java/com/mall/agent/mcp/tools/RefundTools.java` 白名单透传资格指纹、状态和写入预期；`mcp-server/src/main/java/com/mall/agent/mcp/McpServerMain.java` 收紧 schema/参数 |
| Agent 可信事实 | `agent/src/main/java/com/mall/agent/tools/RefundReviewContextFactory.java` 增加后端资格、订单 ID、状态和金额硬门槛；`agent/src/main/java/com/mall/agent/policy/PolicyCatalogConsumer.java` 维护目录代次 |
| Agent 对话/执行 | `agent/src/main/java/com/mall/agent/tools/RefundHandoffTools.java` 只记录候选；`agent/src/main/java/com/mall/agent/flow/ConversationCoordinator.java` 管确认与可信回复；`agent/src/main/java/com/mall/agent/flow/RefundWorkflow.java` 串联复核与执行 |
| Agent 复核 | `RefundReviewContext`、`ReviewVerdict`、`review-system.txt`、`AgentConfig`、`RefundExecutor` 传政策证据和结构化结论 |

---

### Task 1: supermall 资格结果携带同源目录指纹

**Files:**
- Create: supermall `mall-server/src/main/java/com/mall/module/order/service/AfterSalesPolicyCatalog.java`
- Modify: supermall `mall-server/src/main/java/com/mall/module/order/controller/AfterSalesPolicyController.java`、`mall-server/src/main/java/com/mall/module/order/entity/vo/RefundEligibilityVO.java`、`mall-server/src/main/java/com/mall/module/order/service/impl/RefundEligibilityServiceImpl.java`
- Test: supermall `mall-server/src/test/java/com/mall/module/order/controller/AfterSalesPolicyControllerTest.java`、`mall-server/src/test/java/com/mall/module/order/service/impl/RefundEligibilityServiceImplTest.java`

**Interfaces:** Produce `AfterSalesPolicyCatalog.currentSnapshot(): PolicyCatalogVO` and additive `RefundEligibilityVO.catalogFingerprint` and `orderStatus` (from the same order row used to resolve `policyCode`). Move the existing `AfterSalesPolicy.values()` → clause mapping from the controller into this one provider; controller, eligibility and Task 2 execution call it.

- [ ] **Step 1: Write failing tests.** Name `eligibleAndIneligibleResultsCarryCurrentCatalogFingerprint` and `policyEndpointAndEligibilityUseSameSnapshot`. Assert that eligible, denied and existing-refund responses expose the same lowercase 64-character fingerprint as `currentSnapshot()` and the status of the order row read by eligibility; changed text or enum order changes the digest, while previously returned snapshot objects remain unchanged.

```java
assertEquals(catalog.currentSnapshot().getFingerprint(), eligibility.getCatalogFingerprint());
assertEquals(64, eligibility.getCatalogFingerprint().length());
assertEquals(order.getStatus(), eligibility.getOrderStatus());
```
- [ ] **Step 2: Run the focused tests to confirm RED.** From supermall root: `& $Mvn -pl mall-server -am '-Dtest=AfterSalesPolicyControllerTest,RefundEligibilityServiceImplTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`. Expected: the new fingerprint assertions fail, with no missing reactor dependency.
- [ ] **Step 3: Implement the single catalog provider and populate `catalogFingerprint` and `orderStatus` on every successful eligibility response path.** Keep `PolicyCatalogVO.of(List<PolicyClauseVO>)` as the only hashing function. Do not recompute a digest from a second enumeration in the eligibility service.
- [ ] **Step 4: Run the focused tests to GREEN** with the Step 2 command, then run `& $Mvn -pl mall-server -am '-Dtest=RefundEligibilityServiceImplTest,AfterSalesPolicyControllerTest,RefundExecutionServiceImplTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`. Expected: all selected tests pass.
- [ ] **Step 5: Commit** only Task 1 files in the supermall implementation branch: `git commit -m "feat: share after-sales policy catalog with eligibility"`.

### Task 2: supermall 新写入校验复核使用的政策版本

**Files:**
- Create: supermall `mall-server/src/main/java/com/mall/module/order/service/RefundEligibilityEvaluator.java`
- Modify: supermall `mall-common/src/main/java/com/mall/common/enums/ResultStatus.java`、`mall-server/src/main/java/com/mall/module/order/entity/dto/RefundReasonDTO.java`、`mall-server/src/main/java/com/mall/module/order/controller/OrderController.java`、`mall-server/src/main/java/com/mall/module/order/service/RefundExecutionService.java`、`mall-server/src/main/java/com/mall/module/order/service/impl/RefundEligibilityServiceImpl.java`、`mall-server/src/main/java/com/mall/module/order/service/impl/RefundExecutionServiceImpl.java`
- Test: supermall `mall-server/src/test/java/com/mall/module/order/service/impl/RefundExecutionServiceImplTest.java`、`mall-server/src/test/java/com/mall/module/order/service/impl/RefundEligibilityServiceImplTest.java`、`mall-server/src/test/java/com/mall/module/order/controller/OrderControllerRefundTest.java`

**Interfaces:** Preserve `RefundEligibilityVO execute(Long orderId, String reason)` for existing callers. Add `RefundEligibilityVO execute(Long orderId, String reason, String expectedCatalogFingerprint, String expectedPolicyCode)`; both expectation fields are optional **as a pair** for direct legacy HTTP, while Task 3's MCP requires both. `RefundEligibilityEvaluator.assess(Order order): Assessment` returns an immutable `(orderStatus, refundableAmount, policy)` derived from the order's current fields and the existing `AfterSalesPolicy.resolve` rule; both read eligibility and locked-order execution call it, so status/time-window logic stays in one place. Keep `RefundReasonDTO(String reason)` explicitly when adding fields, because the existing controller test constructs this DTO with one argument. Add `ResultStatus.REFUND_REVIEW_STALE(50005, ...)` for a definite catalog/policy precondition failure before any new write. Use Task 1's `currentSnapshot()` inside the write transaction.

- [ ] **Step 1: Write failing tests** named `readAndLockedWriteUseSamePolicyEvaluator`, `rejectsNewRefundWhenExpectedCatalogFingerprintChanged`, `rejectsNewRefundWhenReviewedPolicyCodeChanged`, `returnsExistingRefundDespiteOldFingerprint`, `staleReadViewStillFindsConcurrentPendingWinner`, `uniqueKeyLoserStillReturnsWinner`, `lockedOrderChangeBlocksNewWrite` and `reasonOnlyHttpRemainsValid`. Assert no insert/status change and business code 50005 when catalog or locked-order policy no longer matches the reviewed pair; a locking read sees a concurrently committed `PENDING` winner despite an older repeatable-read view and returns the idempotent pending receipt even with a stale pair; duplicate-key conflict still returns its winner; a changed locked status, owner or amount blocks a new write; a legacy body with only `reason` and the existing `new RefundReasonDTO(reason)` call still compile and reach the service. HTTP with only one expectation field is rejected as invalid.

```java
assertEquals(50005, staleReviewException.getStatus().getCode());
verify(refundMapper, never()).insert(any());
assertEquals("该订单已有退款申请在处理中", concurrentWinner.getReason());
assertEquals("测试理由", new RefundReasonDTO("测试理由").getReason());
```
- [ ] **Step 2: Run RED:** `& $Mvn -pl mall-server -am '-Dtest=RefundExecutionServiceImplTest,RefundEligibilityServiceImplTest,OrderControllerRefundTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`. Expected: new signature/field or behavior tests fail.
- [ ] **Step 3: Implement the optional DTO fields and service overload.** Update `RefundReasonDTO` Javadoc from “只有一个字段” to the actual reason-plus-optional-preconditions contract while preserving the rule that clients cannot set amount. Extract the existing policy resolution and amount calculation into `RefundEligibilityEvaluator.assess(Order)`; use it in eligibility reads and on the **locked order**, without a second copy of time-window logic. After locking the order, use `RefundMapper.selectByOrderIdForUpdate` as the current read of an existing refund **before** checking a supplied expectation; an ordinary `selectOne` may be pinned to an older InnoDB read view. For a genuinely new row, verify locked order ownership and re-resolve the current status, policy and amount through the shared evaluator; require the actual locked policy code to equal `expectedPolicyCode` when supplied, then compare the catalog fingerprint immediately before insert. Reject either mismatch with code 50005. Preserve the unique-key catch and its locking-read winner path for races after the first read. Never turn a timeout into a definite “not refunded”.
- [ ] **Step 4: Run GREEN** with the Step 2 command and then the Task 1+2 test set. Expected: all pass, including old reason-only controller tests.
- [ ] **Step 5: Commit** Task 2 files in supermall: `git commit -m "feat: guard new refund writes by reviewed policy version"`.

### Task 3: MCP 透传资格与执行指纹

**Files:**
- Modify: `mcp-server/src/main/java/com/mall/agent/mcp/tools/RefundTools.java`、`mcp-server/src/main/java/com/mall/agent/mcp/McpServerMain.java`、`scripts/mcp_stdio_smoke.py`
- Test: `mcp-server/src/test/java/com/mall/agent/mcp/tools/RefundToolsTest.java`、`mcp-server/src/test/java/com/mall/agent/mcp/ToolSchemaTest.java`、`mcp-server/src/test/java/com/mall/agent/mcp/McpProtocolTest.java`、`mcp-server/src/test/java/com/mall/agent/mcp/FakeSupermall.java`

**Interfaces:** `RefundTools.submitRefund(Long orderId, String reason, String expectedCatalogFingerprint, String expectedPolicyCode): String` posts `{reason, expectedCatalogFingerprint, expectedPolicyCode}`. `getRefundEligibility` passes through `catalogFingerprint` and `orderStatus`. MCP `submit_refund` schema requires exactly `orderId`, `reason`, `expectedCatalogFingerprint` and `expectedPolicyCode`; no extra properties. Smoke CLI adds `--expected-fingerprint` and `--expected-policy-code` for submit. Preserve the structured MCP error text `{code,message}` from `SupermallClient`, including business code 50005; transport/HTTP failures keep distinct negative codes.

- [ ] **Step 1: Write failing protocol and tool tests** named `eligibilityRetainsCatalogFingerprint`, `submitRequiresReviewedPair`, `submitPostsReviewedPair` and `staleReviewCodeSurvivesMcp`. Assert eligibility preserves fingerprint, policy code and source `orderStatus`; missing/blank fingerprint or policy code returns MCP argument error without HTTP POST, both exact values reach the fake supermall body, and HTTP business code 50005 reaches Agent as `{code:50005,message}` with `isError=true`. Update the smoke script's six-tool schema expectation and test both new CLI arguments/payload, so the live submit command remains usable.

```java
assertEquals("SHIPPED", eligibilityJson.get("orderStatus").asText());
assertEquals("SHIPPED_NOT_RECEIVED", postedJson.get("expectedPolicyCode").asText());
assertEquals(50005, mcpErrorJson.get("code").asInt());
```
- [ ] **Step 2: Run RED:** `& $Mvn -pl mcp-server '-Dtest=RefundToolsTest,ToolSchemaTest,McpProtocolTest' test`. Expected: the new field and schema assertions fail.
- [ ] **Step 3: Implement the schema, parser/dispatch and HTTP body change.** Keep `SupermallClient` Result unwrap/error behavior and do not expose credentials or raw HTTP failures in MCP text.
- [ ] **Step 4: Run GREEN** with the Step 2 command, then `& $Mvn -pl mcp-server test` and `python -m py_compile scripts/mcp_stdio_smoke.py`. Expected: MCP tests pass and the updated smoke script parses. Its live run belongs to Task 8, where a running backend and user token are available.
- [ ] **Step 5: Commit** Task 3 files in after-sales-agent: `git commit -m "feat: carry policy fingerprint through refund MCP tools"`.

### Task 4: Agent 政策目录消费者与精确检索

**Files:**
- Create: `agent/src/main/java/com/mall/agent/policy/PolicyCatalogConsumer.java`、`agent/src/main/java/com/mall/agent/policy/CatalogSnapshot.java`、`agent/src/main/java/com/mall/agent/policy/PolicyEvidence.java`
- Test: `agent/src/test/java/com/mall/agent/policy/PolicyCatalogConsumerTest.java`

**Interfaces:** `PolicyCatalogConsumer.refresh(): CatalogSnapshot` validates one `list_policy_clauses` response and atomically installs `{fingerprint, documents}`. `requireMatching(CatalogSnapshot snapshot, String eligibilityFingerprint, String policyCode): PolicyEvidence` pins **this request's** refreshed snapshot, matching fingerprint and code exactly even if another request refreshes later. Both fail with nested `PolicyCatalogConsumer.PolicyCatalogException`; `PolicyEvidence` contains fingerprint, code, title and original clause text. Task 7 injects it into review; phase 3B uses `refresh()` for general policy questions.

- [ ] **Step 1: Write failing tests** named `switchesCompleteGenerationWhenFingerprintChanges`, `rejectsDuplicateOrUnknownCode`, `failedRefreshNeverReturnsOldEvidence` and `orderChangeProducesNewGeneration`. Assert one response supplies both fingerprint and clauses, a failed refresh makes this request unusable even if an older snapshot is cached, and policy lookup never falls back to semantic similarity.

```java
assertNotEquals(first.fingerprint(), second.fingerprint());
assertThrows(PolicyCatalogConsumer.PolicyCatalogException.class,
        () -> consumer.requireMatching(second, first.fingerprint(), "SHIPPED_NOT_RECEIVED"));
assertThrows(PolicyCatalogConsumer.PolicyCatalogException.class, consumer::refresh); // malformed later response
```
- [ ] **Step 2: Run RED:** `& $Mvn -pl agent '-Dtest=PolicyCatalogConsumerTest' test`. Expected: class/method missing or assertions fail.
- [ ] **Step 3: Implement a synchronized refresh and immutable snapshot swap.** Reject MCP `isError`, null/empty text, malformed JSON, blank fingerprint, duplicate codes and empty title/body. Do not pass fetched policy text to the decision Agent.
- [ ] **Step 4: Run GREEN:** Step 2 command plus `& $Mvn -pl agent '-Dtest=PolicyCatalogConsumerTest,AgentConfigTest' test`. Expected: all pass.
- [ ] **Step 5: Commit** Task 4 files: `git commit -m "feat: consume versioned policy catalog for review"`.

### Task 5: 可信代码在复核前核定订单和资格

**Files:**
- Modify: `agent/src/main/java/com/mall/agent/tools/RefundReviewContextFactory.java`
- Create: `agent/src/main/java/com/mall/agent/model/CheckedRefundFacts.java`
- Test: `agent/src/test/java/com/mall/agent/tools/RefundReviewContextFactoryTest.java`

**Interfaces:** Add `CheckedRefundFacts requireEligible(Long orderId, String reason, String originalUserRequest)`; it contains validated order/eligibility facts, policyCode, catalogFingerprint, amount, original input and candidate action. Return facts only if eligible. Throw nested `RefundReviewContextFactory.RefundNotEligibleException` with the verified backend reason for a known denial, or nested `RefundReviewContextFactory.RefundFactsUnavailableException` for inconsistent/missing/failed reads. Task 7 uses it; only Task 4 resolves policy evidence. Keep an adapter for existing tests until Task 7 removes the old `request_refund` path.

- [ ] **Step 1: Write failing tests** named `rejectsIneligibleBeforeReviewer`, `rejectsExistingRefundBeforeReviewer`, `rejectsMismatchedAmountOrOrderStatus`, `rejectsStatusChangeBetweenOrderAndEligibilityReads` and `rejectsWrongOrderIdAndMalformedFacts`. Assert `eligible=true && refundExists=false`, matching positive amount and `get_order.status == get_refund_eligibility.orderStatus` are necessary, and no reviewer callback can be reached on failure. Simulate `SHIPPED` from one read and a different status/policy from the next; fail closed instead of combining them.

```java
assertThrows(RefundReviewContextFactory.RefundFactsUnavailableException.class,
        () -> factory.requireEligible(orderId, reason, rawInput)); // status changed between reads
verifyNoInteractions(reviewer, executor);
```
- [ ] **Step 2: Run RED:** `& $Mvn -pl agent '-Dtest=RefundReviewContextFactoryTest' test`. Expected: new hard-gate tests fail.
- [ ] **Step 3: Implement `requireEligible` using fresh `get_order` and `get_refund_eligibility` calls under the same user JWT.** Validate MCP error flags, JSON types, requested/returned order IDs, `orderStatus` equality and amounts; treat cross-read status disagreement as unknown facts. Do not duplicate the backend's policy-to-status resolver in Agent. Use the backend reason for known denial and a separate unknown-result state for transport/parse failure. Do not infer authorization from the model's candidate or trace.
- [ ] **Step 4: Run GREEN:** Step 2 command plus `& $Mvn -pl agent '-Dtest=RefundReviewContextFactoryTest,RefundRequestToolsTest' test`. Expected: all pass.
- [ ] **Step 5: Commit** Task 5 files: `git commit -m "feat: enforce refund eligibility before model review"`.

### Task 6: 对话 Agent 只转接，用户确定性确认

**Files:**
- Create: `agent/src/main/java/com/mall/agent/tools/RefundHandoffTools.java`、`agent/src/main/java/com/mall/agent/flow/ConversationCoordinator.java`、`agent/src/main/java/com/mall/agent/model/PendingRefund.java`、`agent/src/main/java/com/mall/agent/model/RefundRequest.java`
- Modify: `agent/src/main/java/com/mall/agent/config/AgentConfig.java`、`agent/src/main/java/com/mall/agent/AgentMain.java`、`agent/src/main/java/com/mall/agent/tools/EscalationTools.java`、`agent/src/main/resources/prompts/decision-system.txt`
- Test: `agent/src/test/java/com/mall/agent/config/AgentConfigTest.java`、`agent/src/test/java/com/mall/agent/AgentMainTest.java`、`agent/src/test/java/com/mall/agent/flow/ConversationCoordinatorTest.java`、`agent/src/test/java/com/mall/agent/agent/PromptTest.java`

**Interfaces:** `RefundHandoffTools.handoffRefund(Long orderId, String reason)` only stores a candidate. `ConversationCoordinator.handleTurn(String sessionId, String rawInput): String` owns a pending order-selection state and intercepts `/select-refund-order <orderId>` and `/confirm-refund <orderId>` before invoking any model; accepted confirmation calls an injected `BiFunction<String, RefundRequest, String>` (Task 7), passing the session ID separately. `RefundRequest` is `(Long orderId, String reason, String originalUserRequest)`. `askRefundEligibility(Long orderId)` is another no-action marker answered by trusted code; phase 3B adds the explanation marker.

- [ ] **Step 1: Write failing tests** named `decisionToolsExcludeLegacyRefundAndPolicy`, `unconfirmedHandoffNeverCallsWorkflow`, `modelRememberedOrderNeedsExplicitUserSelection`, `confirmationBindsSessionOrderAndReason`, `eligibilityQuestionUsesTrustedReply`, `escalationOverridesFalseModelSuccess` and `modelCannotClaimRefundCompletedDuringHandoff`. Assert a mistaken handoff for “不要退款”, stale session or mismatched ID/reason never calls the injected workflow; model-proposed historical ID from “退这单” is ignored until the user explicitly chooses an ID from this session's trusted order list and then confirms; blank/over-512-character reason and overflow ID are refused before confirmation; raw role text is data, never a command. An escalation record in this turn forces “已记录，请联系人工客服” even if model text says “退款已完成”. Model-visible tools exclude old `request_refund`, `submit_refund`, `get_refund_eligibility` and `list_policy_clauses`.

```java
assertEquals(0, workflowCalls.get()); // model handoff alone cannot execute
assertFalse(coordinator.handleTurn(sessionId, "退这单").contains("已退款"));
assertEquals(0, workflowCalls.get()); // model remembered ID is not a user selection
assertEquals("已记录，请联系人工客服", escalationTurnReply);
```
- [ ] **Step 2: Run RED:** `& $Mvn -pl agent '-Dtest=AgentConfigTest,AgentMainTest,ConversationCoordinatorTest,PromptTest' test`. Expected: new tool-surface and confirmation tests fail.
- [ ] **Step 3: Implement the pending state and trusted response priority.** Accept an order ID only when it appears explicitly in this turn's original user input, or when the user chooses it with `/select-refund-order <orderId>` from a code-listed order set for this session; never use the model's remembered ID as proof of selection. If no ID is explicit, show the trusted order list and wait in a distinct selection state, without creating a confirmable refund. Only after selection, display the selected ID and exact reason with `/confirm-refund <orderId>`. Confirmation is parsed by code, bound to session + reason, and clears pending state on use/cancel/change. Observe `EscalationTools` records per turn and give escalation, refund and qualification markers a code-owned reply before any model text; never let an escalation-only turn quote model claims of execution. A nonrefund turn keeps existing conversation, order and logistics behavior. Remove `RefundRequestTools` from decision model's tools; retain its current rejection semantics for migration into Task 7.
- [ ] **Step 4: Run GREEN:** Step 2 command, then `python -m unittest scripts.test_run_agent`. Expected: Java and launcher tests pass; launcher still forwards only whitelisted model/user-token settings.
- [ ] **Step 5: Commit** Task 6 files: `git commit -m "feat: require trusted confirmation for refund handoff"`.

### Task 7: RAG 复核成为唯一 Agent 退款审批通路

**Files:**
- Create: `agent/src/main/java/com/mall/agent/flow/RefundWorkflow.java`、`agent/src/main/java/com/mall/agent/model/ReviewFault.java`
- Modify: `agent/src/main/java/com/mall/agent/model/RefundReviewContext.java`、`agent/src/main/java/com/mall/agent/model/ReviewVerdict.java`、`agent/src/main/java/com/mall/agent/config/AgentConfig.java`、`agent/src/main/java/com/mall/agent/tools/RefundExecutor.java`、`agent/src/main/resources/prompts/review-system.txt`、`agent/src/main/java/com/mall/agent/AgentMain.java`
- Remove after migration: `agent/src/main/java/com/mall/agent/tools/RefundRequestTools.java`; migrate its per-session rejection and in-review concurrency tests into `RefundWorkflowTest.java` before deletion
- Test: `agent/src/test/java/com/mall/agent/flow/RefundWorkflowTest.java`、`agent/src/test/java/com/mall/agent/tools/RefundExecutorTest.java`、`agent/src/test/java/com/mall/agent/model/ReviewVerdictTest.java`、`agent/src/test/java/com/mall/agent/config/AgentConfigTest.java`

**Interfaces:** `RefundWorkflow.apply(String sessionId, RefundRequest request): String` uses Tasks 4–5, then reviewer; only approved, internally consistent `ReviewVerdict(boolean approved, String citedPolicyCode, List<ReviewFault> faults)` reaches `RefundExecutor.apply(Long orderId, String reason, String expectedCatalogFingerprint, String expectedPolicyCode): String`. Top-level `citedPolicyCode` is mandatory on **both** approval and rejection and must equal the retrieved exact code; `ReviewFault` has `category` (`FACT_CONFLICT`, `POLICY_CONFLICT`, `USER_INSTRUCTION_RISK` or `UNCERTAIN`), `evidence` and cited `policyCode`. `RefundReviewContext` adds `PolicyEvidence`. Keep the review Agent tool list empty.

- [ ] **Step 1: Write failing tests** named `policyEvidenceChangesReviewInputAndVerdict`, `policyTextInstructionsCannotGrantApproval`, `approvedWithoutMatchingCitedCodeNeverExecutes`, `rejectedOrMalformedVerdictNeverExecutes`, `sameSessionRejectedOrderCannotRetry`, `policyFailureEscalatesWithoutExecution`, `executorPostsReviewedPair`, `staleReviewCodeIsDefiniteRejection`, `transportFailureIsUnknownOutcome` and `unverifiableFaultUsesGenericHumanReply`. Assert reviewer sees exact clause/code/fingerprint but no FAQ, product text or decision Agent summary; a clause containing role-style instructions remains evidence data and cannot change the review output schema or authorization rule; even approved verdicts with missing/wrong top-level code fail closed; malformed/empty/contradictory output and invalid fault citations fail closed. The executor recognizes only MCP `isError=true` with parseable business code 50005 as a definite pre-write rejection; negative transport/HTTP codes, malformed errors and timeouts remain unknown outcomes. Preserve existing concurrency and transport-uncertainty tests.

```java
assertEquals(0, executorCalls.get()); // approved verdict cites a different policy code
assertEquals("SHIPPED_NOT_RECEIVED", submittedArgs.get("expectedPolicyCode").asText());
assertTrue(staleReviewReply.contains("人工"));
assertTrue(transportFailureReply.contains("无法确认"));
```
- [ ] **Step 2: Run RED:** `& $Mvn -pl agent '-Dtest=RefundWorkflowTest,RefundExecutorTest,ReviewVerdictTest,AgentConfigTest' test`. Expected: new context/verdict/approval tests fail.
- [ ] **Step 3: Implement structured review and wire `RefundWorkflow` to Task 6's confirmation callback.** A denial or catalog failure never calls reviewer/executor; reviewer rejection records one escalation and terminates this session/order; only code-verified fault evidence enters user templates. Send the reviewed fingerprint **and** exact policy code. A backend code 50005 means no new write and asks for human review; all other error or absent-result shapes say status cannot be confirmed unless an explicit backend contract proves otherwise. Remove the legacy Agent-visible `request_refund` path after its state/atomicity rules are covered by `RefundWorkflowTest`.
- [ ] **Step 4: Run GREEN:** Step 2 command, then `& $Mvn test` from after-sales-agent root. Expected: full reactor passes and decision Agent schema has no action tool capable of execution.
- [ ] **Step 5: Commit** Task 7 files: `git commit -m "feat: gate refunds through grounded independent review"`.

### Task 8: 真实环境验证与隐患收口

**Files:**
- Create: `docs/phase3a-rag-refund-validation-<execution-date>.md`
- Modify: `README.md`、`AGENTS.md`、`docs/known-issues.md`
- Test: full Maven reactor in both repositories, `python -m unittest scripts.test_run_agent`, real MCP/model/backend run

**Interfaces:** No new runtime interface. Task 8 consumes the completed supermall, MCP and Agent artifacts; K-13 and K-49 remain `待修` unless their live consumer and hard-gate evidence pass.

- [ ] **Step 1: Prepare isolated test orders through normal business APIs** under a current C-user JWT. Record only order IDs needed for validation, never token values; use the local ignored `.env` and launcher. Do not reuse an already refunded order to demonstrate a new write.
- [ ] **Step 2: Run full automated gates:** from supermall root `& $Mvn test`; from after-sales-agent root `& $Mvn test` and `python -m unittest scripts.test_run_agent`. Expected: all test suites pass on the current source, not cached installed modules.
- [ ] **Step 3: Exercise real paths:** package the MCP jar with `& $Mvn -pl mcp-server -am package`, then run `python scripts/mcp_stdio_smoke.py` with `SUPERMALL_TOKEN`/`SUPERMALL_BASE_URL` set in process environment. For direct smoke submit, first call `--tool get_refund_eligibility --order-id <fresh-id>`, read its `catalogFingerprint` and `policyCode`, then pass `--expected-fingerprint <that-value> --expected-policy-code <that-code>` to `--tool submit_refund --order-id <fresh-id> --reason <test-reason>`; the Agent's normal confirmation path supplies these automatically. Exercise unconfirmed handoff, backend ineligible, reviewer rejection, normal refund, policy fingerprint mismatch, post-review write-version/policy-context conflict, and existing-refund idempotent replay. Switch between two real supermall policy catalog versions through a controlled deployment while the MCP/Agent remains running; use a controllable pause between review and submit to prove the write guard rejects a stale version. Add a real-model A/B review with the same facts/request and two controlled clause variants; verify both structured verdicts and whether evidence changes the conclusion, without executing either. Verify the Agent refreshes the new live directory and stops on fingerprint mismatch. Check MCP trace plus read-only database snapshots for target order, refund row count/status and amount; distinguish business rejection, model/transport failure and unknown write outcome. A test-only catalog stub is supplemental evidence and does not satisfy the live switch gate.
- [ ] **Step 4: Write the validation record** with request/response summaries, commands, exit codes, database invariants and limits, excluding all credentials and raw model prompts containing user secrets. Update K-13 only when the real catalog deployment switch, refresh, failure fallback and live wiring pass; update K-49 only when confirmation and qualification hard gates pass; update README/AGENTS current status from measured results.
- [ ] **Step 5: Commit** the validation record and status changes in after-sales-agent. Run `git diff --check` and review both repositories' final diffs before declaring phase 3A complete.

## Handoff

完成 Task 8 后，阶段 3B 才接入 FAQ/商品资料问答；它复用 Task 4 的目录消费者和 Task 6 的可信回复优先级。退款复核验收不得把阶段 3B 的未实现语料算作已交付。
