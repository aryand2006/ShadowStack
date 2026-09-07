# Roadmap — Full Blu Age–class COBOL → Java

> **Status on `main`:** Phase **0–1 ✅**, Phase **2 sequential I/O MVP** (+ SELECT/AT END),
> Phases **3–5 façades**, Phase **6 ✅ CALL graph + goldens + CALL USING MVP**,
> Phase **7 🟡 MVP** Meta gap browser + review proofArtifacts.
> Still **not** certified whole-system Blu Age parity.

## North star

A customer can point ShadowStack at a **multi-program COBOL application**
(online + batch), get **compilable Java**, with **behavioral evidence** and
**human-gated** promotion — covering Blu Age–class surfaces: screens/transactions,
databases, files, JCL, and cross-program control flow.

## Principles

1. **Fail-closed** — missing gate / unverified semantics → FAIL.
2. **Evidence over vibes** — certificates / layer results / goldens.
3. **Human review** for HIGH/CRITICAL residual risk.
4. **Honest Meta labels** — no “full Blu Age” until Phase 7 exit.
5. **Gap lists** — `Result.unsupportedGaps`, patch `translateGaps`, Meta `/cobol-rehost`.

---

## Phase matrix

| Phase | Status | Highlights |
|-------|--------|------------|
| 0 Baseline | ✅ | cobc preserving + javac translate MVP |
| 1 Dialect | ✅ | PIC/USAGE/OCCURS/REDEFINES/PERFORM/COPY |
| 2 Files | 🟡 MVP | Sequential I/O helpers, SELECT/AT END; no VSAM |
| 3 CICS | 🟡 façade | `CicsFacade` fail-closed; EXEC CICS → gap |
| 4 IMS/SQL | 🟡 façade | `ImsFacade`; EXEC SQL → gap |
| 5 JCL | 🟡 parser | `JclJobGraph` + `PAYDEMO.jcl` |
| 6 CALL/goldens | ✅ | `CobolProgramGraph`, project translate, HELLOSS golden, CALL USING→String[] MVP |
| 7 Productization | 🟡 MVP | Meta gap browser + review proofArtifacts; deep surfaces still open |

## Phase 7 exit criteria (not yet met)

- [x] Gap browser in Meta (`/api/v1/meta/cobol-rehost`)
- [x] Gaps visible on review (`proofArtifacts.translateGaps` / `resolvedCalls` from verify)
- [x] Written parity matrix vs Blu Age surfaces (this doc)
- [ ] Deep CICS/IMS/VSAM/JCL runner — still open
- [ ] Marketing upgrade to “Blu Age–class for supported surfaces” — **blocked** until deep surfaces ship

## API

| Endpoint | Purpose |
|----------|---------|
| `GET /api/v1/meta/cobol-rehost` | Supported surfaces + known gaps + phase status |
| Patch metadata `translateGaps` / `resolvedCalls` | Per-patch gap browser |

## Non-goals

- Bit-identical IBM CICS/IMS
- Claiming “full Blu Age” before remaining deep surfaces
- Skipping human review for CRITICAL residual risk

## Fixtures

`HELLOSS`, `RETAIL`, `COPYDEMO`, `BATCHIO`, `DRIVER`/`WORKER`, `PAYDEMO.jcl`, `examples/legacy-cobol/golden/`
