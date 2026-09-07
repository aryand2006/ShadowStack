# Risk process — how ShadowStack scores patches

## Intended process

1. **Rule prior** — modernization rule tier → base score (`LOW→15%`, `MEDIUM→45%`, `HIGH→70%`, `CRITICAL→90%`).
2. **Calibrate** — accept/reject history shrinks the prior toward observed reject rate (Laplace-smoothed, stored in `ss_rule_prior_calibration`).
3. **Context prior** — `RiskClassifier` / complexity signals for the file raise or lower risk for the same rule on a hard module.
4. **Generate** — initial displayed score = calibrated rule × (1−cw) + context × cw.
5. **Verify** — layers publish signals; `SemanticRiskScorer` is the **sole** risk aggregator (no double-count).
6. **Blast radius** — API / bytecode / AST contributions estimate impact when evidence is weak.
7. **Evidence-weighted blend** — residual risk can **fall below** the prior when verify is clean and evidence is strong:
   ```
   residual = adjustedPrior*(1−w) + verifyRisk*w
            + blastWeight * blast * (1 − evidence)
   w = evidenceStrength * evidenceWeightMax
   ```
8. **Soft vs hard floors** — `PASS` no floor; `WARN` → `warnFloor` (0.35); `FAIL` → `highThreshold` (0.85). Only `PASS` enters `PENDING_REVIEW`.
9. **Review / auto-apply** — humans accept/reject (feeds calibration). Optional auto-apply when residual ≤ `max-auto-apply-risk` **and** evidence ≥ `min-auto-apply-evidence` (off by default).

## UI

- Queue **Residual %** = posterior residual risk; tooltip shows evidence strength (`eNN`).
- Review shows **residual risk** gauge, **evidence**, and factor breakdown.
- Thresholds unified at `0.3 / 0.6 / 0.85` (API yml, worker blend defaults, RiskGauge).

## Efficiency

- Java **COSMETIC/LOW** skip Maven TestExecution / golden; still run compile + AST + API + semantic aggregate.
- Java **MEDIUM+** run the full stack including tests/golden.

## Config (`shadowstack.risk.*`)

| Key | Default | Role |
|-----|---------|------|
| low/medium/high-threshold | 0.3/0.6/0.85 | Tier boundaries |
| warn-floor | 0.35 | Mild floor for WARN |
| context-prior-weight | 0.25 | How much module context moves the prior |
| blast-radius-weight | 0.15 | Impact term when evidence is weak |
| evidence-weight-max | 0.85 | Max weight given to verify vs prior |
| auto-apply-enabled | false | Gate for auto-accept |
| max-auto-apply-risk | 0.2 | Residual ceiling for auto-apply |
| min-auto-apply-evidence | 0.70 | Evidence floor for auto-apply |
