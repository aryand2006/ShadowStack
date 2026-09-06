# ShadowStack

**The Verified Language Modernization Engine**

> Modernize legacy Java systems to Java 17/21/25, safely, verifiably, and provably, with the strongest trust guarantees in the market.

---

## What Is ShadowStack?

ShadowStack is a production-grade enterprise platform for **verified code modernization**. It transforms legacy codebases into modern, maintainable systems through a pipeline that is:

- **Verifiable**: Every transformation is proven correct through multi-layer verification
- **Auditable**: Every action is recorded in an immutable audit log
- **Human-Controlled**: No automatic final conversion. Every change requires explicit developer approval
- **Intelligent**: A migration corpus learns from every accepted and rejected transformation

ShadowStack does not compete on autocomplete. It competes on **trust, proof, controlled transformation, and recorded migration intelligence**.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     ShadowStack Platform                    │
├──────────┬──────────┬──────────┬──────────┬─────────────────┤
│  Web UI  │   API    │  Worker  │ Embedder │   Postgres +    │
│ (Next.js)│ (Spring) │  (Java)  │ (Python) │   pgvector      │
├──────────┴──────────┴──────────┴──────────┴─────────────────┤
│                    Core Engine Packages                     │
├──────────┬──────────┬──────────┬──────────┬─────────────────┤
│ Language │   Core   │ Refactor │  Verify  │   Migration     │
│ Adapters │ Analysis │  Engine  │  Engine  │    Corpus       │
└──────────┴──────────┴──────────┴──────────┴─────────────────┘
```

### Language Adapter Architecture

ShadowStack is built around a **pluggable language adapter framework**:

| Adapter | Status | Description |
|---------|--------|-------------|
| **Java** | ✅ Full | OpenRewrite/Sonar classics: anon→lambda, diamond, `List.sort`, `StringBuffer`→`StringBuilder`, `Vector`→`ArrayList`, `Hashtable`→`HashMap`, `Stack`→`ArrayDeque`, boxing `valueOf`, `size()==0`→`isEmpty()`, `indexOf`→`contains`, `Class.newInstance`, literal-first `equals`, `toUpper/LowerCase(Locale.ROOT)`, `Collections.EMPTY_*`→`empty*()`, `getBytes(UTF_8)`, `URLEncoder` UTF-8, `trim`→`strip`
| **Python** | ✅ Full | lib2to3/modernize set: print/print>>, xrange, iter*, imap/izip/ifilter, reduce→functools, unicode/basestring, except/as, `<>`, has_key, raw_input, long, raise, file, apply, urllib2/ConfigParser/Queue/thread/commands/urlparse/httplib/BaseHTTPServer/md5/sha/sets/UserDict/robotparser, execfile, u-prefix, unichr, reload, intern, StandardError, `.next()`, backticks, cPickle/cStringIO/__builtin__/Cookie/...
| **COBOL** | ✅ Partial | Enterprise COBOL→modern: fixed→free, STOP RUN→GOBACK, GO TO→PERFORM, DISPLAY/MOVE/COMPUTE/PERFORM/ADD/SUBTRACT/ACCEPT/MULTIPLY/DIVIDE/INITIALIZE/EXIT PROGRAM/STRING/SET, INSPECT/UNSTRING/OPEN/CLOSE/READ/WRITE/CALL/CONTINUE, ALTER flags

Each adapter implements: `parse()` → `buildSemanticModel()` → `listRefactorCandidates()` → `applyRefactor()` → `verifyPatch()`

### Verified Refactor Pipeline

```
Phase 0              Phase 1              Phase 2              Phase 3
┌──────────┐    ┌──────────────┐    ┌──────────────┐    ┌───────────────┐
│ Baseline │───▶│   Static     │───▶│    Patch     │───▶│  Multi-Layer  │
│ Capture  │    │  Semantic    │    │  Generation  │    │ Verification  │
│          │    │  Modeling    │    │  (atomic)    │    │  (7 layers)   │
└──────────┘    └──────────────┘    └──────────────┘    └───────┬───────┘
  • Compile       • Call Graph        • Independent             │
  • Test          • Data Flow         • Non-overlapping         ▼
  • Snapshot      • Mutation          • Metadata-rich   ┌───────────────┐
  • Fingerprint   • Purity/Risk       • Rationale       │ Human Review  │
                                                        │ Accept/Reject │
                                                        └───────┬───────┘
                                                                │
                                                                ▼
                                                        ┌───────────────┐
                                                        │  Migration    │
                                                        │  Corpus       │
                                                        │  (pgvector)   │
                                                        └───────────────┘
