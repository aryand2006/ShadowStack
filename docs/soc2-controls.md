# SOC 2 Control Readiness (certification requires external audit)

> **Banner — claim language (mandatory)**  
> You may claim **"SOC 2 control readiness / audit-ready controls"**.  
> You may **NOT** claim **"SOC 2 certified"**, **"SOC 2 compliant"**, or **"SOC 2 Type II"** until an independent auditor issues a report.

This document maps ShadowStack product and infrastructure controls to SOC 2 Trust Service Criteria (TSC). Status values:

| Status | Meaning |
|--------|---------|
| **Implemented** | Code/infra present and wired; evidence path is real |
| **Partial** | Some implementation exists; gaps remain |
| **Target** | Designed/documented only; not enforced in this repo |

---

## CC1: Control Environment

### CC1.1 — Governance and Oversight

| Control | ShadowStack implementation | Status | Evidence path |
|---------|---------------------------|--------|---------------|
| Defined organizational roles | RBAC: `ADMIN`, `REVIEWER`, `ANALYST`, `VIEWER` | Implemented | `apps/api/src/main/java/com/shadowstack/api/security/RBACConfig.java` |
| Separation of duties | Patch authors cannot accept/reject own patches unless `ADMIN` | Implemented | `apps/api/src/main/java/com/shadowstack/api/service/ReviewService.java` |
| Auto-apply gated | Auto-apply off by default | Implemented | `apps/api/src/main/resources/application.yml` (`shadowstack.risk.auto-apply-enabled`) |
| Risk-tier → senior reviewer mapping | Code authorizes `REVIEWER`/`ADMIN` only | Target | (future) risk-tier reviewer gates |

---

## CC2: Communication and Information

### CC2.1 — Audit Trail

| Control | ShadowStack implementation | Status | Evidence path |
|---------|---------------------------|--------|---------------|
| All actions logged | `AuditService` + HTTP `AuditInterceptor` | Implemented | `packages/migration-corpus/src/main/java/com/shadowstack/corpus/AuditService.java` |
| Durable schema | `audit_log` table | Implemented | `packages/migration-corpus/src/main/resources/db/migration/V2__create_audit_log.sql` |
| Append-only app grants | `REVOKE DELETE` on role `shadowstack` when present; soft-delete via `deleted_at` | Implemented | `packages/migration-corpus/src/main/resources/db/migration/V6__soc2_control_readiness.sql` |
| Retention indexes | Timestamp / org indexes for cleanup | Implemented | `V6__soc2_control_readiness.sql` |
| Query + CSV export | ADMIN audit API | Implemented | `apps/api/src/main/java/com/shadowstack/api/controllers/AuditController.java` |

### CC2.2 — Notifications and Alerts

| Control | ShadowStack implementation | Status | Evidence path |
|---------|---------------------------|--------|---------------|
| Health probes | Actuator liveness/readiness | Implemented | `apps/api/src/main/resources/application.yml` |
| Prometheus metrics | Micrometer exposure | Partial | `application.yml` (`management.endpoints`) — Grafana rules not shipped |
| Review SLA dashboards | Analytics pipeline health | Partial | `apps/api/src/main/java/com/shadowstack/api/controllers/AnalyticsController.java` |

---

## CC3: Risk Assessment

### CC3.1 — Automated Risk Scoring

| Control | ShadowStack implementation | Status | Evidence path |
|---------|---------------------------|--------|---------------|
| Per-patch risk classification | Semantic risk scorer + tiers | Implemented | `packages/verify-engine` |
| Configurable thresholds | `shadowstack.risk.*` | Implemented | `apps/api/src/main/java/com/shadowstack/api/config/ShadowStackConfig.java` |

### CC3.2 — Safety Invariant Verification

| Control | ShadowStack implementation | Status | Evidence path |
|---------|---------------------------|--------|---------------|
| Safety invariants before transform | Adapter/rule invariant gates | Implemented | `packages/refactor-engine` |
| Verification layers | Compile → AST → bytecode → API → tests → golden → risk | Implemented | `packages/verify-engine` |

---

## CC5: Control Activities

### CC5.1 — Role-Based Access Control

| Control | ShadowStack implementation | Status | Evidence path |
|---------|---------------------------|--------|---------------|
| JWT authentication | `JwtAuthenticationFilter` | Implemented | `apps/api/src/main/java/com/shadowstack/api/security/JwtAuthenticationFilter.java` |
| Method security | `@PreAuthorize` on controllers | Implemented | `apps/api/src/main/java/com/shadowstack/api/security/SecurityConfig.java` |
| Optional OIDC | `oidc` profile resource server | Implemented | `apps/api/src/main/java/com/shadowstack/api/security/OidcSecurityConfig.java` |

### CC5.2 — Patch / Certificate Integrity

| Control | ShadowStack implementation | Status | Evidence path |
|---------|---------------------------|--------|---------------|
| Behavioral equivalence certificate | Content hash + layer results | Partial | `packages/verify-engine/src/main/java/com/shadowstack/verify/BehavioralEquivalenceCertificate.java` |
| Digital signature / PKI | Field placeholder only | Target | same |

### CC5.3 — Multi-Layer Verification

| Control | ShadowStack implementation | Status | Evidence path |
|---------|---------------------------|--------|---------------|
| Seven-layer verify pipeline | Worker `VerificationTask` | Implemented | `apps/worker/src/main/java/com/shadowstack/worker/tasks/VerificationTask.java` |

---

## CC6: Logical Access / Network

### CC6.1 — Authentication

