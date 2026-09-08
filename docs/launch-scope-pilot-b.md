# Launch scope — Pilot B (multi-lang syntax-gated converters)

**Decision:** ShadowStack launches as a **multi-language syntax-gated modernization workbench**.

## In scope (Pilot B)

| Track | Claim | Native gate |
|-------|--------|-------------|
| Java | Full fail-closed AST converter | `javac` / JDT 7-layer verify |
| Python | Full syntax-gated converter | `python3` + `py_compile` |
| JavaScript / TypeScript | Full syntax-gated converter | `node --check` |
| C# | Full syntax-gated converter | `dotnet build` |
| COBOL **preserving** | Full syntax-gated converter | `cobc -fsyntax-only` |
| COBOL **translate** | Blu Age–class for **supported surfaces** | `javac` (`cobol-to-java-semantic-rehost`) |

Product loop: detect → generate → verify (fail-closed) → **human review** → accept/reject.  
Risk: evidence-weighted residual risk + calibration (see `docs/risk-process.md`).

## Honest marketing language

**Say:**
- “Multi-lang syntax-gated converters with fail-closed verify and human review”
- “Industry *class* of OpenRewrite / Upgrade Assistant / GnuCOBOL-gated tools”
- “COBOL→Java Blu Age–class for supported surfaces (javac-gated)”
- “SOC 2 control readiness / audit-ready controls” (when using the auditor pack)

**Do not say:**
- “SOC 2 certified / compliant / Type II” (needs external auditor report)
- “Full Blu Age” / bit-identical IBM CICS·IMS·VSAM·BMS / licensed Blu Age product clone
- “Vault / CMEK live in production” unless those clusters are actually deployed
- “Penetration tested by Cursor agent” as a substitute for an independent vendor report

## Launch checklist (Pilot B)

1. Merge risk accuracy + this readiness pack to `main`
2. Production secrets / Postgres / worker dequeue / optional encryption + OIDC (`docs/secrets-and-encryption.md`, `docs/deployment-guide.md`)
3. Demo gate: `scripts/demo.sh` shows pending patches for **all five** seeded languages
4. Keep claim language aligned with `docs/converter-parity.md` and `docs/blu-age-cobol-roadmap.md`
5. Web on Next **15.5.x** with CSP; API security headers; `npm audit --audit-level=high` clean
6. Prod `shadowstack.cobol.fail-on-gaps=true`; enterprise image can require `cobc` via `INSTALL_COBC=1`

## Out of scope for Pilot B (tracked separately)

| Item | Doc |
|------|-----|
| Independent pen-test vendor | `docs/security-assessment.md` (agent assessment ≠ vendor) |
| SOC 2 Type I/II | `docs/soc2-auditor-pack.md` |
| Cluster Vault / CMEK / ESO | `docs/secrets-and-encryption.md` |
| Bit-identical IBM / licensed Blu Age clone | `docs/blu-age-cobol-roadmap.md` |
