# SOC 2 Auditor Pack (one-pager)

> **Claim language:** This pack supports **"SOC 2 control readiness / audit-ready controls"**.  
> ShadowStack is **not** SOC 2 certified until an independent AICPA-aligned CPA firm issues a Type I/II report.

| Field | Value |
|-------|--------|
| **Product** | ShadowStack — enterprise modernization workbench (API + worker + Next.js UI) |
| **Trust Service Criteria** | Security (common criteria CC1–CC9); Availability / Confidentiality as scoped by the engagement letter |
| **Report target** | Type I first (design as of a date), then Type II (operating effectiveness) |
| **Control matrix** | [`docs/soc2-controls.md`](soc2-controls.md) |
| **Evidence export** | `scripts/export-soc2-evidence.sh` |

---

## 1. Scope (suggested engagement boundary)

**In scope (product / in-repo controls):**

- Spring Boot API (`apps/api`) — JWT/Basic (+ optional OIDC), RBAC, review SoD, durable `audit_log`, retention job, control evidence API
- Worker (`apps/worker`) — VERIFY dequeue with `FOR UPDATE SKIP LOCKED`, 7-layer verification
- Postgres + Flyway schema (`packages/migration-corpus` migrations V2/V6+)
- K8s NetworkPolicy + secrets/encryption docs (`infra/k8s/`, `docs/secrets-and-encryption.md`)
- CI vulnerability scan — Trivy HIGH/CRITICAL (`.github/workflows/ci.yml` `security-scan`)

**Out of scope unless customer expands the letter:**

- Customer IdP / SSO configuration beyond the optional `oidc` profile
- Cloud account IAM, VPC, WAF, and Postgres TDE/CMEK provisioning
- Physical datacenter / SaaS subprocessors not listed in the customer’s system description
- Penetration test and auditor contract (customer-owned)

---

## 2. System description (short)

ShadowStack modernizes legacy code through a **fail-closed** pipeline: detect → analyze → generate patches → multi-layer verify → **human review** (accept/reject). Auto-apply is off by default. Multi-tenant rows carry `org_id`. Sensitive patch artifacts may be AES-GCM encrypted when `ENCRYPTION_KEY_BASE64` is set. Audit actions land in `audit_log` (append-oriented app grants; soft-delete via `deleted_at` for retention).

```
Web (Next.js) → API (Spring) → Postgres
                    ↕
                 Worker (VERIFY)
```

---

## 3. Control matrix pointer

Use **[`docs/soc2-controls.md`](soc2-controls.md)** as the readiness matrix (Implemented / Partial / Target + evidence paths).  
Pre-auditor done-vs-customer checklist is under **Path to certification** in that file.

---

## 4. Evidence API

```http
GET /api/v1/compliance/evidence
Authorization: Basic <ADMIN> | Bearer <ADMIN JWT>
```

Returns:

1. **Banner** — allowed vs forbidden claim language + doc pointer  
2. **Catalog** — static TSC control IDs / status / evidence paths  
3. **registeredEvidence** — rows from `ss_control_evidence` (seeded pointers + any org-added evidence)

Export helper (when `API_URL` + credentials are set):

```bash
./scripts/export-soc2-evidence.sh
# writes ./soc2-evidence-export/evidence.json and audit-log.csv
```

Related ADMIN endpoints:

| Endpoint | Purpose |
|----------|---------|
| `GET /api/v1/audit/logs` | Filtered audit query |
| `GET /api/v1/audit/export` | CSV export of `audit_log` |

---

## 5. How to request a Type I engagement

1. Stabilize production with non-demo profiles, Flyway V6+ grants, NetworkPolicies, TLS at ingress, Vault/External Secrets (or equivalent), and retention schedules.  
2. Appoint an executive sponsor and gather the system description, data-flow diagram, and subprocessor list.  
3. Contract an **AICPA-aligned CPA firm** experienced with SaaS SOC 2.  
4. Provide this pack + `docs/soc2-controls.md` + exported evidence JSON/CSV + CI Trivy artifacts as the initial PBC (Prepared By Client) set.  
5. Agree Type I “as of” date; remediate design gaps before fieldwork ends.  
6. Schedule Type II observation window after Type I (typically 3–12 months).

Until the report is issued, contracts and marketing may only say **SOC 2 control readiness / audit-ready controls**.

