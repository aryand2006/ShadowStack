# ShadowStack Architecture

> Enterprise Verified Language Modernization Engine

## 1. System Overview

ShadowStack is a modular, pipeline-oriented system that modernizes legacy codebases through **verified, auditable refactoring**. Every transformation passes through a multi-layer verification pipeline before reaching human review, producing a cryptographically-hashed **Behavioral Equivalence Certificate** as proof of correctness.

```mermaid
graph TB
    subgraph Clients
        WEB[Web Dashboard<br/>Next.js]
        CLI[CLI / CI Plugin]
    end

    subgraph API Layer
        API[REST API<br/>Spring Boot]
        AUTH[JWT Auth Filter]
    end

    subgraph Core Pipeline
        LA[Language Adapters]
        CA[Core Analysis]
        RE[Refactor Engine]
        VE[Verify Engine]
    end

    subgraph Intelligence
        MC[Migration Corpus]
        EMB[Embedder Service]
    end

    subgraph Storage
        PG[(PostgreSQL<br/>+ pgvector)]
        BLOB[(Blob Storage<br/>Patches & Evidence)]
    end

    subgraph Infrastructure
        WORKER[Async Worker]
        MQ[Message Queue]
        MON[Monitoring<br/>Prometheus + Grafana]
    end

    WEB --> API
    CLI --> API
    API --> AUTH
    AUTH --> API

    API --> LA
    API --> CA
    API --> RE
    API --> VE
    API --> MC

    MC --> EMB
    MC --> PG

    API --> PG
    API --> BLOB

    API --> MQ
    MQ --> WORKER
    WORKER --> LA
    WORKER --> CA
    WORKER --> RE
    WORKER --> VE

    MON --> API
    MON --> WORKER
```

## 2. Language Adapter Architecture

The Language Adapter layer provides a **language-agnostic interface** to ShadowStack's pipeline. Each supported language implements the `LanguageAdapter` contract, bridging its native toolchain (parser, type resolver, AST rewriter) into the pipeline.

```mermaid
classDiagram
    class LanguageAdapter {
        <<interface>>
        +languageId() String
        +languageVersion() String
        +parse(sourceRoot, config) SemanticModel
        +buildSemanticModel(sourceRoot) SemanticModel
        +listRefactorCandidates(model, rules) List~RefactorCandidate~
        +applyRefactor(candidate, sourceRoot) PatchResult
        +verifyPatch(patch, sourceRoot, config) VerificationResult
    }

    class JavaAdapter {
        -eclipseJdtParser
        -typeResolver
        +languageId() "java"
        +parse()
        +buildSemanticModel()
    }

    class CobolAdapter {
        -gnuCobolParser
        +languageId() "cobol"
        +parse()
        +buildSemanticModel()
    }

    class PythonAdapter {
        -treeSitterParser
        +languageId() "python"
        +parse()
        +buildSemanticModel()
    }

    class SemanticModel {
        +ast: AST
        +callGraph: CallGraph
        +dataFlow: DataFlowGraph
        +typeBindings: Map
        +metrics: ComplexityMetrics
    }

    class RefactorCandidate {
        +candidateId: UUID
        +ruleId: String
        +sourceFile: String
        +startLine: int
        +endLine: int
        +confidenceScore: double
        +riskTier: RiskTier
        +invariants: List~SafetyInvariant~
    }

    class LanguageAdapterConfig {
        +resolveBindings: boolean
        +includeTests: boolean
        +sourceEncoding: String
        +classpathEntries: List~String~
        +excludePatterns: List~String~
    }

    class VerificationConfig {
        +runCompilation: boolean
        +runTests: boolean
        +compareAstStructure: boolean
        +compareBytecode: boolean
        +checkApiSurface: boolean
        +timeoutSeconds: int
    }

    LanguageAdapter <|.. JavaAdapter
    LanguageAdapter <|.. CobolAdapter
    LanguageAdapter <|.. PythonAdapter
    LanguageAdapter --> SemanticModel
    LanguageAdapter --> RefactorCandidate
    LanguageAdapter --> LanguageAdapterConfig
    LanguageAdapter --> VerificationConfig
```

