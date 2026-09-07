# Vault Agent Injector for ShadowStack API

**Status:** Implemented (manifests) — documentation + strategic merge patch.
Demo / compose paths are unchanged; apply only in clusters with Vault Agent Injector.

## When to use Agent vs External Secrets

| Approach | Manifest | Best for |
|----------|----------|----------|
| **External Secrets Operator** | `external-secret-vault.yaml` | Sync Vault → durable K8s Secret; pods use `secretKeyRef` as today |
| **Vault Agent Injector** | This doc + `api-deployment-vault-agent-patch.yaml` | Sidecar renders secrets into files/env at pod start; no ESO CRDs |

Both feed the **same env var names** the API already expects (`JWT_SECRET`, `SECURITY_*`, `DB_*`, optional `ENCRYPTION_KEY_BASE64`). Prefer one path per environment to avoid conflicting Secret owners.

## Prerequisites

1. Vault Agent Injector webhook installed in the cluster.
2. Vault Kubernetes auth enabled; role `shadowstack` bound to the API ServiceAccount in namespace `shadowstack`.
3. KV v2 secret at `secret/data/shadowstack/api` with keys matching the template below.
4. Spring profile includes `vault` (and usually `prod`):  
   `SPRING_PROFILES_ACTIVE=prod,vault`

## Annotations (summary)

Add to `Deployment` `spec.template.metadata.annotations`:

| Annotation | Purpose |
|------------|---------|
| `vault.hashicorp.com/agent-inject: "true"` | Enable sidecar |
| `vault.hashicorp.com/role: "shadowstack"` | Kubernetes auth role |
| `vault.hashicorp.com/agent-inject-secret-shadowstack: "secret/data/shadowstack/api"` | Fetch KV path |
| `vault.hashicorp.com/agent-inject-template-shadowstack` | Render env-file for the app |
| `vault.hashicorp.com/agent-inject-command-shadowstack` | Optional: `source` / kill -HUP after write |
| `vault.hashicorp.com/agent-pre-populate-only: "true"` | Init-only inject (no long-lived sidecar) — recommended for JVM env |

## Env-file template (Agent)

Render a dotenv-compatible file that matches `application-vault.yml` / Deployment env names:

```hcl
{{- with secret "secret/data/shadowstack/api" -}}
JWT_SECRET={{ .Data.data.JWT_SECRET }}
SECURITY_USER={{ .Data.data.SECURITY_USER }}
SECURITY_PASSWORD={{ .Data.data.SECURITY_PASSWORD }}
DB_USER={{ .Data.data.DB_USER }}
DB_PASSWORD={{ .Data.data.DB_PASSWORD }}
{{- if .Data.data.ENCRYPTION_KEY_BASE64 }}
ENCRYPTION_KEY_BASE64={{ .Data.data.ENCRYPTION_KEY_BASE64 }}
{{- end }}
{{- end }}
```

With **pre-populate-only**, export these into the container env via an entrypoint wrapper, **or** keep using the existing `secretKeyRef` path with ESO and skip Agent. For a pure Agent flow without a K8s Secret, use a small entrypoint:

```bash
set -a
# shellcheck disable=SC1091
. /vault/secrets/shadowstack
set +a
exec java $JAVA_OPTS -jar /app/app.jar
```

If you keep `secretKeyRef` (ESO or `secrets.yaml`), you do **not** need the entrypoint — Agent is then optional for other secrets only.

## Apply the patch

```bash
kubectl -n shadowstack patch deployment api --patch-file \
  infra/k8s/api-deployment-vault-agent-patch.yaml
# or strategic merge:
kubectl -n shadowstack apply -f infra/k8s/api-deployment-vault-agent-patch.yaml
```

Then set the profile:

```bash
kubectl -n shadowstack set env deployment/api SPRING_PROFILES_ACTIVE=prod,vault
```

Repeat for `worker` if it also needs Vault-injected credentials (same annotations; profile `prod,vault` or `k8s,worker,vault` as appropriate).

## Verify

```bash
kubectl -n shadowstack get pods -l app.kubernetes.io/name=api
kubectl -n shadowstack logs -l app.kubernetes.io/name=api -c api --tail=50
# Expect SecurityPropertiesValidator / encryption init logs; no JWT placeholder rejection.
curl -u "$SECURITY_USER:$SECURITY_PASSWORD" \
  http://api.shadowstack.svc:8080/api/v1/meta/security
# encryptionEnabled reflects ENCRYPTION_KEY_BASE64; vaultProfileActive=true when profile vault is on.
```

## Rotation

Update the Vault KV payload → Agent refreshes on next pod start (pre-populate-only) or on template re-render → rolling restart API/worker. See rotation table in `docs/secrets-and-encryption.md`.
