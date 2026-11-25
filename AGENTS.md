# erdMaid — Agent Instructions

Read and apply these repository-local authorities before working:

- [Portable execution and engineering integrity](docs/engineering/agent-execution-policy.md): EG-AI-001, replacement-first design, authorization, CI observation, and integrity.
- [Agent recovery](docs/engineering/agent-recovery.md): one durable checkpoint, mutation recovery, exact-HEAD and current-composition evidence.
- [Engineering contract](docs/engineering/engineering-contract.md): architecture, implementation boundaries, test, CI and merge obligations.
- [Product and fidelity contract](docs/product-fidelity-contract.md) and [baseline scenarios](docs/product-baseline-scenarios.yaml): required user-visible behavior and falsification evidence.
- [Marketplace publication](docs/marketplace-publication.md): separate release authority.

These authorities are complementary, not parallel execution policies. Existing
implementation, configuration, tests and historical decisions are audit
inputs. Establish the authorized desired state and replace obsolete or
conflicting structures directly; verify actual product/security/external
obligations, not legacy internals. Do not invent compatibility constraints.
Missing material evidence fails closed. No implicit cross-repository,
merge, tag, release or Marketplace publication authority.
