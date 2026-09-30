# Scenario format v1

This directory is reserved for reviewed, versioned scenario data. Task 1 defines the parser and format only; no pilot or full production scenario file is fabricated here.

Each JSON Lines row is one complete `Case` from [the shared protocol](../../protocol.md). Keep expected outcomes and manual rubric in the evaluator case file; Java strips both before business execution. Scenario IDs are stable and unique within a suite. Use declared order/product aliases in turn text as `{{order-a}}`; the Python renderer substitutes exact prepared strings without evaluating expressions. Use decimal strings for all money and positive decimal strings for business IDs.

A manifest binds phase to exact case-file bytes with SHA-256, preserves case order, and names repeated cases. `load_suite` verifies case references, hashes, uniqueness, and the exact phase-specific category/mode quotas. Pilot uses `v1-pilot` and 32 cases (`8/8/6/4/4/2` across the six categories; `22 LIVE_E2E / 8 CONTROLLED / 2 REVIEW_ONLY`). Full uses `v1-full` and 240 cases (`60/50/40/30/30/30` by category; `150/60/30` by mode), plus 12 repeated IDs divided evenly among normal live, adversarial live, and independent review. Pilot data is never a substitute for full quotas.

Controlled cases state the exact injected boundary, component states, and real-model flag. Dialogue scripts use ordered `{text?, toolCalls?}` response objects; scripted tool requests are untrusted inputs and may name unknown tools to test the production allowlist. Fixed MCP/source responses are also untrusted test data. REVIEW_ONLY cases provide fixed review facts, have no turns or execution control, and mark synthetic context explicitly.

Generated fixtures used by contract tests stay in temporary directories. Private run evidence is written only beneath `/eval/runs/`, which is ignored by Git.
