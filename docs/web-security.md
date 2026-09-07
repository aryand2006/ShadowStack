# Web UI dependency security (Next.js)

ShadowStack’s dashboard (`apps/web`) stays on **Next.js 14.2.x** for App Router
compatibility with the current UI. Backend / Java dependency risk is gated by
Trivy in CI (`security-scan`); the web tree is **skipped** there so Node
advisories are tracked separately.

## Current posture (as of last hygiene pass)

| Item | Status |
|------|--------|
| Next.js pin | `14.2.35` (newest **14.2.x** line) |
| Non-breaking `npm audit fix` | Applied (browserslist, lodash, nanoid, picomatch, related transitive bumps) |
| Remaining HIGH | **next** + nested **postcss** — npm reports fixes only via `npm audit fix --force` → **Next 16.x** (breaking) |
| CRITICAL | Gate: `npm audit --audit-level=critical` (CI job `web-npm-audit`) |

Upgrading to Next 15/16 is a **product migration** (React 19 / breaking APIs),
not a drop-in security patch. Until that upgrade ships:

1. Keep Next on the latest **14.2.x** patch.
2. Re-run `npm audit fix` (without `--force`) on dependency hygiene PRs.
3. Treat remaining next/postcss HIGHs as **accepted residual risk** for the
   14.2 line; schedule a Next 15+ upgrade for full advisory clearance.
4. Prefer not exposing the Image Optimizer / untrusted `remotePatterns`, and
   keep the UI behind the same auth boundary as the API in production.

## Local commands

```bash
cd apps/web
npm audit                 # full report
npm audit fix             # non-breaking only
npm audit --audit-level=critical   # CI-equivalent gate
npm run build
```

## CI

Job **`web-npm-audit`** in `.github/workflows/ci.yml` runs
`npm audit --audit-level=critical` in `apps/web` so CRITICAL findings fail the
pipeline without forcing a Next 15/16 bump for HIGH-only advisories on 14.2.x.

See also: `docs/soc2-controls.md` (CC9.3), `docs/soc2-auditor-pack.md`.
