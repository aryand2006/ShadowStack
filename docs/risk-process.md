# Risk process — how ShadowStack scores patches

## Intended process (matches the modernization workbench design)

1. **Rule prior** — each modernization rule declares a static risk tier (`COSMETIC`/`LOW`/`MEDIUM`/`HIGH`/`CRITICAL`).
2. **Generate** — patches get an initial score from that tier (`LOW→15%`, `MEDIUM→45%`, `HIGH→70%`, `CRITICAL→90%`).
3. **Verify** — fail-closed gates run; layers contribute a **measured** `verifyRisk`.
4. **Blend** — displayed score = `max(rulePrior, verifyRisk)`; tier from `shadowstack.risk.*` thresholds in `application.yml`.
5. **Review** — humans accept/reject; HIGH/CRITICAL should get more scrutiny.

This is intentional: conservative priors (many MEDIUM rules) look “high-ish” until verify proves otherwise — but after verify the score is no longer a frozen placeholder.

## Efficiency

- Java **COSMETIC/LOW** patches skip Maven `TestExecutionVerifier` / golden / semantic layers (compile + AST + API still gate).
- Java **MEDIUM+** still run the full 7-layer stack.

## UI

Queue column **Blended %** is `max(rule prior, verify pipeline)`. Hover for the tooltip.