```

### Verification Layers

| Layer | What It Checks |
|-------|----------------|
| 1. Compile | Before/after compilation success |
| 2. Unit Tests | Test suite execution and regression detection |
| 3. Golden Master | Snapshot comparison of outputs |
| 4. AST Structural | Abstract syntax tree similarity scoring |
| 5. Bytecode | Method descriptor and signature comparison (ASM) |
| 6. API Surface | Public method/field/constructor compatibility |
| 7. Semantic Risk | Deterministic risk score with explicit formula |

---

## First Refactor Rule: Anonymous Class → Lambda

The flagship rule converts anonymous class instances to Java 8+ lambda expressions, **only when provably safe**:

**Safety Invariants (ALL must pass):**
- ✅ Target is a functional interface (single abstract method)
- ✅ No improper outer `this` capture
- ✅ No mutation of non-effectively-final variables
- ✅ No overridden Object methods (toString, equals, hashCode)
- ✅ No reflection usage referencing class name

**Confidence Score Formula:**
```
base = 0.95
if (capturesOuterVars)    score -= 0.10
if (multipleStatements)   score -= 0.05
if (usesGenerics)         score -= 0.05
if (inConcurrentContext)  score -= 0.15
clamp to [0.0, 1.0]
```

**Risk Score Formula:**
```
risk = 0.0
if (!compileSuccess)           risk += 0.40
if (!testSuccess)              risk += 0.30
if (astDelta > threshold)      risk += 0.10
if (bytecodeSignatureMismatch) risk += 0.10
if (apiSurfaceChanged)         risk += 0.10
```

Changes above the configured risk threshold are **automatically blocked** from approval.

---

## Python Modernization Rules

The `PythonAdapter` ships with a string- and comment-aware lexer so that pattern detection never misfires inside literals or comments. It runs entirely in-process; the optional compile-check verifier shells out to `python3 -m py_compile` if a Python runtime is present.

| Rule ID | Transformation | Risk |
|---------|----------------|------|
| `py.print_stmt_to_call` | `print x` → `print(x)` | LOW |
| `py.xrange_to_range` | `xrange(...)` → `range(...)` | LOW |
| `py.iter_methods_to_views` | `.iteritems() / .iterkeys() / .itervalues()` → `.items() / .keys() / .values()` | LOW |
| `py.except_comma_to_as` | `except E, e:` → `except E as e:` | LOW |
| `py.unicode_to_str` | `unicode(x)` / `basestring` → `str(x)` / `str` | LOW |
| `py.ne_operator` | `<>` → `!=` | LOW |

Each candidate carries two safety invariants: a `py-strlit` proof that the match is in executable code (verified via the code-mask scan) and a `py-rationale` documenting the upstream Python 3 specification.

A worked example lives at [`examples/legacy-python/report_builder.py`](examples/legacy-python/report_builder.py).

## COBOL Modernization Rules

The `CobolAdapter` parses fixed-format COBOL-85 (cols 1–6 sequence area, col 7 indicator, cols 8–72 program area, cols 73–80 identification area) and free-format COBOL-2002. It extracts `PROGRAM-ID`, divisions, sections, paragraphs, working-storage items (with `PIC` clauses mapped to a normalized type system), and `PERFORM`-based call-graph edges.

| Rule ID | Transformation | Risk |
|---------|----------------|------|
| `cobol.fixed_to_free` | Fixed-format reference format → COBOL-2002 free-format (comments rewritten to `*>`, continuation/debug lines preserved) | MODERATE |
| `cobol.stop_run_to_goback` | `STOP RUN` → `GOBACK` (CICS-safe, sub-program-safe) | MODERATE |
| `cobol.goto_to_perform` | Terminal `GO TO PARA.` → `PERFORM PARA.` when the GO TO is the last statement in its paragraph | MODERATE |
| `cobol.alter_removed` | Flags `ALTER` statements (removed in COBOL-2002) for manual rewrite | HIGH |

The fixed→free verifier re-parses the converted program and confirms a canonical-form AST hash equality — proof that the transformation is purely structural.

A worked example lives at [`examples/legacy-cobol/PAYROLL.cob`](examples/legacy-cobol/PAYROLL.cob).

---

## Human-in-the-Loop Workflow

**No automatic final conversion.** Every transformation must be:

1. **Proposed**: Generated as an atomic, independently verifiable patch
2. **Verified**: Passed through all 7 verification layers
3. **Reviewed**: Presented to a developer with:
   - Unified diff
   - Human-readable rationale
   - Safety invariant checklist
   - Risk score with visual gauge
   - Full verification evidence
4. **Explicitly accepted or rejected**: With rejection reason recorded

Both accepted and rejected transformations feed the **Migration Intelligence Corpus**.

---

## Migration Intelligence Corpus

Every transformation decision is recorded and embedded:

- **Before/after snippets** with AST context
- **Risk tier**, confidence score, verification metrics
- **Developer acceptance** status and rejection reasons
- **768-dimensional embeddings** via custom CodeBERT encoder
- **Vector similarity search** via pgvector for pattern retrieval

**Analytics include:**
- Acceptance rate per rule
- Confidence calibration curves
- Failure pattern analysis
- Migration success distribution

This corpus is ShadowStack's competitive moat.

---

## Enterprise Security Posture

| Control | Implementation |
|---------|---------------|
| **Offline-Only** | No telemetry, no external LLM calls |
| **RBAC** | ADMIN, REVIEWER, ANALYST, VIEWER roles |
| **Audit Logs** | Every API call logged with actor, timestamp, details |
| **Immutable Artifacts** | Patches and certificates cannot be modified post-creation |
| **Encryption at Rest** | AES-256 for stored source code and embeddings |
| **JWT Authentication** | Stateless token-based auth with role claims |
| **SOC2 Controls** | Full CC1-CC9 mapping documented |
| **Threat Model** | STRIDE analysis with mitigations |

---

## Monorepo Structure

```
shadowstack/
├── apps/
│   ├── api/              # Spring Boot 3.3 REST API (Java 21)
│   ├── web/              # Next.js 14 dashboard UI
│   └── worker/           # Async task processing service
├── packages/
│   ├── language-adapters/ # LanguageAdapter interface + Java/COBOL/Python
│   ├── core-analysis/     # Baseline capture, call graph, risk scoring
│   ├── refactor-engine/   # Refactor rules + patch generation
│   ├── verify-engine/     # 7-layer verification pipeline
│   ├── migration-corpus/  # Postgres + pgvector corpus service
│   └── embedder/          # Custom CodeBERT encoder (Python)
├── infra/
│   ├── docker/           # Dockerfiles for all services
│   └── k8s/              # Kubernetes manifests
├── docs/
│   ├── architecture.md   # Full architecture with Mermaid diagrams
│   ├── threat-model.md   # STRIDE threat analysis
│   ├── soc2-controls.md  # SOC2 control mapping
│   ├── api-reference.md  # Complete API documentation
│   └── deployment-guide.md
├── examples/
│   ├── legacy-sample/    # Example legacy Java project
│   ├── legacy-python/    # Example Python 2 module (drives PythonAdapter rules)
│   └── legacy-cobol/     # Example COBOL-85 program (drives CobolAdapter rules)
├── scripts/
│   ├── demo.sh           # Full workflow demo
│   ├── setup.sh          # Environment setup
│   └── build.sh          # Build all modules
├── docker-compose.yml    # Full local stack
└── pom.xml               # Parent Maven POM
```

---

## Quick Start

### Prerequisites

- Java 21 (Eclipse Temurin recommended)
- Node.js 20+
- Docker & Docker Compose
- Maven 3.9+

### Local Development

```bash
# 1. Clone and setup
git clone <repo-url> && cd shadowstack
./scripts/setup.sh

