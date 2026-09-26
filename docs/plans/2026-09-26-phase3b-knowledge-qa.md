# 阶段 3B：FAQ 与当前商品资料问答 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已验收的阶段 3A 退款防线之外，交付有来源的 32 条原创 FAQ、当前演示商品检索和无动作工具的解释回复。

**Architecture:** 对话 Agent 只记录资料解释请求；可信代码按原始用户输入检索 FAQ/当前商品及同源政策目录，将带来源 ID 的候选交给无工具生成器。它校验引用和交易性声明后才输出补充说明；资料失效只降低问答能力，绝不改变退款准入、复核或执行。

**Tech Stack:** Java 17 编译 / 本机 JDK 22 运行 / LangChain4j 1.20.0 / MCP / Maven / Python 3 种子脚本。采用进程内中文相邻字词与英文/数字词项检索，不增加向量库或 embedding 凭据。

**Spec:** `docs/specs/2026-09-26-rag-policy-review-design.md`，尤其“回复与资料问答”“验收”。本计划在 [阶段 3A](2026-09-26-phase3a-rag-refund-review.md) Task 1–8 通过后实施；复用其 `PolicyCatalogConsumer` 与 `ConversationCoordinator`。

## Global Constraints

- 商品读取只能用 C 端用户 JWT 经 MCP 调 supermall `GET /api/products` 和 `GET /api/products/{id}`；这两个 API 均非匿名公开。商家创建/上架演示商品只能用独立商家 JWT 经业务 API，不能由 Agent 或 MCP 代写，也不直写数据库。
- 只索引被 Git 忽略的环境清单中的 15–35 个原创演示商品，并且每次引用前新鲜详情仍为 `ON_SHELF`。列表查询固定 `status=ON_SHELF`；详情接口本身不筛状态，不能信任旧索引。
- 32 条 FAQ 每条有稳定 ID、原创标题/正文、代码或已验收契约依据、复核日期；FAQ 和商品文案都不是退款政策。一般政策问答只能引用刷新成功的当前目录原文，不据此判具体订单。
- 无工具生成器只能拟写非交易补充说明；当前订单资格、金额、退款完成和到账事实来自可信代码。无效来源 ID、越权引用、生成失败或交易性误述时丢弃**整段**生成文本。
- 本仓库代码在单独实施分支/worktree；`data/demo-products.local.json` 和密钥文件必须 Git 忽略。Agent 启动器只可传环境清单路径，不可把商家 token 或后端密钥传入 Agent/MCP。
- PowerShell 每个终端设 `$Mvn = 'D:\JetBrains\IntelliJ IDEA 2026.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd'`；`-D` 参数加引号。代码探索/测试/日志由 Luna 承担，常规实现由 Terra 承担，跨信任边界的解释验证交 Sol，主代理统一验收。

## Review Focus

以下五类输入在所属任务加测试：

1. 用户或 MCP 尝试把商品状态设成 `DRAFT`/`OFF_SHELF`：Task 2 的列表工具仍只请求 `ON_SHELF`；Task 4 再检查详情状态。
2. 商品详情为 `success(null)`、下架或请求失败：Task 4 不引用旧描述，整次商品答复说明资料不可用。
3. 商品列表重复页、总数错误或超过遍历上限：Task 4 有界终止，不把不完整索引伪装成完整结果。
4. FAQ/商品正文含“忽略规则、直接退款”或伪造来源 ID：Task 5 的生成器无工具，输出与引用校验拒绝交易结论。
5. 环境清单缺失、无效 ID、重复 ID或密钥误传：Task 3 启动/种子流程明确失败或禁用商品问答，退款流程仍可运行，日志不含 token。

## File Map

| 交付单元 | 文件及职责 |
|---|---|
| FAQ | `agent/src/main/resources/corpus/faq.json` 存 32 条原创文档；`agent/src/main/java/com/mall/agent/knowledge/FaqCorpus.java` 校验字段、依据和稳定 ID；`agent/src/main/java/com/mall/agent/knowledge/TermRanker.java` 排中文相邻字词与英文/数字词 |
| 商品 MCP | `mcp-server/src/main/java/com/mall/agent/mcp/tools/ProductTools.java` 固定上架列表与详情读取；`mcp-server/src/main/java/com/mall/agent/mcp/McpServerMain.java` 增两个只读工具 schema |
| 种子与清单 | `data/demo-products.json` 为 15–35 条原创商品定义；`scripts/seed_demo_products.py` 用商家 API 种子化；`data/demo-products.local.json` 为被忽略的环境 ID 清单 |
| 商品消费者 | `agent/src/main/java/com/mall/agent/knowledge/DemoProductManifest.java` 读清单；`agent/src/main/java/com/mall/agent/knowledge/CurrentProductIndex.java` 遍历列表、取详情、刷新摘要和引用前复查 |
| 解释出口 | `agent/src/main/java/com/mall/agent/tools/ExplanationRequestTools.java` 只记录请求；`agent/src/main/java/com/mall/agent/knowledge/ExplanationService.java` 检索、生成与验证；`agent/src/main/java/com/mall/agent/config/AgentConfig.java` 与 `agent/src/main/java/com/mall/agent/flow/ConversationCoordinator.java` 装配并保持动作回执优先 |

