# Secrets & Encryption at Rest

ShadowStack separates **secret injection** (Vault / Kubernetes Secrets / compose env)
from **encryption at rest** (cloud disk TDE/CMEK + optional application-level AES-GCM
for patch artifacts).

## Secrets: compose vs production

| Environment | How secrets are supplied | Notes |
|-------------|--------------------------|--------|
| **demo** profile | Defaults in `application-demo.yml` | No Postgres; JWT placeholder OK; encryption off |
| **docker-compose** | Env vars with **local DEV defaults** | Defaults exist so the stack boots; **production MUST override** `JWT_SECRET`, `SECURITY_*`, `DB_PASSWORD` |
| **prod / k8s** | `infra/k8s/secrets.yaml` or External Secrets → Vault | `SecurityPropertiesValidator` rejects weak JWT placeholders |

### Required production secrets

| Variable | Purpose | Constraints |
|----------|---------|-------------|
| `JWT_SECRET` | HS256 signing key | ≥ 32 chars; must **not** contain: `changeme`, `demo-only`, `local-dev-only`, `shadowstack_dev`, `password`, `secret123` |
| `SECURITY_USER` | Bootstrap admin username | Required when demo profile is off |
| `SECURITY_PASSWORD` | Bootstrap admin password | ≥ 12 characters |
| `DB_USER` / `DB_PASSWORD` | Postgres credentials | Never commit real values |
| `ENCRYPTION_KEY_BASE64` | Optional AES-256 for patch artifacts | Base64 of **exactly 32 bytes**; unset = plaintext (demo OK) |

Generate strong values:

```bash
# JWT (≥ 32 chars of entropy)
openssl rand -base64 48

# AES-256 application key
openssl rand -base64 32
```

## Vault integration (External Secrets Operator)

Documentation-grade manifests live at:

- `infra/k8s/external-secret-vault.yaml` — `SecretStore` + `ExternalSecret`
- Vault KV v2 path: **`shadowstack/data/api`** (ESO remote key `shadowstack/api`)
- Target Kubernetes Secret: **`shadowstack-secrets`** (wired into `api-deployment.yaml` / `worker-deployment.yaml`)

### Bootstrap steps (outline)

1. Install [External Secrets Operator](https://external-secrets.io/).
2. Enable Vault Kubernetes auth; create a role/policy that can read `secret/data/shadowstack/api`.
3. Write the KV payload (`JWT_SECRET`, `SECURITY_USER`, `SECURITY_PASSWORD`, `DB_*`, optional `ENCRYPTION_KEY_BASE64`).
4. Edit `external-secret-vault.yaml` (Vault URL, role, mount) and apply it in the `shadowstack` namespace.
5. Confirm `kubectl get secret shadowstack-secrets -n shadowstack` is populated; roll API/worker pods if needed.

Do **not** apply the template unchanged — server URL and role are placeholders.

### Rotation runbook

| Secret | Procedure |
|--------|-----------|
| `JWT_SECRET` | Update Vault → wait for ESO refresh → rolling restart API/worker. Existing JWTs signed with the old key become invalid (users re-login). Prefer a short dual-key window only if you add multi-key support later. |
| `SECURITY_PASSWORD` | Update Vault → restart pods → update operator runbooks / password managers. |
| `DB_PASSWORD` | Rotate in Postgres + Vault atomically; restart pods; verify Flyway/health. |
| `ENCRYPTION_KEY_BASE64` | **Breaking for ciphertext.** Prefer: (1) decrypt/re-encrypt offline with a migration job, or (2) introduce a new key version in app code before retiring the old key. Do not flip the key without a re-encryption plan. |

## Application-level encryption (patch artifacts)

When `ENCRYPTION_KEY_BASE64` (or `shadowstack.security.encryption-key-base64`) is set,
`AesGcmEncryptionService` encrypts these columns on write and decrypts on read via `ProjectMapper`:

- `unified_diff`
- `verification_json`
- `review_json`

Ciphertext format: `v1:` + Base64(`IV || ciphertext+GCM-tag`) using AES-256-GCM.

- **Key unset** → passthrough plaintext (required for demo profile / local compose).
- **Legacy rows** without a `v1:` prefix decrypt as plaintext (no migration required to enable encryption going forward).
- Flyway schema migrations do **not** implement TDE; they only manage tables/columns.

## Database / PVC encryption (TDE / CMEK) — infrastructure

**Claim boundary:** full-disk / volume encryption for Postgres is an **infrastructure** control, not an application or Flyway feature. Application AES-GCM covers sensitive **patch artifacts** even when volume encryption is separate.

### Runbook: Postgres PVC with customer-managed keys

1. **Choose a storage class** that encrypts volumes with a cloud KMS key:
   - **AWS EKS:** `storageClass` backed by EBS with CMK (`encrypted: true`, `kmsKeyId`).
   - **GCP GKE:** Persistent Disk with CMEK (`disk-encryption-kms-key`).
   - **Azure AKS:** Disk Encryption Set / customer-managed key on the StorageClass.
2. **Update** `infra/k8s/postgres.yaml` PVC `storageClassName` to that encrypted class **before** first provision (changing class on an existing PVC usually requires volume migration).
3. **Verify** at the cloud console that the volume shows encryption = customer-managed / CMK.
4. **Optional Postgres TDE:** if using a managed Postgres offering (RDS, Cloud SQL, Azure DB) with transparent data encryption, prefer that over self-managed StatefulSet disks and document the KMS key ARN/resource ID in your ops register.
5. **Backups:** ensure snapshots/backups inherit the same CMEK; test restore into an encrypted volume.
6. **Access:** restrict KMS key usage to the node/CSI service account; audit key use.

### What Flyway does / does not do

| Layer | Responsibility |
|-------|----------------|
| Flyway migrations | Schema only (`ss_*` tables, indexes, `org_id`, …) |
| App AES-GCM | Encrypts patch artifact fields when key is set |
| Cloud TDE/CMEK | Encrypts the Postgres data directory / PVC at the block layer |

---

See also: `docs/soc2-controls.md` (control mapping), `docs/threat-model.md` (INFO threats), `infra/k8s/secrets.yaml`.