# 2. Start the full stack
docker compose up -d

# 3. Run the demo
./scripts/demo.sh
```

### Demo Workflow

```bash
# Ingest a legacy project
curl -X POST http://localhost:8080/api/v1/projects \
  -H "Content-Type: application/json" \
  -d '{"name": "legacy-sample", "repoUrl": "./examples/legacy-sample", "sourceLanguage": "java", "sourceVersion": "1.8", "targetVersion": "21"}'

# Capture baseline
curl -X POST http://localhost:8080/api/v1/projects/{id}/baseline

# Run analysis and generate patches
curl -X POST http://localhost:8080/api/v1/projects/{id}/analyze
curl -X POST http://localhost:8080/api/v1/projects/{id}/candidates/{cid}/generate-patch

# Review in the UI
open http://localhost:3000/review

# Accept or reject
curl -X POST http://localhost:8080/api/v1/reviews/{patchId}/accept

# View migration corpus entry
curl http://localhost:8080/api/v1/analytics/corpus
```

---

## Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Language Analysis | Eclipse JDT Core | 3.36.0 |
| Bytecode Analysis | ASM | 9.7 |
| API Server | Spring Boot | 3.3 |
| Web Dashboard | Next.js + React | 14.2 / 18 |
| Database | PostgreSQL + pgvector | 16 + 0.7 |
| ML Embedder | PyTorch + CodeBERT | 2.2+ |
| Inference API | FastAPI | 0.109+ |
| Container Runtime | Docker | 24+ |
| Orchestration | Kubernetes | 1.28+ |
| Build System | Maven (Java) / npm (JS) | 3.9+ / 10+ |

---

## Build Order

The platform was built in this order, with each layer depending on the previous:

1. ✅ Monorepo scaffold
2. ✅ Language adapter interfaces
3. ✅ Java adapter implementation (Eclipse JDT)
4. ✅ Baseline + verification engine
5. ✅ First elite refactor rule (Anonymous Class → Lambda)
6. ✅ Patch review workflow
7. ✅ Migration corpus storage (Postgres + pgvector)
8. ✅ Embedder training + inference (CodeBERT + FastAPI)
9. ✅ Web UI (Next.js dashboard)
10. ✅ Security docs + hardening
11. ✅ Python adapter — Python 2 → 3 modernization (44 rules)
12. ✅ COBOL adapter — fixed-format parser + 25 modernization rules

---

## License

Proprietary. All rights reserved.

---

<p align="center">
<strong>ShadowStack</strong>. More serious. More verifiable. More enterprise-ready.
</p>
