# Security assessment (agent-led defensive review)

> **Not a substitute for an independent penetration test.**  
> This is a Cursor-agent defensive assessment of ShadowStack’s own stack
> (authz checks, config review, dependency audit, live demo-profile probes).  
> For customer / SOC 2 evidence, still hire a pen-test vendor and keep their report.

**Date:** 2026-09-07  
**Target:** local `demo` profile API (`:8080`) + `apps/web` npm audit  
**Launch scope:** Pilot B — multi-lang syntax-gated converters (`docs/launch-scope-pilot-b.md`)  
**Raw probe log:** `docs/security-assessment-raw.log`

## Method (what we did / did not do)

| Did | Did not |
|-----|---------|
| Verify unauthenticated callers get **401** on protected APIs | Weaponized exploit chains / PoC malware |
| Bad password / garbage JWT / SQLi-looking login rejection | Blind scanning of third-party infrastructure |
| CORS allow-list behavior for hostile `Origin` | Social engineering / physical |
| Actuator exposure check | Full cloud IAM / K8s RBAC review |
| Response security headers smoke | Formal ASVS / PTES engagement |
| `npm audit` on `apps/web` | Claiming “SOC 2 pen-test complete” |

## Results summary

| Area | Result | Severity |
|------|--------|----------|
| API authn required for `/api/v1/**` | **Pass** — unauth → 401 | — |
| Bad Basic / bad login / garbage JWT | **Pass** — 401 | — |
| Admin-only actuator (non-health) | **Pass** — unauth prometheus/metrics/info → 401 | — |
| Hostile CORS origin | **Pass** — no `Access-Control-Allow-Origin` for `evil.example`; preflight 403 | — |
| Path traversal style project id | **Pass** — 404 | — |
| Oversized login body | **Pass** — 401 (no crash observed) | — |
| Headers | Partial — `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`; no HSTS on HTTP demo | Low (prod TLS) |
| Swagger UI unauthenticated | **Open** (`/swagger-ui/index.html` → 200) | **Medium** (lock down in prod) |
| OpenAPI `/api-docs` | 500 on demo probe | Low (fix path / disable in prod) |
| Demo credentials `admin`/`admin` | Expected on demo; **forbidden in prod** | High if shipped to prod |
| CSRF disabled | By design for stateless JWT API | Info — ensure cookie auth never added without CSRF |
| Health unauthenticated | `503` on demo (no DB) but still public | Info |
| npm (`apps/web`) | **0 critical**, **2 high** (Next 14.2.x + nested postcss; fix needs Next 16 breaking bump) | Medium accepted residual (`docs/web-security.md`) |
| Unauth request hang (pre-fix) | Default Basic entry point could stall some clients | **Fixed** — `HttpStatusEntryPoint(UNAUTHORIZED)` |
| Demo boot with calibration service | Failed without JDBC | **Fixed** — optional `JdbcTemplate` |

## Findings & remediation

### F1 — Swagger / OpenAPI publicly reachable (Medium)
**Risk:** Schema discloses API surface to unauthenticated users.  
**Fix (prod):** Disable springdoc or protect with ADMIN auth; block at ingress.

### F2 — Demo secrets must never reach production (High if mis-deployed)
**Risk:** `admin`/`admin` and placeholder JWT on demo profile.  
**Fix:** Prod profile + `SecurityPropertiesValidator`; override `JWT_SECRET`, `SECURITY_PASSWORD` (`docs/secrets-and-encryption.md`).

### F3 — Next.js 14.2 HIGH advisories (Medium, accepted for Pilot B)
**Risk:** Known GHSA on Next / postcss; CRITICAL gate already green in CI.  
**Fix:** Schedule Next 15/16 migration; until then keep latest 14.2.x (`docs/web-security.md`).

### F4 — Missing HSTS / CSP on API responses (Low on HTTP demo)
**Risk:** Browser transport / XSS hardening incomplete at API layer.  
**Fix:** Terminate TLS at ingress with HSTS; add CSP on Next.js app.

### F5 — Independent pen-test still required (Process)
**Risk:** Agent review misses business-logic and multi-tenant isolation abuse at scale.  
**Fix:** Engage vendor; give staging URL + test orgs; remediate; attach report to SOC pack.

## Fixes shipped with this assessment

1. `HttpStatusEntryPoint` for immediate 401 (API + OIDC security configs).  
2. `RulePriorCalibrationService` works without JDBC (demo profile).

## Sign-off language

You may say: **“Internal defensive security assessment completed; critical authz checks passed on demo API; residual findings tracked.”**  
You may **not** say: **“Penetration tested / certified secure by third party”** based on this document alone.
