## Superpowers SDD Model Routing

When `superpowers:subagent-driven-development` is active,
Superpowers SDD is the sole orchestration authority.

Do not create a second independent implementation or review workflow.

During SDD:

- Follow the SDD task loop exactly.
- Do not automatically dispatch separate project-defined Worker,
  Expert, or Reviewer agents outside the SDD workflow.
- Implementer subagents must not dispatch their own subagents.
- Never run multiple implementation agents concurrently.
- Use SDD's task reviewer as the per-task review gate.
- Use SDD's scoped re-review process for fixes.
- Use SDD's whole-branch reviewer as the final review gate.

### Model routing

Choose the least expensive model that can reliably complete the task.

#### Exploration
Repository search, dependency tracing, log investigation,
test discovery, and read-only evidence gathering:

- Default: GPT-6 Luna High
- Difficult investigation: GPT-6 Luna XHigh
- Exceptionally difficult investigation: GPT-6 Luna Max

#### Implementation
Mechanical changes with a complete specification,
usually 1-2 files:

- GPT-6 Luna High

Routine implementation with normal integration work:

- GPT-6 Luna XHigh

Broader multi-file work with meaningful integration complexity:

- GPT-6 Luna Max
- Escalate to GPT-6 Sol XHigh when judgment or risk is high

#### High-risk implementation
Use GPT-6 Sol directly for:

- authentication
- authorization
- security-sensitive logic
- transactions
- concurrency
- locking
- idempotency
- payment/refund correctness
- data-integrity invariants
- complex state machines
- difficult cross-module bugs

Default:

- GPT-6 Sol XHigh

Use GPT-6 Sol Max when the task is exceptionally difficult,
has already failed with a lower tier, or has unusually high impact.

### Review routing

Small mechanical task review:

- GPT-6 Luna Max or GPT-6 Sol High

Normal non-trivial task review:

- GPT-6 Sol XHigh

High-risk task review involving security, concurrency,
transactions, authorization, payment/refund,
or critical data integrity:

- GPT-6 Sol Max

Final whole-branch review:

- GPT-6 Astra High

### Astra policy

GPT-6 Astra is capped at High.

Use Astra sparingly.

Astra High is appropriate for:

- major architecture decisions
- expensive-to-reverse technical choices
- architecture-level security boundaries
- major persistence or infrastructure redesign
- final whole-branch review

Do not use Astra for routine implementation.

Prefer this architecture workflow:

1. Sol proposes a concrete design.
2. Astra High independently critiques the proposal.
3. Sol incorporates justified findings and owns execution.

### Escalation policy

Prefer Luna for workload and complexity.
Prefer Sol for risk.

Do not make high-risk tasks fail through several Luna tiers
before assigning Sol.

For ordinary implementation escalation:

Luna High
-> Luna XHigh
-> Luna Max
-> Sol XHigh
-> Sol Max

For security, concurrency, transaction, authorization,
payment/refund, or critical data-integrity work:

Sol XHigh
-> Sol Max

### Controller responsibility

The primary session acts as the SDD controller.

The controller should normally use GPT-6 Sol Max.

The controller owns:

- understanding the plan
- task sequencing
- model selection
- subagent dispatch
- fix-loop escalation
- adjudication
- integration
- final verification

Subagent completion is not proof that the overall task is complete.

### Risk is determined by consequence, not difficulty

Complexity asks:
"How hard is this to implement?"

Risk asks:
"What happens if this implementation is wrong?"