### Adapter Contract

| Method | Purpose |
|---|---|
| `parse()` | Produce a raw AST-level SemanticModel |
| `buildSemanticModel()` | Enrich with call graphs, data flow, type resolution, and metrics |
| `listRefactorCandidates()` | Identify transformation opportunities using rule sets |
| `applyRefactor()` | Execute a single transformation, produce unified diff |
| `verifyPatch()` | Run multi-layer verification on the result |

Implementations must be **thread-safe** for concurrent invocations on different source roots.

## 3. Verified Refactor Pipeline

The pipeline is the heart of ShadowStack. Every transformation passes through four phases before human review.

```mermaid
sequenceDiagram
    participant Client
    participant API
    participant RefactorEngine
    participant VerifyEngine
    participant Corpus
    participant Reviewer

    Note over Client, Reviewer: Phase 1 — Detection & Analysis

    Client->>API: POST /api/v1/projects/{id}/analyze
    API->>RefactorEngine: scan(compilationUnit, semanticContext)
    RefactorEngine->>RefactorEngine: Phase 1: collectCandidates()<br/>Walk AST, match rules
    RefactorEngine->>RefactorEngine: Phase 2: filterCandidates()<br/>Check invariants, confidence, risk
    RefactorEngine->>RefactorEngine: Phase 3: resolveOverlaps()<br/>Greedy by confidence
    RefactorEngine-->>API: List<RefactorCandidate>

    Note over Client, Reviewer: Phase 2 — Patch Generation

    API->>RefactorEngine: generatePatches(candidates)
    RefactorEngine->>RefactorEngine: Phase 4: apply() each candidate
    RefactorEngine->>RefactorEngine: Phase 5: validatePatchIndependence()
    RefactorEngine-->>API: List<PatchUnit> (immutable, non-overlapping)

    Note over Client, Reviewer: Phase 3 — Multi-Layer Verification

    API->>VerifyEngine: verify(patchUnit, context)
    VerifyEngine->>VerifyEngine: Layer 1: CompileVerifier
    VerifyEngine->>VerifyEngine: Layer 2: ASTStructuralComparator
    VerifyEngine->>VerifyEngine: Layer 3: BytecodeDescriptorComparator
    VerifyEngine->>VerifyEngine: Layer 4: APISignatureDiffVerifier
    VerifyEngine->>VerifyEngine: Layer 5: TestExecutionVerifier
    VerifyEngine->>VerifyEngine: Layer 6: GoldenMasterVerifier
    VerifyEngine->>VerifyEngine: Layer 7: SemanticRiskScorer
    VerifyEngine->>VerifyEngine: Issue BehavioralEquivalenceCertificate
    VerifyEngine-->>API: Certificate (SHA-256 content hash)

    Note over Client, Reviewer: Phase 4 — Human Review & Corpus Learning

    API->>Corpus: findSimilarMigrations(embedding)
    Corpus-->>API: Historical context + success probability
    API->>Reviewer: Queue patch for review with context
    Reviewer->>API: POST /api/v1/queue/review (accept/reject)
    API->>Corpus: recordAcceptedTransformation() or recordRejectedTransformation()
    Corpus->>Corpus: Update embeddings, calibrate confidence
```

### Verification Layers

| Layer | Purpose | Verdict Weight |
|---|---|---|
| CompileVerifier | Ensures patched code compiles | Hard gate |
| ASTStructuralComparator | Compares AST structure pre/post | 0.20 |
| BytecodeDescriptorComparator | Compares method descriptors in bytecode | 0.20 |
| APISignatureDiffVerifier | Ensures public API surface unchanged | 0.15 |
| TestExecutionVerifier | Runs existing tests against patched code | 0.25 |
| GoldenMasterVerifier | Compares output against golden master | 0.10 |
| SemanticRiskScorer | Aggregates risk from all layers | 0.10 |

## 4. Migration Intelligence Corpus

The Migration Corpus is ShadowStack's learning system. It records every transformation decision (accepted or rejected), embeds them using a 768-dimensional vector model, and uses this history to improve future recommendations.

