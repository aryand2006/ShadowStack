# Roadmap — Blu Age–class COBOL → Java

> **Status on `main`:** Phases **0–7 shipped for supported surfaces** —
> dialect, COPY, CALL/LINKAGE, sequential files (+ START/DELETE/REWRITE),
> SORT/MERGE USING/GIVING, embedded CICS/SQL MVP, JCL runner + COND,
> Meta gap browser + review proofArtifacts.
>
> **Claim:** **Blu Age–class for supported surfaces.**
> Still **not** bit-identical IBM CICS/IMS/VSAM/BMS or a certified Blu Age product clone.

## North star

A customer can point ShadowStack at a **multi-program COBOL application**
(online + batch), get **compilable Java**, with **behavioral evidence** and
**human-gated** promotion — covering Blu Age–class surfaces: transactions,
databases (stub), files, JCL, and cross-program control flow.

## Principles

1. **Fail-closed** — missing gate / unverified semantics → FAIL.
2. **Evidence over vibes** — certificates / layer results / goldens.
3. **Human review** for HIGH/CRITICAL residual risk.
4. **Honest Meta labels** — Blu Age–class for supported surfaces; remaining gaps listed.
5. **Gap lists** — `Result.unsupportedGaps`, patch `translateGaps`, Meta `/cobol-rehost`.

---

## Phase matrix

| Phase | Status | Highlights |
|-------|--------|------------|
| 0 Baseline | ✅ | cobc preserving + javac translate |
| 1 Dialect | ✅ | PIC/USAGE/OCCURS/REDEFINES/PERFORM/COPY |
| 2 Files | ✅ MVP+ | SELECT/FD/01, sequential I/O, AT END, I-O REWRITE, START/DELETE |
| 3 CICS | ✅ MVP | Inline `__cics*` + `InMemoryCicsFacade` (LINK/XCTL/TSQ/SYNCPOINT) |
| 4 IMS/SQL | 🟡 | `ImsFacade`; EXEC SQL → fail-closed `__sqlExec` (host JDBC) |
| 5 JCL | ✅ MVP+ | `JclJobGraph` + `JclJobRunner` + COND=(code,op) |
| 6 CALL/goldens | ✅ | Program graph, CALL USING, LINKAGE bind, HELLOSS golden |
| 7 Productization | ✅ | Meta `/cobol-rehost` + review proofArtifacts; claim upgraded |

## Phase 7 exit criteria

- [x] Gap browser in Meta (`/api/v1/meta/cobol-rehost`)
- [x] Gaps visible on review (`proofArtifacts.translateGaps` / `resolvedCalls`)
- [x] Written parity matrix vs Blu Age surfaces (this doc)
- [x] Deep surfaces MVP: CICS embed, SORT, LINKAGE, PERFORM THRU, JCL COND
- [x] Marketing upgrade to **“Blu Age–class for supported surfaces”**
- [ ] Bit-identical IBM CICS/IMS/VSAM/BMS — **non-goal**

## API

| Endpoint | Purpose |
|----------|---------|
| `GET /api/v1/meta/cobol-rehost` | Supported surfaces + known gaps + phase status |
| Patch metadata `translateGaps` / `resolvedCalls` | Per-patch gap browser |

## Remaining gaps (honest)

- Nested programs; deep FD groups/OCCURS in records
- VSAM / INDEXED / RELATIVE
- BMS / 3270 screens
- Full IMS DL/I in generated code
- JCL PROC/INCLUDE + cataloged datasets
- True BY REFERENCE shared memory / POINTER
- Dynamic CALL (non-literal target)

## Non-goals

- Bit-identical IBM CICS/IMS/VSAM
- Claiming a licensed Blu Age product replacement
- Skipping human review for CRITICAL residual risk

## Fixtures

`HELLOSS`, `RETAIL`, `COPYDEMO`, `BATCHIO`, `DRIVER`/`WORKER`, `PAYDEMO.jcl`, `examples/legacy-cobol/golden/`
