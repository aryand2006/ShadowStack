# Secrets & Encryption at Rest

ShadowStack separates **secret injection** (Vault / Kubernetes Secrets / compose env)
from **encryption at rest** (cloud disk TDE/CMEK + optional application-level AES-GCM
for patch artifacts).

| Capability | Status | Ship artifact |
|------------|--------|---------------|
| External Secrets → Vault | **Implemented (manifests)** | `infra/k8s/external-secret-vault.yaml` + `external-secret-vault.values.example.yaml` |
| Vault Agent Injector | **Implemented (manifests)** | `infra/k8s/vault-agent-annotations.md` + `api-deployment-vault-agent-patch.yaml` |
| Optional Spring `vault` profile | **Implemented** | `apps/api/.../application-vault.yml` (env-only; no Spring Cloud Vault dep) |
| App AES-GCM patch artifacts | **Implemented** | `AesGcmEncryptionService` + optional `ENCRYPTION_KEY_BASE64` |
| CMEK / encrypted PVC | **Implemented (manifests)** | `infra/k8s/storageclass-encrypted.yaml` + optional `storageClassName` in `postgres.yaml` |

Demo / `docker-compose` paths stay plaintext-friendly and do **not** require Vault or CMEK.

## Secrets: compose vs production

| Environment | How secrets are supplied | Notes |
|-------------|--------------------------|--------|
| **demo** profile | Defaults in `application-demo.yml` | No Postgres; JWT placeholder OK; encryption off |
| **docker-compose** | Env vars with **local DEV defaults** | Defaults exist so the stack boots; **production MUST override** `JWT_SECRET`, `SECURITY_*`, `DB_PASSWORD` |
| **prod / k8s** | `infra/k8s/secrets.yaml` or External Secrets / Vault Agent | `SecurityPropertiesValidator` rejects weak JWT placeholders |
| **prod,vault** | Same env names; profile fails fast if `JWT_SECRET` missing | Activate with Agent or ESO — see below |

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
- `infra/k8s/external-secret-vault.values.example.yaml` — example server/role/path values
- Vault KV v2 path: **`shadowstack/data/api`** (ESO remote key `shadowstack/api`)
- Target Kubernetes Secret: **`shadowstack-secrets`** (wired into `api-deployment.yaml` / `worker-deployment.yaml`)

### Bootstrap steps (ESO)

