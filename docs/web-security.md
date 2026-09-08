# Web UI dependency security (Next.js)

ShadowStack’s dashboard (`apps/web`) runs **Next.js 15.5.x** (patched line).
Backend / Java dependency risk is gated by Trivy in CI (`security-scan`); the
web tree is tracked separately via `npm audit`.

## Current posture

| Item | Status |
|------|--------|
| Next.js pin | `15.5.25` (patched **15.5.x** line) |
| React | 19.x (peer of Next 15) |
| HIGH / CRITICAL (`npm audit --audit-level=high`) | **0** after 15.5.25 upgrade |
| Security headers | CSP + `X-Frame-Options` + `nosniff` + Referrer-Policy + Permissions-Policy in `next.config.js` |
| HSTS | Set at **TLS ingress** (not in Next config — see API HSTS headers for HTTPS terminators) |

## Local commands

```bash
cd apps/web
npm audit
npm audit --audit-level=high
npm audit --audit-level=critical   # CI-equivalent floor
npm run build
```

## CI

Job **`web-npm-audit`** in `.github/workflows/ci.yml` runs
`npm audit --audit-level=critical` in `apps/web`.

See also: `docs/soc2-controls.md` (CC9.3), `docs/soc2-auditor-pack.md`,
`docs/security-assessment.md`.
