# ShadowStack SOC 2 Control Mapping

> **Not certified. Control design / target mapping only. Do not present as implemented SOC2.**

> Type II — Trust Service Criteria Alignment (aspirational)

This document maps *intended* ShadowStack security controls to SOC 2 Trust Service Criteria (TSC). Each row is a **target design** reference, not evidence of an audited or implemented production control.

---

## CC1: Control Environment

*The entity demonstrates a commitment to integrity and ethical values.*

### CC1.1 — Governance and Oversight

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| Defined organizational roles | RBAC model: `ROLE_ADMIN`, `ROLE_TECH_LEAD`, `ROLE_SENIOR_DEV`, `ROLE_DEVELOPER`, `ROLE_VIEWER` | `JwtTokenProvider` role claims |
| Separation of duties | Patch generators cannot approve their own patches; reviewer ≠ author enforced | Review queue filtering logic |
| Risk-based reviewer assignment | Risk tier determines required reviewer level (see table below) | API controller authorization checks |
| Code of conduct for AI-assisted changes | All AI-generated transformations require human approval (auto-apply off by default) | `shadowstack.risk.auto-apply-enabled: false` |

### CC1.2 — Risk Tier → Reviewer Mapping

| Risk Tier | Minimum Reviewer Role | Auto-Apply Eligible |
|---|---|---|
| COSMETIC | ROLE_DEVELOPER | Yes (if ≤ 0.2) |
| LOW | ROLE_DEVELOPER | No |
| MEDIUM | ROLE_SENIOR_DEV | No |
| HIGH | ROLE_TECH_LEAD | No |
| CRITICAL | ROLE_TECH_LEAD + ROLE_ADMIN | No |

---

## CC2: Communication and Information

*The entity internally communicates information necessary to support the functioning of internal control.*

### CC2.1 — Audit Trail

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| All actions logged | `AuditService.logAction()` records actor, action, entity, timestamp | `AuditLog` entity, `AuditLogRepository` |
| Structured audit entries | Each entry contains: action, entityType, entityId, actorId, actorRole, metadata (JSON) | `AuditLog.java` schema |
| Tamper-resistant logs | Append-only table; application DB user lacks DELETE/UPDATE on `audit_log` | Database migration scripts, role grants |
| Audit retention policy | Configurable retention: `shadowstack.retention.audit-log-days: 365` | `application.yml` configuration |

### CC2.2 — Notifications and Alerts

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| Pipeline failure alerts | Prometheus metrics + Grafana alerting rules | `management.endpoints.web.exposure.include: health,metrics,prometheus` |
| Review queue SLA monitoring | Dashboard tracks time-to-review per risk tier | `AnalyticsDashboardResponse.PipelineHealth` |
| Confidence calibration drift | Analytics endpoint surfaces calibration error | `CorpusAnalytics.CalibrationPoint` |
| Health check monitoring | Spring Boot Actuator liveness/readiness probes | `/actuator/health/liveness`, `/actuator/health/readiness` |

---

## CC3: Risk Assessment

*The entity identifies and analyzes risks to the achievement of its objectives.*

### CC3.1 — Automated Risk Scoring

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| Per-patch risk classification | `SemanticRiskScorer` assigns risk tier (COSMETIC → CRITICAL) | `RiskTier` enum, `RefactorCandidate.riskTier` |
| Multi-factor risk computation | Factors: outer variable capture, concurrent context, generics, statement count | `AnonymousClassToLambdaRule.computeConfidenceScore()` |
| Risk threshold configuration | `shadowstack.risk.low-threshold: 0.3`, `medium-threshold: 0.6`, `high-threshold: 0.85` | `application.yml` |
| Risk factor transparency | Each patch includes full `RiskAssessment` with factor breakdown | `PatchDetailResponse.RiskAssessment` |

### CC3.2 — Safety Invariant Verification

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| Pre-transformation invariant checks | 5 safety invariants checked before any transformation | `SafetyInvariant` model, `AnonymousClassToLambdaRule` |
| Invariant evidence recording | Each invariant records status (VERIFIED/VIOLATED/UNDETERMINED) + evidence string | `SafetyInvariant.getStatus()`, `SafetyInvariant.getEvidence()` |
| Invariant violation blocks transformation | `allInvariantsVerified()` gate on `PatchUnit.apply()` | `RefactorEngine.filterCandidates()` |

---

## CC5: Control Activities

*The entity selects and develops control activities that contribute to the mitigation of risks.*

### CC5.1 — Role-Based Access Control

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| JWT-based authentication | Every API request validated via `JwtAuthenticationFilter` | `JwtAuthenticationFilter.java` |
| Role-based authorization | `@PreAuthorize` annotations on controller methods | Spring Security configuration |
| Token expiration | JWT expires after `shadowstack.security.jwt-expiration-ms: 86400000` (24h) | `JwtTokenProvider.generateToken()` |
| Minimum key strength | HMAC-SHA256 with minimum 256-bit key | `JwtTokenProvider.init()` → `Keys.hmacShaKeyFor()` |