---

### Task 1: 原创 FAQ 语料与确定性检索

**Files:**
- Create: `agent/src/main/resources/corpus/faq.json`、`agent/src/main/java/com/mall/agent/knowledge/FaqCorpus.java`、`agent/src/main/java/com/mall/agent/knowledge/TermRanker.java`
- Test: `agent/src/test/java/com/mall/agent/knowledge/FaqCorpusTest.java`、`agent/src/test/java/com/mall/agent/knowledge/TermRankerTest.java`

**Interfaces:** `FaqCorpus.load(): List<FaqDocument>`, where `FaqDocument` is a nested record with `id`, `title`, `body`, `basis` and `reviewedAt`. `TermRanker.rank(String originalQuestion, Map<String,String> sourceIdToText): List<String>` returns source IDs with stable tie-breaking. IDs are exactly `FAQ-001` through `FAQ-032`.

- [ ] **Step 1: Write failing tests** named `loadsExactly32UniqueGroundedFaqs`, `rejectsMissingBasisOrReviewDate`, `chineseBigramsAndLatinNumbersRankDeterministically`. Assert IDs are unique/continuous, every basis points to an existing code path or accepted contract, and the same input yields the same order without semantic policy invention.

```java
assertEquals(32, documents.size());
assertEquals("FAQ-001", documents.get(0).id());
assertEquals("FAQ-032", documents.get(31).id());
assertEquals("FAQ-001", ranker.rank("退款", sources).get(0));
```
- [ ] **Step 2: Run RED:** `& $Mvn -pl agent '-Dtest=FaqCorpusTest,TermRankerTest' test`. Expected: corpus and retriever classes/data are absent.
- [ ] **Step 3: Write 32 original, reviewable FAQ entries and the minimal loader/ranker.** Cover verified查单/物流、退款状态、后端资格说明、人工升级、能力边界、政策来源和当前商品资料；逐条对照现行代码/契约，不能把 FAQ 写成新的退款窗口或质量核验承诺。中文用相邻字词，英文/数字用词项；无命中时返回空列表。
- [ ] **Step 4: Run GREEN** with Step 2 command. Expected: exact count, basis and ranking tests pass.
- [ ] **Step 5: Commit:** `git commit -m "feat: add grounded FAQ corpus and lexical retrieval"`.

### Task 2: MCP 只读商品列表与详情

**Files:**
- Create: `mcp-server/src/main/java/com/mall/agent/mcp/tools/ProductTools.java`、`scripts/test_mcp_stdio_smoke.py`
- Modify: `mcp-server/src/main/java/com/mall/agent/mcp/McpServerMain.java`、`scripts/mcp_stdio_smoke.py`
- Test: `mcp-server/src/test/java/com/mall/agent/mcp/tools/ProductToolsTest.java`、`mcp-server/src/test/java/com/mall/agent/mcp/ToolSchemaTest.java`、`mcp-server/src/test/java/com/mall/agent/mcp/McpProtocolTest.java`、`mcp-server/src/test/java/com/mall/agent/mcp/FakeSupermall.java`

**Interfaces:** MCP `list_on_shelf_products(pageNum, pageSize)` invokes `GET /api/products?pageNum=…&pageSize=…&status=ON_SHELF`; MCP `get_product_detail(productId)` invokes `GET /api/products/{id}`. `ProductTools` uses the existing `SupermallClient` and user JWT; neither tool accepts a status or write parameter. Cap page size at 20 and reject nonpositive page/ID.

