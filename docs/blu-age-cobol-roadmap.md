# Roadmap — Blu Age–class COBOL → Java

> **Status on `main`:** Phases **0–7 Blu Age–class for supported surfaces**, including
> nested PROGRAM-ID emit, CALL BY REFERENCE heap MVP, BMS SEND/RECEIVE MAP,
> deep FD groups, dynamic CALL, EXEC DLI, and JCL INCLUDE + PROC expand.
>
> **Claim:** **Blu Age–class for supported surfaces.**
> Still **not** bit-identical IBM VSAM/BMS/IDCAMS or a licensed Blu Age product clone.

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
| 1 Dialect | ✅ | PIC/USAGE/OCCURS/REDEFINES/PERFORM/COPY + INSPECT/UNSTRING/SEARCH/CORR/GIVING |
| 2 Files | ✅ MVP+ | Sequential + INDEXED/RELATIVE + deep FD + multi OPEN + indexed AT END |
| 3 CICS | ✅ MVP+ | Inline `__cics*` + SEND/RECEIVE MAP (`__bms*`) + façades |
| 4 IMS/SQL | ✅ MVP | EXEC DLI inline + `InMemoryImsFacade`; EXEC SQL stub |
| 5 JCL | ✅ MVP+ | Runner + COND + INCLUDE + PROC + IF/THEN cond + &symbolics |
| 6 CALL/goldens | ✅ | Literal + dynamic CALL, LINKAGE, BY REFERENCE heap, HELLOSS golden |
| 7 Productization | ✅ | Meta `/cobol-rehost` + review proofArtifacts |

## Phase 7 exit criteria

- [x] Gap browser in Meta (`/api/v1/meta/cobol-rehost`)
- [x] Gaps visible on review (`proofArtifacts.translateGaps` / `resolvedCalls`)
- [x] Written parity matrix vs Blu Age surfaces (this doc)
- [x] Deep surfaces MVP: CICS embed, SORT, LINKAGE, PERFORM THRU, JCL COND
- [x] Nested PROGRAM-ID sibling emit, BY REFERENCE heap, BMS maps, PROC expand
- [x] Marketing upgrade to **“Blu Age–class for supported surfaces”**
- [ ] Bit-identical IBM CICS/IMS/VSAM/BMS — **non-goal**

## API

| Endpoint | Purpose |
|----------|---------|
| `GET /api/v1/meta/cobol-rehost` | Supported surfaces + known gaps + phase status |
| Patch metadata `translateGaps` / `resolvedCalls` | Per-patch gap browser |

## Remaining gaps (honest non-goals / stretch)

- Bit-identical IBM CICS/IMS/VSAM / BMS / IDCAMS
- POINTER / ADDRESS OF / BASED linkage (beyond string-key BY REFERENCE heap)
- Cataloged dataset resolution for JCL DD (PROC/INCLUDE member expand is supported)
- Licensed Blu Age product clone
- Skipping human review for HIGH/CRITICAL residual risk

## Non-goals

- Bit-identical IBM CICS/IMS/VSAM/BMS
- Claiming a licensed Blu Age product replacement
- Skipping human review for CRITICAL residual risk

## Fixtures

`HELLOSS`, `RETAIL`, `COPYDEMO`, `BATCHIO`, `DRIVER`/`WORKER`, `PAYDEMO.jcl`, `examples/legacy-cobol/golden/`

## Translate verify: fail-on-gaps

| Profile | Default | Override |
|---------|---------|----------|
| `demo` | `false` | `SHADOWSTACK_COBOL_FAIL_ON_GAPS=true` |
| `prod` | `true` | `SHADOWSTACK_COBOL_FAIL_ON_GAPS=false` |
| Adapter / CLI | `false` unless `-Dshadowstack.cobol.fail-on-gaps=true` | env or sysprop |

When enabled, non-empty patch metadata `translateGaps` fails the translate verify layer (`CobolAdapter`).