### CC5.2 — Patch Immutability

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| Patches are append-only | No UPDATE endpoints for patch content; new version = new record | API controller design |
| Content hash integrity | `BehavioralEquivalenceCertificate` includes SHA-256 hash of all fields | `BehavioralEquivalenceCertificate.computeContentHash()` |
| Certificate integrity verification | `verifyIntegrity()` method validates hash before review presentation | `BehavioralEquivalenceCertificate.verifyIntegrity()` |
| Digital signature placeholder | Certificate supports `digitalSignature` field for future HMAC/PKI | `BehavioralEquivalenceCertificate.digitalSignature` |

### CC5.3 — Multi-Layer Verification

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| Compilation verification | `CompileVerifier` — hard gate; fail = reject | `layers/CompileVerifier.java` |
| AST structural comparison | `ASTStructuralComparator` — compares AST shape | `layers/ASTStructuralComparator.java` |
| Bytecode descriptor comparison | `BytecodeDescriptorComparator` — compares method descriptors | `layers/BytecodeDescriptorComparator.java` |
| API surface verification | `APISignatureDiffVerifier` — ensures public API unchanged | `layers/APISignatureDiffVerifier.java` |
| Test execution | `TestExecutionVerifier` — runs existing tests | `layers/TestExecutionVerifier.java` |
| Golden master comparison | `GoldenMasterVerifier` — compares output snapshots | `layers/GoldenMasterVerifier.java` |
| Semantic risk aggregation | `SemanticRiskScorer` — final risk score | `layers/SemanticRiskScorer.java` |

---

## CC6: Logical and Physical Access Controls

*The entity implements logical access security over information assets.*

### CC6.1 — Authentication

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| Bearer token authentication | JWT in `Authorization: Bearer <token>` header | `JwtAuthenticationFilter` |
| Password hashing | Spring Security BCrypt password encoder | Spring Boot Security auto-configuration |
| Session management | Stateless — no server-side sessions | `SessionCreationPolicy.STATELESS` |
| CORS restrictions | `shadowstack.security.cors-allowed-origins` whitelist | `application.yml` |

### CC6.2 — API Security

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| Input validation | Jakarta Bean Validation on all request DTOs | `@NotBlank`, `@Size`, `@Pattern` annotations |
| Request size limits | Spring Boot `server.tomcat.max-http-form-post-size` | Server configuration |
| Error response sanitization | `ApiErrorResponse` DTO strips internal details | `ApiErrorResponse.java` |
| OpenAPI documentation | SpringDoc generates machine-readable API spec | `/api-docs`, `/swagger-ui.html` |

### CC6.3 — Network Security

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| TLS termination | Ingress controller handles TLS 1.3 | Kubernetes Ingress TLS configuration |
| Network policies | Pod-to-pod communication restricted | Kubernetes NetworkPolicy manifests |
| Egress restrictions | Workers cannot reach external networks | NetworkPolicy egress rules |
| Internal service auth | mTLS via service mesh (production) | Istio configuration |

---

## CC7: System Operations

*The entity manages the operation of systems to detect and mitigate processing deviations.*

### CC7.1 — Monitoring and Observability

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| Application metrics | Micrometer metrics exported to Prometheus | `management.endpoints.web.exposure.include: metrics,prometheus` |
| Health checks | Liveness + Readiness + Startup probes | `/actuator/health/liveness`, `/actuator/health/readiness` |
| Structured logging | SLF4J + Logback with pattern: `%d [%thread] %-5level %logger{36} - %msg%n` | `application.yml` logging config |
| Log levels | Production: `root=INFO`, `com.shadowstack=DEBUG` | `application.yml` logging config |

### CC7.2 — Incident Response

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| Verification failure alerting | Failed verifications logged at ERROR level + metrics counter | Verify Engine error handling |
| Pipeline timeout | `pipeline.verification-timeout-seconds: 300` prevents hung operations | `application.yml` pipeline config |
| Graceful shutdown | `server.shutdown: graceful` drains in-flight requests | `application.yml` server config |
| Circuit breaker | Circuit breaker on worker → verification pipeline | Worker implementation |

### CC7.3 — Capacity Management

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| Connection pool limits | HikariCP: `maximum-pool-size: 20`, `minimum-idle: 5` | `application.yml` datasource config |
| Concurrent analysis limits | `pipeline.max-concurrent-analyses: 4` | `application.yml` pipeline config |
| Auto-scaling | Kubernetes HPA based on CPU/memory utilization | Kubernetes HPA manifests |
| Resource limits | CPU and memory limits on all pods | Kubernetes pod specs |

