# ShadowStack API Reference

> Base URL: `http://localhost:8080/api/v1`

## Authentication

All API endpoints (except `/auth/login` and health checks) require a valid JWT bearer token.

```
Authorization: Bearer <token>
```

### Obtain a Token

```
POST /api/v1/auth/login
```

**Request Body:**

```json
{
  "username": "admin",
  "password": "shadowstack"
}
```

**Response (200):**

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "expiresIn": 86400000,
  "roles": ["ROLE_ADMIN"]
}
```

**Token Claims:**

| Claim | Type | Description |
|---|---|---|
| `sub` | string | Username |
| `roles` | string | Comma-separated role list |
| `iat` | number | Issued-at timestamp |
| `exp` | number | Expiration timestamp |

---

## Projects

### Create Project

```
POST /api/v1/projects
```

Creates a new ShadowStack project from a Git repository.

**Request Body:**

```json
{
  "name": "legacy-payment-service",
  "description": "Java 8 payment processing service",
  "repositoryUrl": "https://github.com/acme/payment-service.git",
  "branch": "main",
  "sourceLanguage": "java",
  "targetLanguageVersion": "21"
}
```

| Field | Type | Required | Validation |
|---|---|---|---|
| `name` | string | Yes | 1–255 characters |
| `description` | string | No | Max 1000 characters |
| `repositoryUrl` | string | Yes | Valid git URL (`https://`, `git@`, `ssh://`) |
| `branch` | string | No | Alphanumeric + `./_-`; defaults to `main` |
| `sourceLanguage` | string | Yes | Language identifier (e.g., `java`, `cobol`, `python`) |
| `targetLanguageVersion` | string | No | Target version (e.g., `21`) |