### Architecture

```mermaid
graph LR
    subgraph Ingest
        PATCH[PatchUnit] --> EMBED[Embedder]
        EMBED --> VEC[768-dim Vector]
    end

    subgraph Storage
        VEC --> PGVEC[(PostgreSQL<br/>pgvector)]
        META[Metadata + Decision] --> PGVEC
    end

    subgraph Query
        NEW[New Candidate] --> EMBED2[Embedder]
        EMBED2 --> SIM[Cosine Similarity Search]
        PGVEC --> SIM
        SIM --> NEIGHBORS[K-Nearest Neighbors]
        NEIGHBORS --> PROB[Success Probability<br/>Inverse-Distance Weighted]
    end

    subgraph Analytics
        PGVEC --> ACC[Acceptance Rate by Rule]
        PGVEC --> RISK[Risk Distribution]
        PGVEC --> CAL[Confidence Calibration]
        PGVEC --> FAIL[Top Failure Patterns]
    end
```

### Key Capabilities

- **Similarity Search**: Find the K most similar past migrations using pgvector cosine similarity
- **Success Probability Estimation**: Inverse-distance-weighted acceptance probability based on 50 nearest neighbors
- **Confidence Calibration**: Tracks predicted confidence vs. actual acceptance rate across buckets
- **Failure Pattern Mining**: Aggregates rejection reasons to identify systematic issues

## 5. Embedder System Design

The embedder converts code snippets and transformation context into dense vector representations for the Migration Corpus.

| Component | Description |
|---|---|
| **Input** | Before snippet + after snippet + rule metadata + AST context |
| **Tokenizer** | Language-aware tokenizer (preserves identifiers, strips comments) |
| **Model** | Fine-tuned CodeBERT (768-dim output) |
| **Storage** | pgvector extension on PostgreSQL |
| **Index** | IVFFlat index with 100 lists for approximate NN search |
| **Distance** | Cosine similarity (`1 - cosine_distance`) |

## 6. Human-in-the-Loop Workflow

```mermaid
flowchart TD
    START([Patch Generated]) --> VERIFY{Verification<br/>Passed?}
    VERIFY -->|No| REJECT_AUTO[Auto-Reject<br/>Log to Corpus]
    VERIFY -->|Yes| RISK{Risk Tier?}

    RISK -->|LOW + autoApply| AUTO[Auto-Apply<br/>if risk ≤ 0.2]
    RISK -->|LOW| QUEUE_LOW[Queue: Low Priority]
    RISK -->|MEDIUM| QUEUE_MED[Queue: Normal Priority]
    RISK -->|HIGH / CRITICAL| QUEUE_HIGH[Queue: High Priority<br/>Senior Reviewer Required]

    AUTO --> CORPUS[Record in Corpus]
    QUEUE_LOW --> REVIEW{Human Review}
    QUEUE_MED --> REVIEW
    QUEUE_HIGH --> REVIEW

    REVIEW -->|Accept| ACCEPT[Apply Patch]
    REVIEW -->|Reject| REJECT_HUMAN[Record Rejection Reason]

    ACCEPT --> CORPUS
    REJECT_HUMAN --> CORPUS
    REJECT_AUTO --> CORPUS

    CORPUS --> LEARN[Update Embeddings<br/>Calibrate Confidence]
```

### Review Queue Priority

| Risk Tier | Auto-Apply Eligible | Reviewer Level | SLA |
|---|---|---|---|
| COSMETIC | Yes (if ≤ 0.2 risk) | Any | — |
| LOW | No | Any developer | 5 business days |
| MEDIUM | No | Senior developer | 3 business days |
| HIGH | No | Tech lead | 1 business day |
| CRITICAL | No | Architect + Tech lead | Same day |

## 7. Data Flow Diagram