1. Install [External Secrets Operator](https://external-secrets.io/).
2. Enable Vault Kubernetes auth; create a role/policy that can read `secret/data/shadowstack/api`.
3. Write the KV payload (`JWT_SECRET`, `SECURITY_USER`, `SECURITY_PASSWORD`, `DB_*`, optional `ENCRYPTION_KEY_BASE64`).
4. Copy values from `external-secret-vault.values.example.yaml` into `external-secret-vault.yaml` (Vault URL, role, mount) and apply it in the `shadowstack` namespace.
5. Confirm `kubectl get secret shadowstack-secrets -n shadowstack` is populated; roll API/worker pods if needed.

Do **not** apply the template unchanged — server URL and role are placeholders.

## Vault Agent Injector (alternative)

**Status: Implemented (manifests).**

- Guide: `infra/k8s/vault-agent-annotations.md`
- Patch: `infra/k8s/api-deployment-vault-agent-patch.yaml` (`vault.hashicorp.com/agent-inject*`)
- Spring: `--spring.profiles.active=prod,vault` → `application-vault.yml` binds `JWT_SECRET` / DB / encryption from env only (no `spring-cloud-starter-vault-config`)

Prefer **either** ESO **or** Agent per environment so Secret ownership stays clear.

### Rotation runbook

| Secret | Procedure |
|--------|-----------|
| `JWT_SECRET` | Update Vault → wait for ESO refresh (or Agent re-inject on restart) → rolling restart API/worker. Existing JWTs signed with the old key become invalid (users re-login). Prefer a short dual-key window only if you add multi-key support later. |
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
- Runtime flag: `GET /api/v1/meta/security` → `encryptionEnabled` (authenticated).

## Database / PVC encryption (TDE / CMEK) — infrastructure

**Claim boundary:** full-disk / volume encryption for Postgres is an **infrastructure** control, not an application or Flyway feature. Application AES-GCM covers sensitive **patch artifacts** even when volume encryption is separate.

**Status: Implemented (manifests)** — `infra/k8s/storageclass-encrypted.yaml` (GKE / EKS / AKS examples) and optional `storageClassName: shadowstack-encrypted` in `postgres.yaml`.

### Runbook: Postgres PVC with customer-managed keys

1. **Choose a storage class** that encrypts volumes with a cloud KMS key — start from `storageclass-encrypted.yaml`:
   - **AWS EKS:** EBS gp3 with `encrypted: true` and optional `kmsKeyId`.
   - **GCP GKE:** `pd-balanced` with `disk-encryption-kms-key`.
   - **Azure AKS:** `diskEncryptionSetID` on the Azure Disk CSI StorageClass.
2. Apply the provider StorageClass so its **name** is `shadowstack-encrypted`.
3. **Uncomment** `storageClassName: shadowstack-encrypted` in `infra/k8s/postgres.yaml` **before** first provision (changing class on an existing PVC usually requires volume migration).
4. **Verify** at the cloud console that the volume shows encryption = customer-managed / CMK.
5. **Optional Postgres TDE:** if using a managed Postgres offering (RDS, Cloud SQL, Azure DB) with transparent data encryption, prefer that over self-managed StatefulSet disks and document the KMS key ARN/resource ID in your ops register.
6. **Backups:** ensure snapshots/backups inherit the same CMEK; test restore into an encrypted volume.
7. **Access:** restrict KMS key usage to the node/CSI service account; audit key use.

### What Flyway does / does not do

| Layer | Responsibility |
|-------|----------------|
| Flyway migrations | Schema only (`ss_*` tables, indexes, `org_id`, …) |
| App AES-GCM | Encrypts patch artifact fields when key is set |
| Cloud TDE/CMEK | Encrypts the Postgres data directory / PVC at the block layer |

---

## Production checklist

Actionable gate before calling an environment “Vault + CMEK ready.” Demo stack is exempt.

### A. Secrets (pick one injection path)

- [ ] Generate production `JWT_SECRET`, `SECURITY_PASSWORD`, `DB_PASSWORD` (and optional `ENCRYPTION_KEY_BASE64`) with `openssl` commands above — no placeholder substrings.
- [ ] Write KV payload to Vault at `secret/data/shadowstack/api`.
- [ ] **Path ESO:** Install External Secrets Operator → edit `external-secret-vault.yaml` using `external-secret-vault.values.example.yaml` → `kubectl apply -n shadowstack -f infra/k8s/external-secret-vault.yaml` → confirm `kubectl get secret shadowstack-secrets -n shadowstack`.
- [ ] **Path Agent:** Install Vault Agent Injector → bind K8s auth role `shadowstack` → `kubectl patch deployment api --patch-file infra/k8s/api-deployment-vault-agent-patch.yaml` → set `SPRING_PROFILES_ACTIVE=prod,vault` (see `vault-agent-annotations.md`).
- [ ] Do **not** leave `infra/k8s/secrets.yaml` placeholders applied in production.
- [ ] Rolling restart API + worker; login works; `/actuator/health` ready.
- [ ] Confirm `GET /api/v1/meta/security` shows `vaultProfileActive: true` when using the `vault` profile.

### B. Application encryption (optional but recommended)

- [ ] Set `ENCRYPTION_KEY_BASE64` (32-byte Base64) in Vault / Secret.
- [ ] Restart API; logs show `EncryptionService: AES-GCM enabled`.
- [ ] `GET /api/v1/meta/security` → `encryptionEnabled: true`.
- [ ] Create a patch in a non-prod org and confirm DB columns use `v1:` ciphertext (or accept plaintext only if key intentionally unset).

### C. CMEK / volume encryption

- [ ] Create cloud KMS key; grant CSI / node SA encrypt/decrypt.
- [ ] Apply provider block from `infra/k8s/storageclass-encrypted.yaml` as StorageClass **`shadowstack-encrypted`** (remove the no-provisioner placeholder).
- [ ] Uncomment `storageClassName: shadowstack-encrypted` in `postgres.yaml` **before** first Postgres PVC (or migrate volumes).
- [ ] `kubectl apply -f infra/k8s/postgres.yaml` (or Helm equivalent); confirm PVC uses `shadowstack-encrypted`.
- [ ] In cloud console: volume encryption = customer-managed / CMK / Disk Encryption Set.
- [ ] Backup/snapshot policy inherits the same key; document key ID in the ops register.
- [ ] Test restore into an encrypted volume in a staging namespace.

### D. Post-deploy verification

- [ ] NetworkPolicies applied (`infra/k8s/networkpolicy.yaml`).
- [ ] Weak JWT rejected by `SecurityPropertiesValidator` if misconfigured.
- [ ] Rotation drill: update one Vault key → ESO/Agent refresh → restart → re-login.
- [ ] Record evidence links in `ss_control_evidence` / SOC 2 runbook (`docs/soc2-controls.md`).

---

See also: `docs/soc2-controls.md` (control mapping), `docs/threat-model.md` (INFO threats), `infra/k8s/secrets.yaml`.
