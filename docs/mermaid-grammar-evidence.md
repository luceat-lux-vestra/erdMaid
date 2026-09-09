# Mermaid ER grammar evidence

This document records the executable compatibility evidence owned by issue #79. It is deliberately narrower than a general Mermaid compatibility claim.

## Exact upstream target

The maintained evidence target is Mermaid `11.17.2`.

- Mermaid tag: `mermaid@11.17.2`
- tag object: `02d554400de909df3fa1c62b87dca5ef7df4ac97`
- release commit: `dcb694ddb58dc5ad3502e7e903cac05fd812eac3`
- ER grammar source at that commit: `packages/mermaid/src/diagrams/er/parser/erDiagram.jison`
- evidence package: `mermaid@11.17.2`
- parser bundle: `dist/mermaid.esm.min.mjs` from that published package

The CI evidence path uses `npm pack` only: it downloads the exact published Mermaid package tarball, extracts it, and imports the already-built ESM distribution. It does not run `npm install` or resolve Mermaid's declared dependency graph into this repository. Mermaid's release build bundles the non-core ESM distribution and its split chunks into `dist/`, which is part of the published package.

## Proof chain

The required `Test` context owns both halves of the proof.

1. `MermaidGrammarFixtureBindingTest` serializes semantic graphs through the production `MermaidDocumentSerializer` and requires byte-for-byte equality with the checked-in `production-*.mmd` fixtures.
2. The same `Test` job fetches the exact `mermaid@11.17.2` package with `npm pack`, asserts package identity/version, and runs `scripts/verify_mermaid_grammar.mjs` against its published ESM bundle.
3. The script parses every checked-in `.mmd` fixture with Mermaid's public `parse()` API and requires `diagramType=er`.
4. A deliberately invalid ER document must return `false` under `suppressErrors`; otherwise the evidence fails. This negative control prevents a missing/no-op parser from being mistaken for acceptance.
5. The script owns an exact fixture manifest. Adding or removing an `.mmd` file without updating the evidence script fails closed.

No Node, npm, Mermaid, browser, or Mermaid CLI dependency is added to the plugin runtime or Gradle dependency graph.

## Covered production syntax

`production-surface.mmd` is bound directly to production serialization and covers:

- generated entity identifiers plus quoted aliases;
- identifying (`--`) and non-identifying (`..`) relationships;
- all four multiplicities on both relationship sides: exactly one, zero-or-one, one-or-more, and zero-or-more;
- attribute `PK` syntax;
- an authoritative present-empty quoted attribute comment;
- a present-empty relation name as it is actually emitted today (`"physical:"`).

`production-hostile.mmd` is also bound directly to production serialization and covers:

- Mermaid `%%` line comments emitted for table/FK annotations;
- quoted attribute comments;
- hostile control/syntax characters after the serializer's deterministic encoding;
- encoded relationship labels and FK annotation text that cannot inject new Mermaid syntax.

## Grammar-boundary fixture

`grammar-empty-role-boundary.mmd` proves that Mermaid `11.17.2` accepts an empty quoted ER relationship role (`: ""`). It is intentionally **not** described as current production output: the current serializer always prefixes relation provenance, so an authoritative empty relation name is emitted as `"physical:"` or `"virtual:"`, not as an empty role.

Keeping this fixture separate preserves the acceptance requirement from #79 without creating a false statement about the shipped serializer.

## Claim boundary

Passing this evidence means only that the exact checked-in production serializer surface, plus the explicitly named empty-role grammar boundary, is accepted by Mermaid `11.17.2`'s maintained ER parser.

It does **not** prove:

- compatibility with every older or future Mermaid version;
- rendering/layout fidelity in every Mermaid host;
- syntax that the serializer cannot emit;
- browser-specific rendering behavior.

Any serializer syntax change, fixture-manifest change, or Mermaid evidence-version change invalidates the previous proof and requires the binding test and parser evidence to pass again on the exact final PR HEAD and post-merge `main`.