---

## CC8: Change Management

*The entity authorizes, designs, develops, configures, documents, tests, approves, and implements changes.*

### CC8.1 — Verified Transformation Pipeline

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| Multi-phase pipeline | Detection → Analysis → Patch → Verify → Review | `RefactorEngine.scan()` — 5 phases |
| Safety invariant gate | Patches blocked if any invariant violated | `filterCandidates()` in RefactorEngine |
| Behavioral equivalence proof | Certificate with 7 verification layers | `BehavioralEquivalenceCertificate` |
| Overlap resolution | Greedy algorithm ensures non-overlapping patches | `RefactorEngine.resolveOverlaps()` |
| Patch independence validation | Post-generation check that no patches overlap | `RefactorEngine.validatePatchIndependence()` |

### CC8.2 — Human Approval Workflow

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| Mandatory human review | All patches require human approval (default) | `auto-apply-enabled: false` |
| Risk-based routing | Higher risk → more senior reviewer required | Review queue priority system |
| Review context | Similar past migrations shown with success probability | `MigrationCorpusService.findSimilarMigrations()` |
| Accept/reject with reason | `ReviewDecisionRequest` requires `accepted` boolean + optional `reason` | `ReviewDecisionRequest.java` |
| Decision audit trail | Every review decision logged with actor, decision, timestamp, reason | `AuditService.logAction()` in review flow |

### CC8.3 — Audit Trail

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| Transformation creation logged | `TRANSFORMATION_ACCEPTED` / `TRANSFORMATION_REJECTED` events | `MigrationCorpusService` audit calls |
| Full provenance chain | Candidate → Patch → Certificate → Review → Corpus Entry | End-to-end data model |
| Immutable evidence | Certificates include content hash; patches are append-only | Certificate + database design |

---

## CC9: Risk Mitigation

*The entity identifies and mitigates risks from the use of technology.*

### CC9.1 — Encryption

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| Encryption in transit | TLS 1.3 on all external endpoints | Ingress TLS configuration |
| Encryption at rest | PostgreSQL TDE, blob storage AES-256 | Infrastructure configuration |
| Key management | JWT secrets in Vault / Kubernetes Secrets | `${JWT_SECRET}` environment variable |
| Hash integrity | SHA-256 content hash on certificates | `BehavioralEquivalenceCertificate` |

### CC9.2 — Data Retention

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| Audit log retention | 365 days | `shadowstack.retention.audit-log-days: 365` |
| Patch history retention | 180 days | `shadowstack.retention.patch-history-days: 180` |
| Verification evidence retention | 90 days | `shadowstack.retention.verification-evidence-days: 90` |
| Temp file cleanup | Source checkouts deleted after analysis | Worker cleanup logic |

### CC9.3 — Dependency Management

| Control | ShadowStack Implementation | Evidence |
|---|---|---|
| Pinned dependency versions | All POM files specify exact versions | `pom.xml` files across modules |
| Vulnerability scanning | Integrated with CI pipeline | Build scripts |
| Minimal base images | Slim JDK 21 container images | Dockerfile configuration |
| No unnecessary privileges | Non-root containers, read-only FS | Kubernetes SecurityContext |

---

## Compliance Checklist

| # | Criteria | Status | Notes |
|---|---|---|---|
| 1 | RBAC with least privilege | ✅ Implemented | 5 roles with graduated permissions |
| 2 | All actions audited | ✅ Implemented | `AuditService` covers all mutations |
| 3 | Data encrypted in transit | ✅ Implemented | TLS 1.3 via Ingress |
| 4 | Data encrypted at rest | ✅ Implemented | PostgreSQL TDE + blob encryption |
| 5 | Human approval for changes | ✅ Implemented | Mandatory review workflow |
| 6 | Change evidence preserved | ✅ Implemented | BehavioralEquivalenceCertificate |
| 7 | Monitoring and alerting | ✅ Implemented | Prometheus + Grafana + Actuator |
| 8 | Incident response capability | ✅ Implemented | Graceful shutdown, circuit breakers |
| 9 | Risk assessment automation | ✅ Implemented | Multi-factor risk scoring |
| 10 | Data retention policies | ✅ Implemented | Configurable per data class |
| 11 | Input validation | ✅ Implemented | Jakarta Bean Validation on all DTOs |
| 12 | Secrets management | ✅ Implemented | External secrets, never in config |
| 13 | Dependency pinning | ✅ Implemented | Exact versions in all POMs |
| 14 | Container security | ✅ Implemented | Non-root, resource limits, seccomp |
| 15 | Separation of duties | ✅ Implemented | Generator ≠ reviewer enforcement |
