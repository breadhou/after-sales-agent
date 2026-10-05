# Frozen v2 formal scenarios

This new directory expands the corrected, immutable v2 pilot to 240 independently
premarked cases. It preserves all 32 pilot dictionaries, including the exact
REVIEW-001/013 trusted-fact strings and their distinct requests. `pilot/` contains
byte copies of the six original v2 JSONL files. The new pilot manifest references
only those 32-case copies; the formal manifest references the six expanded files.
Neither historical v1/v2 manifest nor any saved run/assessment is updated.

| Category / IDs | Cases | Mode |
|---|---:|---|
| NORMAL-001..060 | 60 | LIVE_E2E |
| BOUNDARY-001..040 / 041..050 | 40 / 10 | LIVE_E2E / CONTROLLED |
| ADVERSARIAL-001..020 / 021..040 | 20 / 20 | LIVE_E2E / CONTROLLED |
| FAULT-001..030 | 30 | CONTROLLED |
| INFO-001..030 | 30 | LIVE_E2E |
| REVIEW-001..030 | 30 | REVIEW_ONLY |

NORMAL contains exactly 32 legal whole-order refunds (001..005, 009..035), 16
order/logistics queries (006..007, 036..049), and 12 eligibility questions (008,
050..060). Added NORMAL cases declare `intent-refund`, `intent-query` or
`intent-eligibility`. Tests classify the actual expectation/request semantics,
including the immutable untagged pilot, and reconcile these tags with 32/16/12.
Refund variations cover status/policy, complete-day ranges with API timing margin,
quantity, one/two/three SKUs and exact paid-amount arithmetic. Query and eligibility
cases exercise status, ownership, existing records and fields actually exposed by
the tools. A changed phrase alone does not define a new factual variation.

INFO-001..008 exercise policy questions, 009..018 the frozen FAQ corpus, 019..028
current demo products, and 029..030 historical-description limits/current-turn
refund priority. Product cases bind submitted demo logical keys; actual IDs remain
private. FAQ expectations bind current corpus IDs, without assuming the list tool
can paginate or the detail tool returns filtered-out product line items.

Every controlled case declares the five components, substituted role/boundary,
real-model usage and AGENT_CHAIN/MCP_CONTRACT/BACKEND_TRANSACTION target. Model
scripts use the actual `text`/`toolCalls` shapes; only tool argument aliases are
rendered by the adapter. Fixed MCP response text does not render logical IDs.
BOUNDARY-043 uses POLICY_WINDOW_FIXED_TIME: a test-only static clock calls the
real RefundEligibilityEvaluator.assess on detached inputs built from the real
fixture Order, with literal expectations at 7d23:59:59 and 8d00:00:00. All model
roles/MCP are ABSENT; the backend evaluator/policy are REAL; the clock alone is
substituted. It checks that persisted order/refund facts remain unchanged and
submits no refund. This proves controlled business-evaluator truncation, not the
live API system clock, model behavior or a refund transaction. The real probe
remains undispatched; the offline fixed-clock unit test is its local proof.
Malformed/mismatched facts and catalogs are evidence/format failures, not valid
semantic reviewer interceptions. The FAQ draft attacks (ADVERSARIAL-031..033)
exercise fabricated narrative/citation IDs and code-owned output binding against
the real frozen corpus. They do not claim that a real model consumed an altered
FAQ corpus. Product attacks cover unsafe initial/final responses; one failed final
citation disables the whole answer. Explanation failures may return verified
source originals, whereas unavailable sources require the fixed no-basis reply.

REVIEW-001..012 are risks; 013..024 are normal candidates. The three extra pairs
keep the trusted facts, original request and candidate action exactly fixed and
change only the policy evidence. Their independent premark is APPROVED → REJECTED:

| Pair ID | Cases | Policy-only change |
|---|---|---|
| REVIEW-POLICY-PAIR-001 | 025 / 026 | Two-day RECEIVED: window 7 days → 1 day |
| REVIEW-POLICY-PAIR-002 | 027 / 028 | SHIPPED allowed → only DELIVERED allowed |
| REVIEW-POLICY-PAIR-003 | 029 / 030 | Whole-order refund allowed → exchange only |

These are explicit synthetic policy experiments, not claims about backend policy
changes. Their `pairId` differs from the immutable REVIEW-PAIR-001 risk/request
pair. Synthetic reviews have no executor, fixture or dialogue turns. An invalid
or missing verdict remains an error; a model's actual refusal does not lower a
frozen APPROVED answer.

Repeat IDs, in frozen order, are NORMAL-001/002/004/005,
ADVERSARIAL-001/002/003/005 and REVIEW-001/002/013/014. Each has semantic trial
indices 1/2/3, totaling 240 first trials + 24 extra trials = 264. The existing wire
format encodes FIRST with `repeatIndex=0`, then REPEAT with indices 1 and 2.

`freeze.json` is a provenance sidecar, not an additional manifest wire field.
The closed manifests anchor exact file bytes and case order; the sidecar records
their hashes, all 32 canonical pilot case hashes, prompt/FAQ/product/source/SDK
inputs and the preparatory private proof digest. The recorded commits describe
the preparation baseline, not this file's own future commit. Model identity is
the configured endpoint/name/temperature digest, never a supplier-returned name.
The private proof binds saved runtime/policy evidence and the JDK release file.
Task14 must bind the actual source/build/runtime/configuration/policy identity
before execution; this preparatory freeze does not assert that binding occurred.

No formal/model/helper/API/MCP/database/probe execution was performed by this
freeze task. Default dry-run plans only; all original runs and the shared budget
remain immutable. Correct answers were set from contracts before formal output
and must not be changed to improve actual results.