- [ ] **Step 1: Write failing tests** named `listAlwaysForcesOnShelfAndBoundsPageSize`, `detailKeepsStatusForCallerVerification`, `nullOrErrorDetailIsToolError`, `productToolsRequireUserBearer` and `smokeChildEnvironmentExcludesMerchantSecret`. Assert only safe fields needed for answer are returned, no merchant token or hidden write tool appears in the MCP schema; unauthorized HTTP response is not treated as an empty catalog. Assert the smoke script's Java subprocess receives only user token, base URL and required OS/JVM settings, even when the parent process holds `DEMO_MERCHANT_TOKEN`.

```java
assertTrue(fakeSupermall.lastRequestUri().toString().contains("status=ON_SHELF"));
assertFalse(productToolSchema.inputSchema().properties().containsKey("status"));
assertTrue(nullDetailResult.isError());
```

```python
assert "DEMO_MERCHANT_TOKEN" not in captured_child_env
assert captured_child_env["SUPERMALL_TOKEN"] == "test-user-token"
```
- [ ] **Step 2: Run RED:** `& $Mvn -pl mcp-server '-Dtest=ProductToolsTest,ToolSchemaTest,McpProtocolTest' test` and `python -m unittest scripts.test_mcp_stdio_smoke`. Expected: missing tool/schema and child-environment assertions fail.
- [ ] **Step 3: Implement both read-only tools and schema/dispatch.** Fix `status=ON_SHELF` in code, not in a model-controlled argument. Retain source `productId`, description, status and SKU facts needed for citation; do not claim list itself has current SKU/minPrice if only detail provides them. Update the smoke script's tool count and names, and replace its `os.environ.copy()` child environment with an explicit allowlist for user MCP credentials plus OS/JVM essentials.
- [ ] **Step 4: Run GREEN** with Step 2 command, then `& $Mvn -pl mcp-server test`, `python -m unittest scripts.test_mcp_stdio_smoke` and `python -m py_compile scripts/mcp_stdio_smoke.py`. Expected: all pass.
- [ ] **Step 5: Commit:** `git commit -m "feat: expose read-only current product MCP tools"`.

### Task 3: 经商家业务 API 创建演示商品并保护环境清单

**Files:**
- Create: `data/demo-products.json`、`scripts/seed_demo_products.py`、`scripts/test_seed_demo_products.py`
- Modify: `.gitignore`、`scripts/run_agent.py`、`scripts/test_run_agent.py`
- Runtime-only ignored files: `data/demo-products.local.json`、`data/demo-products.seed-journal.local.json`

**Interfaces:** `seed_demo_products.py --category-id <positive-id>` reads `DEMO_MERCHANT_TOKEN` and `SUPERMALL_BASE_URL` from process environment; calls `POST /api/merchant/products` with 15–35 stable logical keys and writes logical-key→product-ID manifest after confirmed creates. Before each POST it writes an ignored in-flight journal record; if response is lost or the process exits before saving the ID, rerun stops and requests manual reconciliation through a merchant business surface rather than blindly posting again. Agent receives only `DEMO_PRODUCT_MANIFEST` path through the launcher allowlist; it never receives the journal or `DEMO_MERCHANT_TOKEN`.

- [ ] **Step 1: Write failing Python tests** named `seedsOnlyThroughMerchantApi`, `refusesMissingMerchantTokenOrCategory`, `manifestResumeSkipsCreatedLogicalKeys`, `lostCreateResponseBlocksAutomaticRetry` and `launcherPassesOnlyManifestPath`. Assert no direct SQL, no C-user token used for merchant writes, and no merchant/back-end secret in Agent subprocess environment or logs. Simulate a POST that succeeds at the server but times out locally; rerun must not issue another POST for that logical key. Task 4 tests invalid manifests at the consumer boundary.

```python
assert all(call.path == "/api/merchant/products" for call in create_calls)
assert resumed_create_count == 0  # unresolved in-flight POST is never retried
assert "DEMO_MERCHANT_TOKEN" not in captured_agent_env
assert captured_agent_env["DEMO_PRODUCT_MANIFEST"] == str(local_manifest)
```
- [ ] **Step 2: Run RED:** `python -m unittest scripts.test_seed_demo_products scripts.test_run_agent`. Expected: seed script/allowlist tests fail.
- [ ] **Step 3: Author 15–35 original demo products with SKU price ≥0.01 and stock ≥0.** Use only existing category ID supplied at runtime; do not invent a category or merchant ID. Script uses the merchant JWT, atomically marks an in-flight journal before each POST, saves the ignored manifest after each confirmed create, then clears the journal marker; on rerun it skips confirmed logical keys and stops on unresolved in-flight markers. An operator reconciles an uncertain create using merchant business API/UI before marking it resolved; the script never assumes a timed-out POST failed. Add both local files to `.gitignore` and allowlist only the manifest path to Agent. Do not send merchant credentials to Agent/MCP.
- [ ] **Step 4: Run GREEN** with Step 2 command. Expected: tests pass against fake HTTP; real merchant API seeding belongs to Task 6.
- [ ] **Step 5: Commit:** `git commit -m "feat: seed original demo products through merchant API"`.