```mermaid
graph TD
    SRC[Source Repository] -->|git clone| INGEST[Ingest Service]
    INGEST -->|source files| PARSE[Language Adapter: Parse]
    PARSE -->|SemanticModel| ANALYZE[Core Analysis]

    ANALYZE -->|CallGraph + DataFlow| BASELINE[Baseline Capture]
    BASELINE -->|BaselineSnapshot| DB[(PostgreSQL)]

    ANALYZE -->|SemanticContext| REFACTOR[Refactor Engine]
    REFACTOR -->|PatchUnit[]| VERIFY[Verify Engine]

    VERIFY -->|BEC Certificate| DB
    VERIFY -->|Evidence artifacts| BLOB[(Blob Storage)]

    DB -->|Patch + Certificate| QUEUE[Review Queue]
    QUEUE -->|Decision| CORPUS[Migration Corpus]
    CORPUS -->|Embedding| PGVEC[(pgvector)]

    QUEUE -->|Accepted patches| APPLY[Patch Applier]
    APPLY -->|Modified source| SRC
```

## 8. Technology Stack

| Layer | Technology | Version | Purpose |
|---|---|---|---|
| **API** | Spring Boot | 3.3.0 | REST API, dependency injection, security |
| **Language** | Java | 21 | Primary implementation language |
| **Web UI** | Next.js + Tailwind CSS | 14.x | Developer dashboard |
| **Database** | PostgreSQL | 16 | Primary data store |
| **Vectors** | pgvector | 0.7.x | Embedding similarity search |
| **Migrations** | Flyway | 10.x | Schema versioning |
| **AST Parsing** | Eclipse JDT Core | 3.36.0 | Java AST parsing with full type resolution |
| **Auth** | jjwt | 0.12.5 | JWT token generation and validation |
| **Build** | Maven | 3.9.x | Multi-module build |
| **Containers** | Docker + Docker Compose | — | Local development and deployment |
| **Orchestration** | Kubernetes | 1.29+ | Production deployment |
| **Monitoring** | Prometheus + Grafana | — | Metrics, alerting, dashboards |
| **Logging** | SLF4J + Logback | 2.0.x | Structured logging |
| **API Docs** | SpringDoc OpenAPI | 2.3.0 | Swagger UI + OpenAPI spec |

## 9. Deployment Architecture

```mermaid
graph TB
    subgraph Kubernetes Cluster
        subgraph Namespace: shadowstack
            ING[Ingress Controller<br/>nginx] --> API_SVC[API Service]
            ING --> WEB_SVC[Web Service]

            API_SVC --> API_POD1[API Pod 1]
            API_SVC --> API_POD2[API Pod 2]
            API_SVC --> API_POD3[API Pod 3]

            WEB_SVC --> WEB_POD1[Web Pod 1]
            WEB_SVC --> WEB_POD2[Web Pod 2]

            WORKER_DEP[Worker Deployment] --> WORKER_POD1[Worker Pod 1]
            WORKER_DEP --> WORKER_POD2[Worker Pod 2]
        end

        subgraph Namespace: data
            PG_STS[PostgreSQL StatefulSet] --> PG_POD[(PostgreSQL<br/>+ pgvector)]
            PG_STS --> PG_PVC[(PersistentVolumeClaim)]
        end
    end

    subgraph External
        LB[Load Balancer] --> ING
        PROM[Prometheus] --> API_POD1
        PROM --> WORKER_POD1
        GRAF[Grafana] --> PROM
    end
```

### Resource Requirements

| Component | CPU Request | CPU Limit | Memory Request | Memory Limit | Replicas |
|---|---|---|---|---|---|
| API | 500m | 2000m | 512Mi | 2Gi | 2–5 (HPA) |
| Worker | 1000m | 4000m | 1Gi | 4Gi | 2–4 (HPA) |
| Web | 100m | 500m | 128Mi | 512Mi | 2 |
| PostgreSQL | 500m | 2000m | 1Gi | 4Gi | 1 (StatefulSet) |

### Health Checks

- **Liveness**: `GET /actuator/health/liveness` (HTTP 200)
- **Readiness**: `GET /actuator/health/readiness` (HTTP 200)
- **Startup**: `GET /actuator/health` with 60s initial delay
- **Metrics**: `GET /actuator/prometheus` (Prometheus scrape target)
