# Roadmap — Full Blu Age–class COBOL → Java

> **Today:** COBOL **translate** is a **javac-gated semantic rehost MVP**
> (`cobol-to-java-semantic-rehost`) for a practical PROCEDURE DIVISION /
> WORKING-STORAGE subset. That is **toward** Blu Age–class rehost, **not**
> certified whole-system Blu Age parity.
>
> This roadmap is the path from MVP → enterprise mainframe rehost class
> (CICS / IMS / JCL / data / batch), still fail-closed with human review.

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
5. **Incremental certificates** — each phase expands the *proven* subset; unproven constructs stay explicit gaps.

---

## Phase 0 — Baseline (done / shipping)

| Capability | Status |
|------------|--------|
| COBOL preserving (`cobc -fsyntax-only`) | Full syntax-gated |
| COBOL→Java semantic rehost MVP | javac-gated subset |
| Identity / empty diffs rejected | Enforced |
| Multi-lang Pilot B launch scope | Documented |

**Exit:** Demo + Meta show translate as MVP with javac gate; claim language honest.

---

## Phase 1 — Language coverage depth (core dialect)

**Goal:** Expand the static COBOL subset that reliably becomes idiomatic Java.

| Workstream | Deliverable | Gate |
|------------|-------------|------|
| Data division | PIC clauses, REDEFINES, OCCURS, USAGE COMP/COMP-3 → typed Java fields | Unit + javac |
| Conditionals / loops | IF/EVALUATE/PERFORM UNTIL/VARYING → structured Java | javac + golden snippets |
| PERFORM graphs | PERFORM THRU / SECTION graphs without incorrect fall-through | AST + call-graph tests |
| Copybooks | COPY expansion with replacing | Project-level compile |
| Nested programs | Inner programs → nested types / packages | javac |

**Exit criteria:** Representative retail/batch sample (no CICS) compiles under javac; verify pipeline PASS rate documented; gap list published for unsupported verbs.

---

## Phase 2 — Files & batch data

**Goal:** Sequential / indexed / relative file I/O and batch restart semantics.

| Workstream | Deliverable | Gate |
|------------|-------------|------|
| FD / SELECT | File descriptors → Java IO / NIO adapters | Integration tests |
| READ/WRITE/REWRITE | Mapped operations with status keys | Behavioral golden |
| SORT / MERGE | Explicit support or documented fail-closed reject | Gate FAIL if unsupported |
| VSAM-shaped access | Adapter interface + one reference impl | Contract tests |

**Exit criteria:** Batch file sample modernizes with PASS certificates for supported FD types; unsupported → FAIL with clear diagnostics.

---

## Phase 3 — Transactional online (CICS class)

**Goal:** Map CICS-like verbs to a Java transaction/runtime façade (not bit-identical IBM CICS).

| Workstream | Deliverable | Gate |
|------------|-------------|------|
| Program control | LINK / XCTL / RETURN model | Runtime contract tests |
| Transient data / TSQ | Queue abstractions | Integration |
| File / DB under syncpoint | Commit / rollback façade | Fail-closed on missing sync |
| BMS / screens (subset) | Map → DTO / API boundary (start narrow) | Review + tests |
| Security / USERID | Propagation into Java security context | Auth tests |

**Exit criteria:** One online sample with LINK/XCTL + syncpoint modernizes; Meta claims “CICS-façade subset” not “full CICS.”

---

## Phase 4 — Data stores (IMS / DB2 class)

**Goal:** Hierarchical + relational access patterns used by mainframe apps.

| Workstream | Deliverable | Gate |
|------------|-------------|------|
| IMS DL/I subset | GU/GHU/GN/ISRT/REPL/DLET → repository API | Contract + FAIL on unsupported |
| DB2 / embedded SQL | EXEC SQL → JDBC / jOOQ / Spring Data | Compile + SQL parse gate |
| Dual-mode | Same program files + DB | Integration sample |

**Exit criteria:** One IMS-shaped and one SQL-shaped sample PASS; unsupported PCB/SQL → FAIL.

---

## Phase 5 — JCL & operations

**Goal:** Batch job orchestration understanding, not a full mainframe scheduler clone.

| Workstream | Deliverable | Gate |
|------------|-------------|------|
| JCL parse | JOB/STEP/DD → pipeline graph | Parser tests |
| Step dependencies | Ordering + condition codes | Graph verify |
| Proc / INCLUDE | Expansion | Project compile |
| Restart / DISP | Documented mapping or FAIL | Explicit |

**Exit criteria:** Multi-step job sample produces an executable Java batch graph with evidence; exotic JCL → FAIL list.

---

## Phase 6 — Behavioral equivalence at scale

**Goal:** Move from “compiles” to “behaves.”

| Workstream | Deliverable | Gate |
|------------|-------------|------|
| Golden masters | Input/output corpora for batch | GoldenMaster layer |
| Differential fuzz | Property tests on numeric/PIC edge cases | CI |
| Multi-program certificates | Cross-program LINK evidence packs | Review pack |
| Residual risk | Blast-radius across program graph | RiskPosterior + call graph |

**Exit criteria:** Customer-visible equivalence report; HIGH residual requires dual review.

---

## Phase 7 — Productization & parity bar

**Goal:** Operable “Blu Age–class” offering without false equivalence claims.

| Workstream | Deliverable |
|------------|-------------|
| Gap browser | UI listing unsupported verbs/PCBs/JCL per project |
| Migration waves | Phased cutover (preserve COBOL + translate islands) |
| Runtime pack | Supported façades versioned (CICS/IMS/files) |
| Claim language update | Meta + `docs/converter-parity.md` only after exit criteria |

**Exit criteria:** Written parity matrix vs Blu Age–class surfaces; only then consider upgrading marketing from “MVP / subset” to “Blu Age–class rehost for supported surfaces.”

---

## Suggested sequencing (engineering order)

```text
Phase 1 (dialect) ──► Phase 2 (files)
         │                    │
         └──────────► Phase 6 (early goldens on subset)
                              │
Phase 3 (CICS façade) ◄───────┤
Phase 4 (IMS/SQL)     ◄───────┤
Phase 5 (JCL)         ◄───────┘
                              │
                         Phase 7 (productize)
```

Phases 3–5 can proceed in parallel tracks after Phase 1 is stable; each stays fail-closed.

## Non-goals (explicit)

- Bit-identical IBM CICS/IMS replacement
- Guaranteeing every CUSTOMER dialect extension without a gap entry
- Skipping human review for CRITICAL residual risk
- Claiming “full Blu Age” before Phase 7 exit criteria

## Tracking

| Artifact | Purpose |
|----------|---------|
| `docs/converter-parity.md` | Claim language |
| Meta `/api/v1/meta/languages` | Machine-readable status |
| This roadmap | Phase plan |
| Verify evidence JSON | Per-patch proof |