### Task 4: 当前上架商品索引、过滤与引用复查

**Files:**
- Create: `agent/src/main/java/com/mall/agent/knowledge/DemoProductManifest.java`、`agent/src/main/java/com/mall/agent/knowledge/CurrentProductIndex.java`、`agent/src/main/java/com/mall/agent/knowledge/ProductEvidence.java`
- Test: `agent/src/test/java/com/mall/agent/knowledge/DemoProductManifestTest.java`、`agent/src/test/java/com/mall/agent/knowledge/CurrentProductIndexTest.java`

**Interfaces:** `DemoProductManifest.load(Path path): Set<Long>`; `CurrentProductIndex.refresh(Set<Long> allowedIds): ProductSnapshot` paginates Task 2's list with `pageSize=20` and retains only allowed, `ON_SHELF` IDs; `ProductSnapshot` is a nested immutable record in `CurrentProductIndex`. `CurrentProductIndex.verifyCitation(ProductSnapshot snapshot, Long productId): Optional<ProductEvidence>` re-GETs detail and checks allowed ID/status before any quote; `ProductEvidence` uses source ID `PRODUCT-<id>` and carries current digest, description and SKU facts.

- [ ] **Step 1: Write failing tests** named `onlyIndexesManifestIdsFromAllPages`, `rejectsDuplicateOrNonAdvancingPages`, `offShelfOrNullDetailDropsCitation`, `invalidManifestDisablesOnlyProductAnswers` and `changedDescriptionRefreshesDigest`. Assert at most 100 pages are followed, total/current/size are consistent, incomplete traversal disables product citations for that request, and no stale description survives a detail error; absent/duplicate/nonpositive manifest IDs do not block refund startup.

```java
assertTrue(snapshot.productIds().stream().allMatch(allowedIds::contains));
assertTrue(index.verifyCitation(snapshot, offShelfProductId).isEmpty());
assertNotEquals(before.digest(), after.digest());
assertTrue(pageCalls.get() <= 100);
```
- [ ] **Step 2: Run RED:** `& $Mvn -pl agent '-Dtest=DemoProductManifestTest,CurrentProductIndexTest' test`. Expected: new index/parser absent.
- [ ] **Step 3: Implement bounded refresh and fresh detail check.** Reject absent, duplicate or nonpositive manifest IDs; ignore unrelated catalog products even if they match a query. Refresh current product content digest each question, keep each request pinned to one snapshot and do not backfill from previous snapshots on failure.
- [ ] **Step 4: Run GREEN** with Step 2 command. Expected: all tests pass.
- [ ] **Step 5: Commit:** `git commit -m "feat: index only current allowlisted demo products"`.

### Task 5: 无动作解释生成与可信回复优先级

**Files:**
- Create: `agent/src/main/java/com/mall/agent/tools/ExplanationRequestTools.java`、`agent/src/main/java/com/mall/agent/knowledge/ExplanationService.java`、`agent/src/main/java/com/mall/agent/knowledge/ExplanationDraft.java`
- Modify: `agent/src/main/java/com/mall/agent/config/AgentConfig.java`、`agent/src/main/java/com/mall/agent/flow/ConversationCoordinator.java`、`agent/src/main/resources/prompts/decision-system.txt`
- Test: `agent/src/test/java/com/mall/agent/knowledge/ExplanationServiceTest.java`、`agent/src/test/java/com/mall/agent/config/AgentConfigTest.java`、`agent/src/test/java/com/mall/agent/flow/ConversationCoordinatorTest.java`、`agent/src/test/java/com/mall/agent/agent/PromptTest.java`

**Interfaces:** `ExplanationRequestTools.requestExplanation(Long orderId)` only records a marker. `ExplanationService.answer(String originalInput, Long orderId): String` calls Task 1 FAQ retriever, Task 4 current-product index and, for policy, 3A `PolicyCatalogConsumer.refresh()`. `ExplanationDraft` contains narrative text plus cited source IDs; the generator receives no tools. For order-specific policy text, re-read eligibility via trusted MCP and require its `catalogFingerprint`/`policyCode` to match the exact catalog snapshot.