| Control | ShadowStack implementation | Status | Evidence path |
|---------|---------------------------|--------|---------------|
| Bearer JWT + Basic | Stateless sessions | Implemented | `SecurityConfig.java` |
| Password hashing | BCrypt via Spring Security | Implemented | `DemoUsersConfig.java` / `ProdUsersConfig.java` |
| CORS allowlist | `shadowstack.security.cors-allowed-origins` | Implemented | `application.yml` |

### CC6.3 — Network Security

| Control | ShadowStack implementation | Status | Evidence path |
|---------|---------------------------|--------|---------------|
| Default-deny NetworkPolicy | Deny all + allow api↔postgres, worker↔postgres, web→api | Implemented | `infra/k8s/networkpolicy.yaml` |
| TLS at ingress | Expected in cluster ingress | Target | `infra/k8s/ingress.yaml` |
| mTLS / service mesh | Not shipped | Target | — |

---

## CC7: System Operations

| Control | ShadowStack implementation | Status | Evidence path |
|---------|---------------------------|--------|---------------|
| Actuator health/metrics | Enabled | Partial | `application.yml` |
| Graceful shutdown | `server.shutdown: graceful` | Implemented | `application.yml` |
| Circuit breakers | Not productized | Target | — |

---

## CC8: Change Management

| Control | ShadowStack implementation | Status | Evidence path |
|---------|---------------------------|--------|---------------|
| Verified transform pipeline | Detect → analyze → patch → verify → review | Implemented | `packages/refactor-engine` |
| Mandatory human review | Auto-apply off; accept/reject API | Implemented | `apps/api/src/main/java/com/shadowstack/api/controllers/ReviewController.java` |
| Decision audit | Review decisions logged | Implemented | `AuditService` + review flow |

---

## CC9: Risk Mitigation

### CC9.2 — Data Retention

| Control | ShadowStack implementation | Status | Evidence path |
|---------|---------------------------|--------|---------------|
| Configured retention windows | `audit-log-days`, `patch-history-days`, `verification-evidence-days` | Implemented | `application.yml` / `ShadowStackConfig` |
| Daily cleanup job | Soft-delete audit + archive verification JSON; audits itself | Implemented | `apps/api/src/main/java/com/shadowstack/api/jobs/RetentionCleanupJob.java` |
| Evidence registry | `ss_control_evidence` + ADMIN API | Implemented | `V6__soc2_control_readiness.sql`, `ControlEvidenceController.java` |

### CC9.3 — Dependency Management

| Control | ShadowStack implementation | Status | Evidence path |
|---------|---------------------------|--------|---------------|
| CI vulnerability scan | Trivy filesystem HIGH/CRITICAL (`security-scan` job) | Implemented | `.github/workflows/ci.yml` |
| Pinned Maven versions | Parent/module POMs | Partial | `pom.xml` |
| Non-root containers | K8s `runAsNonRoot` | Partial | `infra/k8s/*-deployment.yaml` |

---

## Compliance Checklist

| # | Criteria | Status | Notes |
|---|----------|--------|-------|
| 1 | RBAC with least privilege | Implemented | ADMIN / REVIEWER / ANALYST / VIEWER |
| 2 | All actions audited | Implemented | Interceptor + durable `audit_log` (`!demo`) |
| 3 | Data encrypted in transit | Target | TLS expected at ingress |
| 4 | Data encrypted at rest | Target | Depends on Postgres/disk |
| 5 | Human approval for changes | Implemented | Review workflow; auto-apply off |
| 6 | Change evidence preserved | Partial | Verification JSON + certificates; retention archives old evidence |
| 7 | Monitoring and alerting | Partial | Actuator/Prometheus; no shipped Grafana rules |
| 8 | Incident response capability | Target | Graceful shutdown only |
| 9 | Risk assessment automation | Implemented | Risk scoring on candidates/patches |
| 10 | Data retention policies | Implemented | Config + `RetentionCleanupJob` |
| 11 | Input validation | Partial | Bean Validation on many DTOs |
| 12 | Secrets management | Partial | Env/K8s secrets; demo defaults remain |
| 13 | Dependency pinning | Partial | Most POMs pin versions |
| 14 | Container / network security | Implemented | NetworkPolicy + non-root where set |
| 15 | Separation of duties | Implemented | `createdBy` SoD; ADMIN override |
| 16 | Control evidence registry | Implemented | `ss_control_evidence` + `/api/v1/compliance/evidence` |
| 17 | Vulnerability scanning in CI | Implemented | Trivy `security-scan` job |

---

## Path to certification

Control readiness in this repository is **necessary but not sufficient** for SOC 2.

1. **Stabilize production controls** — Deploy with non-demo profiles, apply V6 grants, NetworkPolicies, secrets management, TLS, and retention schedules in the real environment.
2. **Collect operating evidence** — Retain audit exports, CI scan results, access reviews, change tickets, and incident records for the observation window.
3. **Engage an independent auditor** — Select an AICPA-aligned CPA firm experienced with SaaS / SOC 2.
4. **Type I report** — Point-in-time design (and implementation) opinion: controls suitably designed as of a date.
5. **Type II report** — Operating effectiveness over a period (typically 3–12 months) with sample testing of the controls above.
6. **Remediate exceptions** — Close auditor findings; update this matrix and evidence registry accordingly.
7. **Only then** — Marketing and contracts may cite the auditor’s SOC 2 report. Until that report exists, use only: **“SOC 2 control readiness / audit-ready controls.”**

### API for evidence listing

`GET /api/v1/compliance/evidence` (ADMIN) returns the static control catalog plus rows from `ss_control_evidence`.