**Response (201):**

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "legacy-payment-service",
  "description": "Java 8 payment processing service",
  "repositoryUrl": "https://github.com/acme/payment-service.git",
  "branch": "main",
  "sourceLanguage": "java",
  "targetLanguageVersion": "21",
  "status": "CREATED",
  "createdAt": "2026-02-16T10:00:00Z",
  "updatedAt": "2026-02-16T10:00:00Z"
}
```

### List Projects

```
GET /api/v1/projects
```

**Response (200):**

```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "legacy-payment-service",
    "status": "ANALYZED",
    "sourceLanguage": "java",
    "patchCount": 12,
    "acceptedCount": 8,
    "createdAt": "2026-02-16T10:00:00Z"
  }
]
```

### Get Project

```
GET /api/v1/projects/{projectId}
```

**Response (200):** Full `ProjectResponse` object.

---

## Analysis Pipeline

### Trigger Analysis

```
POST /api/v1/projects/{projectId}/analyze
```

Ingests the project source, builds the semantic model, and runs all registered refactoring rules.

**Request Body (optional):**

```json
{
  "rules": ["ANON_TO_LAMBDA"],
  "includeTests": false,
  "dryRun": false
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `rules` | string[] | No | Specific rule IDs to run; empty = all rules |
| `includeTests` | boolean | No | Whether to include test sources (default: false) |
| `dryRun` | boolean | No | If true, return candidates without generating patches |

**Response (202):**

```json
{
  "analysisId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "projectId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "QUEUED",
  "estimatedDurationSeconds": 120,
  "queuedAt": "2026-02-16T10:05:00Z"
}
```

### Get Analysis Status

```
GET /api/v1/projects/{projectId}/analyses/{analysisId}
```

**Response (200):**

```json
{
  "analysisId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "COMPLETED",
  "candidatesFound": 15,
  "patchesGenerated": 12,
  "patchesVerified": 11,
  "patchesFailed": 1,
  "durationSeconds": 87,
  "completedAt": "2026-02-16T10:06:27Z"
}
```

### Capture Baseline

```
POST /api/v1/projects/{projectId}/baseline
```

Captures a behavioral baseline (test outputs, API signatures, bytecode hashes) for the current source state.

**Response (201):**

```json
{
  "baselineId": "b1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "projectId": "550e8400-e29b-41d4-a716-446655440000",
  "fileCount": 42,
  "testCount": 156,
  "capturedAt": "2026-02-16T10:04:00Z"
}
```

---

## Patches

### List Patches

```
GET /api/v1/patches?project_id={projectId}&status={status}&risk_tier={riskTier}
```

**Query Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `project_id` | UUID | Filter by project |
| `status` | string | Filter by status: `GENERATED`, `VERIFYING`, `VERIFIED`, `VERIFICATION_FAILED`, `PENDING_REVIEW`, `ACCEPTED`, `REJECTED`, `APPLIED` |
| `risk_tier` | string | Filter by risk: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |

**Response (200):**

```json
[
  {
    "patchId": "p1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "projectId": "550e8400-e29b-41d4-a716-446655440000",
    "ruleName": "Anonymous Class to Lambda Expression",
    "ruleCategory": "modernization",
    "status": "PENDING_REVIEW",
    "filePath": "src/main/java/com/example/EventProcessor.java",
    "startLine": 25,
    "endLine": 35,
    "risk": {
      "score": 0.15,
      "tier": "LOW",
      "confidenceScore": 0.95
    },
    "createdAt": "2026-02-16T10:06:00Z"
  }
]
```

### Get Patch Detail

```
GET /api/v1/patches/{patchId}
```

Returns comprehensive patch information including diff, invariants, risk assessment, verification evidence, and review history.

**Response (200):**

```json
{
  "patchId": "p1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "projectId": "550e8400-e29b-41d4-a716-446655440000",
  "candidateId": "c1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "ruleName": "Anonymous Class to Lambda Expression",
  "ruleCategory": "modernization",
  "status": "PENDING_REVIEW",
  "filePath": "src/main/java/com/example/EventProcessor.java",
  "startLine": 25,
  "endLine": 35,
  "unifiedDiff": "--- a/src/main/java/com/example/EventProcessor.java\n+++ b/src/main/java/com/example/EventProcessor.java\n@@ -25,11 +25,1 @@\n-        Collections.sort(events, new Comparator<Event>() {\n-            @Override\n-            public int compare(Event e1, Event e2) {\n-                return e1.getTimestamp().compareTo(e2.getTimestamp());\n-            }\n-        });\n+        Collections.sort(events, (e1, e2) -> e1.getTimestamp().compareTo(e2.getTimestamp()));",
  "rationale": "Convert anonymous Comparator implementation to lambda expression. All safety invariants verified — conversion is safe.",
  "invariants": [
    {
      "type": "functional_interface",
      "description": "Target interface must be a functional interface",
      "expression": "Comparator<Event> has SAM 'compare'",
      "preserved": true
    },
    {
      "type": "no_outer_this_capture",
      "description": "No problematic 'this' references",
      "expression": "0 this expressions scanned",
      "preserved": true
    },
    {
      "type": "no_mutable_capture",
      "description": "All captured variables effectively final",
      "expression": "No outer variables captured",
      "preserved": true
    },
    {
      "type": "no_object_method_override",
      "description": "Method 'compare' is not an Object method override",
      "expression": "compare ∉ {toString, equals, hashCode, ...}",
      "preserved": true
    },
    {
      "type": "no_reflection_dependency",
      "description": "No reflection or serialization dependencies",
      "expression": "0 reflection usages detected",
      "preserved": true
    }
  ],
  "risk": {
    "score": 0.15,
    "tier": "LOW",
    "factors": [
      {
        "name": "capturesOuterVars",
        "description": "Lambda captures variables from enclosing scope",
        "weight": 0.10,
        "contribution": 0.0
      },
      {
        "name": "multipleStatements",
        "description": "Lambda body contains multiple statements",
        "weight": 0.05,
        "contribution": 0.0
      },
      {
        "name": "usesGenerics",
        "description": "Transformation involves generic types",
        "weight": 0.05,
        "contribution": 0.05
      },
      {
        "name": "inConcurrentContext",
        "description": "Used in concurrent execution context",
        "weight": 0.15,
        "contribution": 0.0
      }
    ],
    "confidenceScore": 0.90
  },
  "verificationEvidence": {
    "behaviorallyEquivalent": true,
    "testsPassed": 12,
    "testsFailed": 0,
    "testsSkipped": 0,
    "invariantsVerified": [
      "functional_interface",
      "no_outer_this_capture",
      "no_mutable_capture",
      "no_object_method_override",
      "no_reflection_dependency"
    ],
    "invariantsViolated": [],
    "proofArtifacts": {
      "certificateId": "cert-abc123",
      "originalAstHash": "sha256:a1b2c3...",
      "transformedAstHash": "sha256:d4e5f6...",
      "contentHash": "sha256:789abc..."
    },
    "verifiedAt": "2026-02-16T10:06:15Z"
  },
  "review": null,
  "createdAt": "2026-02-16T10:06:00Z",
  "updatedAt": "2026-02-16T10:06:15Z"
}
```

---

## Review Queue

### List Review Queue

```
GET /api/v1/queue?status={status}&risk_tier={riskTier}
```

Returns patches awaiting human review, ordered by priority (risk tier descending, then creation time ascending).

**Query Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `status` | string | `PENDING_REVIEW`, `ACCEPTED`, `REJECTED` |
| `risk_tier` | string | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |

**Response (200):**

```json
[
  {
    "patchId": "p1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "projectName": "legacy-payment-service",
    "ruleName": "Anonymous Class to Lambda Expression",
    "filePath": "src/main/java/com/example/EventProcessor.java",
    "startLine": 25,
    "endLine": 35,
    "riskTier": "LOW",
    "confidenceScore": 0.95,
    "successProbability": 0.92,
    "similarMigrations": 7,
    "createdAt": "2026-02-16T10:06:00Z"
  }
]
```

### Submit Review Decision

```
POST /api/v1/queue/{patchId}/review
```

**Request Body:**

```json
{
  "accepted": true,
  "reason": "Lambda conversion looks correct. Verified no side effects."
}
```

| Field | Type | Required | Validation |
|---|---|---|---|
| `accepted` | boolean | Yes | `true` = accept, `false` = reject |
| `reason` | string | No | Max 2000 characters (required for rejections) |

**Response (200):**

```json
{
  "patchId": "p1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "ACCEPTED",
  "reviewer": "jane.doe",
  "reviewedAt": "2026-02-16T11:30:00Z",
  "corpusEntryId": "m1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

**Authorization:**
- `LOW` risk: `ROLE_DEVELOPER` or higher
- `MEDIUM` risk: `ROLE_SENIOR_DEV` or higher
- `HIGH`/`CRITICAL` risk: `ROLE_TECH_LEAD` or higher

---

## Analytics

### Dashboard

```
GET /api/v1/analytics/dashboard
```

Returns aggregated analytics across all projects.

**Response (200):**

```json
{
  "corpus": {
    "totalPatterns": 1247,
    "totalTransformations": 3891,
    "successfulTransformations": 3502,
    "overallSuccessRate": 0.90,
    "patternsByLanguage": {
      "java": 980,
      "cobol": 200,
      "python": 67
    },
    "patternsByCategory": {
      "modernization": 800,
      "cleanup": 300,
      "security": 147
    }
  },
  "acceptance": {
    "overallAcceptanceRate": 0.87,
    "byRule": [
      {
        "ruleName": "Anonymous Class to Lambda Expression",
        "totalReviewed": 512,
        "accepted": 478,
        "acceptanceRate": 0.934
      }
    ],
    "byCategory": [
      {
        "category": "modernization",
        "totalReviewed": 2100,
        "accepted": 1890,
        "acceptanceRate": 0.90
      }
    ]
  },
  "riskDistribution": {
    "low": 2800,
    "medium": 800,
    "high": 250,
    "critical": 41,
    "meanRiskScore": 0.23,
    "medianRiskScore": 0.15
  },
  "confidenceCalibration": {
    "buckets": [
      { "predictedMin": 0.9, "predictedMax": 1.0, "actualRate": 0.94, "count": 1200 },
      { "predictedMin": 0.8, "predictedMax": 0.9, "actualRate": 0.85, "count": 900 },
      { "predictedMin": 0.7, "predictedMax": 0.8, "actualRate": 0.73, "count": 600 }
    ],
    "brierScore": 0.042,
    "expectedCalibrationError": 0.031
  },
  "pipelineHealth": {
    "totalPatchesGenerated": 4200,
    "totalPatchesVerified": 3891,
    "totalPatchesAccepted": 3502,
    "totalPatchesRejected": 389,
    "meanVerificationTimeSeconds": 12.5,
    "meanReviewTimeSeconds": 3600
  },
  "generatedAt": "2026-02-16T12:00:00Z"
}
```

### Corpus Analytics

```
GET /api/v1/analytics/corpus/{projectId}
```

Returns corpus analytics scoped to a specific project.

**Response (200):**

```json
{
  "totalEntries": 156,
  "acceptedCount": 140,
  "rejectedCount": 16,
  "acceptanceRateByRule": {
    "ANON_TO_LAMBDA": 0.95,
    "FOR_LOOP_TO_STREAM": 0.82
  },
  "riskDistribution": {
    "LOW": 120,
    "MEDIUM": 30,
    "HIGH": 6
  },
  "avgTimeToAccept": "PT45M",
  "confidenceCalibration": [
    { "bucket": 0.9, "actualRate": 0.95, "sampleCount": 80 },
    { "bucket": 0.8, "actualRate": 0.83, "sampleCount": 50 }
  ],
  "topFailurePatterns": [
    { "reason": "Lambda captures mutable state", "occurrences": 5 },
    { "reason": "Concurrent context requires extra review", "occurrences": 3 }
  ]
}
```

---

## Migration Corpus

### Search Similar Migrations

```
POST /api/v1/corpus/search
```

Finds the most similar past migrations using embedding similarity.

**Request Body:**

```json
{
  "patchId": "p1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "limit": 10
}
```

**Response (200):**

```json
{
  "query": {
    "patchId": "p1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "ruleId": "ANON_TO_LAMBDA"
  },
  "results": [
    {
      "entryId": "m1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "similarity": 0.96,
      "ruleId": "ANON_TO_LAMBDA",
      "accepted": true,
      "riskTier": "LOW",
      "confidenceScore": 0.92,
      "projectName": "order-service",
      "reviewedAt": "2026-01-15T09:00:00Z"
    }
  ],
  "estimatedSuccessProbability": 0.93
}
```

### Estimate Success Probability

```
POST /api/v1/corpus/predict
```

Estimates the probability that a patch will be accepted, based on K-nearest-neighbor analysis.

**Request Body:**

```json
{
  "patchId": "p1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

**Response (200):**

```json
{
  "patchId": "p1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "estimatedSuccessProbability": 0.93,
  "neighborCount": 50,
  "confidenceInterval": {
    "lower": 0.88,
    "upper": 0.97
  }
}
```

---

## Verification

### Get Verification Result

```
GET /api/v1/patches/{patchId}/verification
```

Returns the full verification result including the Behavioral Equivalence Certificate.

**Response (200):**

```json
{
  "patchId": "p1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "certificateId": "cert-abc123",
  "overallVerdict": "PASS",
  "riskScore": 0.12,
  "riskThreshold": 0.85,
  "layerResults": [
    { "layerId": "compile", "verdict": "PASS", "durationMs": 2300 },
    { "layerId": "ast-structural", "verdict": "PASS", "durationMs": 150 },
    { "layerId": "bytecode-descriptor", "verdict": "PASS", "durationMs": 800 },
    { "layerId": "api-signature", "verdict": "PASS", "durationMs": 100 },
    { "layerId": "test-execution", "verdict": "PASS", "durationMs": 8500, "testsPassed": 12 },
    { "layerId": "golden-master", "verdict": "SKIP", "reason": "No golden master configured" },
    { "layerId": "semantic-risk", "verdict": "PASS", "riskContribution": 0.12 }
  ],
  "originalAstHash": "sha256:a1b2c3d4e5f6...",
  "transformedAstHash": "sha256:7890abcdef12...",
  "contentHash": "sha256:456789abcdef...",
  "issuedAt": "2026-02-16T10:06:15Z",
  "verifierVersion": "1.0.0-SNAPSHOT"
}
```

---

## Health & Monitoring

### Health Check

```
GET /actuator/health
```

**Response (200):**

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "diskSpace": { "status": "UP" },
    "ping": { "status": "UP" }
  }
}
```

### Liveness Probe

```
GET /actuator/health/liveness
```

### Readiness Probe

```
GET /actuator/health/readiness
```

### Prometheus Metrics

```
GET /actuator/prometheus
```

Returns Prometheus-formatted metrics for scraping.

---

## Error Codes

| HTTP Status | Code | Description |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Request body failed validation |
| 401 | `UNAUTHORIZED` | Missing or invalid JWT token |
| 403 | `FORBIDDEN` | Insufficient role for this operation |
| 404 | `NOT_FOUND` | Resource not found |
| 409 | `CONFLICT` | Resource already exists or state conflict |
| 422 | `UNPROCESSABLE_ENTITY` | Valid request but cannot be processed (e.g., invariant violation) |
| 429 | `RATE_LIMITED` | Too many requests |
| 500 | `INTERNAL_ERROR` | Unexpected server error |
| 502 | `UPSTREAM_ERROR` | Worker or external service failure |
| 503 | `SERVICE_UNAVAILABLE` | System is starting up or shutting down |

**Error Response Format:**

```json
{
  "status": 400,
  "error": "VALIDATION_ERROR",
  "message": "Project name is required",
  "timestamp": "2026-02-16T10:00:00Z",
  "path": "/api/v1/projects",
  "details": [
    {
      "field": "name",
      "message": "Project name is required",
      "rejectedValue": null
    }
  ]
}
```