- [ ] **Step 1: Write failing tests** named `refundOrEscalationReplyWinsOverExplanation`, `eligibilityMarkerWinsOverExplanationWithoutGeneratorCall`, `generalPolicyQuotesCurrentThreeClausesWithoutOrderClaim`, `orderPolicyRequiresMatchingEligibilityFingerprint`, `ineligibleOrderUsesBackendReasonWithoutPolicy`, `historicDescriptionQuestionDoesNotAssertPast`, `badCitationDropsWholeDraft` and `transactionClaimDropsWholeDraft`. Assert no explanation appears in the same turn as a refund, escalation **or fixed backend qualification answer**; if `askRefundEligibility` and `requestExplanation` fire together, explanation generator calls equal zero. Unknown sources, current-order refund/arrival claims or generator failure yield source-original fallback or “无可靠依据”，never a model-invented transaction outcome. Chat order/logistics turns still answer.

```java
assertEquals(0, explanationGeneratorCalls.get()); // eligibility and explanation markers in one turn
assertEquals(trustedEligibilityReply, finalReply);
assertFalse(historicDescriptionReply.contains("下单时描述一致"));
assertFalse(invalidCitationReply.contains(modelDraftText));
```
- [ ] **Step 2: Run RED:** `& $Mvn -pl agent '-Dtest=ExplanationServiceTest,AgentConfigTest,ConversationCoordinatorTest,PromptTest' test`. Expected: marker/service/generator tests fail.
- [ ] **Step 3: Implement marker, retrieval and no-tool generator.** Coordinator prioritizes refund, escalation and qualification trusted replies before any explanation and skips explanation work for those turns. Accept only this request's candidate source IDs; policy body is quoted verbatim from the current directory, FAQ/product prose is nontransactional. Product references call `verifyCitation` immediately before use; source formatting and citation checks are code-owned.
- [ ] **Step 4: Run GREEN** with Step 2 command, then `& $Mvn test` and `python -m unittest scripts.test_run_agent scripts.test_seed_demo_products`. Expected: all pass; model-visible tools still have no policy/product MCP or退款执行 tool.
- [ ] **Step 5: Commit:** `git commit -m "feat: answer grounded policy FAQ and product questions"`.

### Task 6: 真实 API、语料与跨链路验收

**Files:**
- Create: `docs/phase3b-knowledge-validation-<execution-date>.md`
- Modify: `README.md`、`AGENTS.md`、`docs/known-issues.md` only for measured status
- Test: after-sales-agent full Maven reactor + Python tests + live supermall API/MCP/model

**Interfaces:** No new runtime API. Merchant prerequisites are a valid local `DEMO_MERCHANT_TOKEN` and a real category ID; do not place either in the committed document. Current C-user JWT is separately required for product reads.

- [ ] **Step 1: Verify environment without displaying secrets.** Start supermall per its AGENTS; check unauthenticated product API is rejected. Use a valid C-user token for list/detail and a different merchant token for `POST /api/merchant/products`. If merchant token or category ID is absent, document the exact unmet prerequisite and finish all local tests; never create products with direct SQL.
- [ ] **Step 2: Run `seed_demo_products.py` with the real merchant API** to create the committed 15–35 original records and ignored ID manifest; verify C-user list/detail can read them. Down-shelf one test product through the merchant API and verify a fresh detail is excluded; **restore it through the merchant API before final acceptance**, then recheck that at least 15 and at most 35 manifest products are currently `ON_SHELF`.
- [ ] **Step 3: Run full automated gates:** `& $Mvn test`; `python -m unittest scripts.test_run_agent scripts.test_seed_demo_products scripts.test_mcp_stdio_smoke`. Expected: all pass. Clear `DEMO_MERCHANT_TOKEN` from the parent shell before the MCP smoke run, and verify the smoke child allowlist independently. Exercise real MCP/model FAQ, current product, general policy and one order-specific explanation; verify source IDs/text, no invented refund or historic product claim, and unchanged phase 3A refund behavior.
- [ ] **Step 4: Write validation evidence** distinguishing fake HTTP tests, real supermall endpoints and live model outputs. Include command/exit code, FAQ count, demo product count, on-shelf status, content digest refresh and failure fallback; omit all JWTs, merchant credentials and private original user utterances. Update README/AGENTS and any K entries only after observed evidence.
- [ ] **Step 5: Commit** evidence and status docs; run `git diff --check` and review final diff. Phase 3 is complete only when 3A and 3B each meet their own gates.
