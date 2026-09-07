# Roadmap — Full Blu Age–class COBOL → Java

> **Status on `main` / this branch:** Phase **0–1 complete**, Phase **2 sequential I/O MVP**
> (+ light SELECT/AT END), Phases **3–5 façades + fail-closed gaps**, Phase **6 CALL graph + goldens**.
> Still **not** certified whole-system Blu Age parity (CICS/IMS deep semantics, full JCL).

## North star

A customer can point ShadowStack at a **multi-program COBOL application**
(online + batch), get **compilable Java**, with **behavioral evidence** and
**human-gated** promotion — covering the surfaces Blu Age–class tools are
expected to touch: screens/transactions, databases, files, JCL, and
cross-program control flow.

## Principles (never drop these)

1. **Fail-closed** — missing gate binary or unverified semantics → FAIL, never soft PASS into review.
2. **Evidence over vibes** — every phase ships certificates / layer results, not marketing scores.
3. **Human review** for HIGH/CRITICAL residual risk; auto-apply stays off by default.
4. **Honest Meta labels** — do not mark a track `full` for capabilities that are still stub/detect-only.
5. **Incremental certificates** — each phase expands the *proven* subset; unproven constructs stay explicit gaps (`Result.unsupportedGaps`, optional `shadowstack.cobol.fail-on-gaps=true`).

---

## Phase 0 — Baseline ✅

| Capability | Status |
|------------|--------|
| COBOL preserving (`cobc -fsyntax-only`) | Full syntax-gated |
| COBOL→Java semantic rehost MVP | javac-gated |
| Identity / empty diffs rejected | Enforced |
| Multi-lang Pilot B launch scope | Documented |

---

## Phase 1 — Language coverage depth ✅

| Workstream | Status |
|------------|--------|
| PIC / USAGE COMP / COMP-3 → typed fields | ✅ (`BigDecimal` for COMP-3) |
| OCCURS → arrays + subscripts | ✅ |
| REDEFINES elementary alias | ✅ (conflicts → gap) |
| IF / EVALUATE / PERFORM UNTIL/TIMES/VARYING | ✅ |
| PERFORM THRU | Partial (calls start; full graph → gap) |
| COPY / REPLACING | ✅ (`CobolCopybookExpander`) |
| SECTION entry points | ✅ |
| Nested programs | ❌ gap (Phase 6 adjacent) |
| Gap list on Result | ✅ |

**Fixtures:** `examples/legacy-cobol/RETAIL.cob`, `COPYDEMO.cob`, `copybooks/WSCOMN.cpy`  
**Tests:** `CobolPhase1IT`, `CobolTranslateIT`

**Exit:** Representative retail/batch sample compiles under javac; gaps published.

---

## Phase 2 — Files & batch data 🟡 MVP

| Workstream | Status |
|------------|--------|
| Sequential OPEN/READ/WRITE/CLOSE → generated helpers | ✅ (javac-friendly inline NIO) |
| `CobolFileFacade` + `SequentialCobolFileFacade` | ✅ (host/runtime) |
| REWRITE / DELETE / START / SORT / MERGE | ❌ fail-closed gaps |
| VSAM / INDEXED / RELATIVE | ❌ fail-closed on façade |
| FD/SELECT deep mapping | Partial (verbs mapped; FD parse light) |

**Fixtures:** `examples/legacy-cobol/BATCHIO.cob`  
**Tests:** `CobolRuntimePhaseIT`

---

## Phase 3 — CICS class 🟡 façade

| Workstream | Status |
|------------|--------|
| `CicsFacade` (LINK/XCTL/RETURN/TSQ/SYNCPOINT) | ✅ default fail-closed |
| Translator EXEC CICS → gap + comment | ✅ |
| BMS / screens | ❌ not started |

---

## Phase 4 — IMS / DB2 class 🟡 façade

| Workstream | Status |
|------------|--------|
| `ImsFacade` (GU/GN/ISRT/REPL/DLET) | ✅ default fail-closed |
| Translator EXEC SQL → gap | ✅ |
| Dual-mode sample | ❌ |

---

## Phase 5 — JCL & operations 🟡 parser MVP

| Workstream | Status |
|------------|--------|
| `JclJobGraph.parse` JOB/STEP/DD | ✅ |
| PROC/INCLUDE/IF → gaps | ✅ |
| Fixture `PAYDEMO.jcl` | ✅ |
| Executable Java batch graph runner | ❌ next |

---

## Phase 6 — Behavioral equivalence at scale ✅

| Workstream | Status |
|------------|--------|
| Golden corpora for batch | ✅ HELLOSS javac+java stdout golden |
| Cross-program CALL | ✅ `TranslatedX.main` + `CobolProgramGraph` / `CobolProjectTranslator` |
| Blast-radius across program graph | RiskPosterior ready; COBOL CALL graph via `CobolProgramGraph` |

---

## Phase 7 — Productization ⬜

| Workstream | Status |
|------------|--------|
| Gap browser UI | Gaps in patch metadata (`translateGaps`) — UI TBD |
| Claim language upgrade | Still **MVP / subset** — do not say “full Blu Age” |

---

## Suggested sequencing (remaining)

```text
Phase 2 deepen (FD/SELECT, AT END) ──► Phase 5 job runner
Phase 3/4 embed real façades in generated code (optional imports)
Phase 6 goldens + CALL graph
Phase 7 Meta/UI gap browser + claim matrix
```

## Non-goals (explicit)

- Bit-identical IBM CICS/IMS replacement
- Claiming “full Blu Age” before Phase 7 exit criteria
- Skipping human review for CRITICAL residual risk