---

## 6. Sample population queries (auditor testing aids)

Run against the application Postgres (adjust schema/role as deployed). Soft-deleted audit rows use `deleted_at IS NOT NULL`.

### Audit log — population & samples

```sql
-- Population: all active audit events in the Type I/II window
SELECT COUNT(*) AS population
FROM audit_log
WHERE deleted_at IS NULL
  AND timestamp >= :period_start
  AND timestamp <  :period_end;

-- Stratified sample (example: 25 random rows)
SELECT id, action, entity_type, entity_id, actor_id, actor_role, org_id, timestamp, ip_address
FROM audit_log
WHERE deleted_at IS NULL
  AND timestamp >= :period_start
  AND timestamp <  :period_end
ORDER BY random()
LIMIT 25;

-- Confirm append-only app posture (no DELETE privilege for app role)
SELECT grantee, privilege_type
FROM information_schema.role_table_grants
WHERE table_name = 'audit_log' AND grantee = 'shadowstack';
```

### Retention job

```sql
-- Soft-deleted (retained for purge window) vs active
SELECT
  COUNT(*) FILTER (WHERE deleted_at IS NULL) AS active_rows,
  COUNT(*) FILTER (WHERE deleted_at IS NOT NULL) AS soft_deleted_rows,
  MIN(deleted_at) AS oldest_soft_delete,
  MAX(deleted_at) AS newest_soft_delete
FROM audit_log;

-- Evidence that retention audited itself (action naming may vary by build)
SELECT id, action, actor_id, timestamp, details
FROM audit_log
WHERE deleted_at IS NULL
  AND (action ILIKE '%RETENTION%' OR entity_type ILIKE '%retention%')
ORDER BY timestamp DESC
LIMIT 50;
```

Config: `shadowstack.retention.*` in `apps/api/src/main/resources/application.yml`; job: `RetentionCleanupJob` (`!demo`).

### Separation of duties (SoD)

```sql
-- Review decisions where actor matches patch author (should be ADMIN override only)
-- Join shape depends on how review events are logged; start from audit_log:
SELECT id, action, entity_id, actor_id, actor_role, timestamp, details
FROM audit_log
WHERE deleted_at IS NULL
  AND action ILIKE '%REVIEW%'
  AND timestamp >= :period_start
ORDER BY timestamp DESC
LIMIT 100;
```

Code enforcement: `ReviewService.enforceSeparationOfDuties` — REVIEWER cannot accept/reject own `createdBy` patches; ADMIN may override (logged).

### Encryption at rest

```sql
-- Application AES-GCM ciphertext uses envelope prefix "v1:" when ENCRYPTION_KEY_BASE64 is set
SELECT id,
       LEFT(unified_diff, 8) AS unified_diff_prefix,
       LEFT(verification_json, 8) AS verification_prefix,
       LEFT(review_json, 8) AS review_prefix
FROM ss_patches
WHERE unified_diff LIKE 'v1:%'
   OR verification_json LIKE 'v1:%'
   OR review_json LIKE 'v1:%'
LIMIT 20;
```

Operational checklist: `ENCRYPTION_KEY_BASE64` set in prod; Postgres TDE/CMEK per `docs/secrets-and-encryption.md`.

### Trivy CI (CC9.3)

- Workflow: `.github/workflows/ci.yml` job **`security-scan`**
- Severity gate: **HIGH,CRITICAL** (`exit-code: 1`, `ignore-unfixed: true`)
- Evidence: download the GitHub Actions run log / artifact for the observation window; retain at least one green run per change freeze and any failed→fixed remediations
- Web UI deps are excluded from Trivy FS (`apps/web`); see `docs/web-security.md` and the `web-npm-audit` CI job (`npm audit --audit-level=critical`)

---

## 7. Quick file index

| Artifact | Path |
|----------|------|
| Control matrix | `docs/soc2-controls.md` |
| This pack | `docs/soc2-auditor-pack.md` |
| Secrets / encryption | `docs/secrets-and-encryption.md` |
| Web npm advisories | `docs/web-security.md` |
| Evidence registry schema + seed | `V6__soc2_control_readiness.sql` (+ later seeds if present) |
| Evidence API | `ControlEvidenceController.java` |
| Export script | `scripts/export-soc2-evidence.sh` |
