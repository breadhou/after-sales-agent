# Task 1 implementation report — shared evaluation contract

## Result

Implemented the shared closed v1 case and evaluation wire contract. Python and Java bind to the documented protocol and use shared case fixtures for accept/reject parity. Java `CaseSpec.document()` removes both evaluator-only `expect` and `manualRubric`; Python validation retains these fields for the judge. Python owns manifest file/hash, duplicate-ID, quota, and repeat checks; Java validates one case per worker and has no suite loader.

The final direct MCP control is `target=MCP_CONTRACT`, `point=NONE`, with only `toolCalls`; it requires real MCP and backend components, all model roles ABSENT, and `usesRealModel=false`. MCP contract calls must use implemented tool names. By contrast, `AGENT_CHAIN` model scripts may issue unknown tool names as intentionally untrusted model input. Script shape is `{role, responses:[{text?, toolCalls?:[{name, arguments}]}]}`; `toolCalls` may appear only for DIALOGUE. Case placeholders are exact `{{logical-alias}}` tokens and resolve only declared order/product aliases. Business IDs and money remain strings.

## TDD evidence

**RED — direct MCP contract combination.**

```text
python -m unittest scripts.test_evaluation_contract.EvaluationContractTest.test_mcp_contract_direct_call_requires_none_and_real_backend_components -v
Ran 1 test ...
ERROR: case.control: MCP_CONTRACT requires fixed toolCalls before request
FAILED (errors=1)
```

The Java shared-fixture and focused-combination checks were also red before implementation:

```text
& 'D:/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd' -pl agent -am '-Dtest=CaseSpecTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
Tests run: 4, Failures: 0, Errors: 2
... MCP_CONTRACT requires toolCalls before request
BUILD FAILURE
```

**GREEN — focused contract verification.**

```text
python -m unittest scripts.test_evaluation_contract -v
Ran 18 tests ...
OK

& 'D:/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd' -pl agent -am '-Dtest=CaseSpecTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**Full relevant regressions.**

```text
python -m unittest discover -s scripts -v
Ran 32 tests ...
OK

& 'D:/JetBrains/IntelliJ IDEA 2026.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd' -pl agent -am test
Tests run: 198, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

This Maven command verifies the full Agent module (198/198); the root 240-test baseline combines MCP Server 46 and Agent 194. The root-wide 240-test gate remains for Task 14. Python passed 32/32 (the 14 existing script tests plus 18 contract tests). Maven output included the known JDK/console encoding warnings and expected test error logs; none were failures.

## Files

- `.gitignore` — ignore `/eval/runs/` before runtime evidence is created.
- `eval/protocol.md` — shared closed case, control, binding, lifecycle, event, metering, worker, and suite protocol.
- `eval/fixtures/case-contract.json` — shared accepted and rejected per-case fixtures, including the direct MCP case and attack-script example.
- `eval/scenarios/v1/README.md` — v1 scenario directory contract; no production placeholder cases added.
- `scripts/evaluation_contract.py` — standard-library Python case, suite, wire, and placeholder validation.
- `scripts/test_evaluation_contract.py` — focused Python checks, quota fixtures, wire checks, and shared parity.
- `agent/src/main/java/com/mall/agent/evaluation/CaseSpec.java` — Java 17 per-case validator and evaluator-label-free defensive document.
- `agent/src/test/java/com/mall/agent/evaluation/CaseSpecTest.java` — shared fixture parity, document redaction/copy checks, bigint string preservation, and direct MCP combination tests.

## Self-review and concerns

Reviewed closed fields/enums and mode-target-payload combinations across both validators, including bigint IDs, decimal money strings, actor-token scoping, substitution responses, lifecycle payloads, event sentinels, partial metering, exact phase quotas, and the final MCP_CONTRACT direct-call constraint. The shared fixture suite rejects the same malformed controls in both languages. No future runner, model adapter, live batch, credential use, network call, or backend call was introduced. `/eval/runs/` is ignored and `git diff --check` is clean. No new potential issue was found, so `docs/known-issues.md` was not changed. Root-wide regressions remain intentionally with Task 14; this task verified all Agent module tests and all Python script tests.
