# ShadowStack

**The Verified Language Modernization Engine**

> Modernize legacy systems with compile- and syntax-gated patches, and attach verification evidence to every item in the review queue.

---

## What Is ShadowStack?

ShadowStack is an enterprise modernization workbench for **verified code conversion** with industry-aligned AST engines:

| Language | Engine | Gate |
|----------|--------|------|
| Java | Eclipse JDT | `javac` compile |
| Python | LibCST | `py_compile` |
| JavaScript/TS | Acorn | `node --check` |
| C# | Roslyn | `dotnet build` |
| COBOL | Structural + GnuCOBOL | `cobc` (preserving track) |

Soft/WARN results do **not** enter the review queue.

The pipeline is:

- **Fail-closed**: Only hard verification PASS promotes a patch to pending review
- **Auditable**: Every action is recorded in an immutable audit log
- **Human-Controlled**: No automatic final conversion — every change requires explicit developer approval
- **Multi-language full converters**: Java, Python, JavaScript, C#, and COBOL-preserving — each on an industry-standard parse frontend

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
| **Java** | ✅ Full (JDT + javac) | OpenRewrite/Sonar/Jakarta classics (~62): anon→lambda, diamond, Guava→JDK, `javax`→`jakarta`, Optional/Objects/Map idioms, sequenced collections, JUnit4→5, boxing, collections, charset, deprecations |
| **Python** | ✅ Full (LibCST + py_compile) | lib2to3/modernize/pyupgrade on AST: xrange/iter*/imports/unicode/has_key/reduce/types.* / octal / f-strings; Py2 print/`<>` via regex fallback when LibCST cannot parse |
| **COBOL** | ✅ Full preserving (cobc) + translate adapter | **preserving**: fixed→free, GOBACK, PERFORM, NEXT SENTENCE→CONTINUE, EVALUATE TRUE→IF; **translate**: DISPLAY/MOVE/… stubs (detect-only, never cobc PASS) |
| **JavaScript/TS** | ✅ Full (Acorn + node --check) | ES5/CommonJS→modern on AST: var/let/const, ===, substr, includes/startsWith, spread, escape, template literals; CJS→ESM detect |
| **C#** | ✅ Full (Roslyn + dotnet) | Upgrade Assistant / CA classics on Roslyn: ArrayList/Hashtable, string.Format, nameof, nullable, using declarations, file-scoped namespaces, HttpClient migrations |

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

The `CobolAdapter` exposes two tracks:

| Track | Status | Verification |
|-------|--------|--------------|
| **preserving** | full | `cobc -fsyntax-only` (adds `-free` after fixed→free / `>>SOURCE FREE`) |
| **translate** | adapter | Java-ish stubs; cobc intentionally skipped |

It parses fixed-format COBOL-85 (cols 1–6 sequence area, col 7 indicator, cols 8–72 program area, cols 73–80 identification area) and free-format COBOL-2002. Industry alignment: GnuCOBOL and IBM Enterprise COBOL modernization patterns.

| Rule ID | Track | Transformation | Risk |
|---------|-------|----------------|------|
| `cobol.fixed_to_free` | preserving | Fixed-format → free-format + `>>SOURCE FREE` | MODERATE |
| `cobol.stop_run_to_goback` | preserving | `STOP RUN` → `GOBACK` | MODERATE |
| `cobol.goto_to_perform` | preserving | Terminal `GO TO PARA.` → `PERFORM PARA.` | MODERATE |
| `cobol.exit_program_to_goback` | preserving | `EXIT PROGRAM` → `GOBACK` | LOW |
| `cobol.next_sentence_to_continue` | preserving | `NEXT SENTENCE` → `CONTINUE` | MODERATE |
| `cobol.evaluate_true_simplify` | preserving | `EVALUATE TRUE` → `IF` / `ELSE IF` | MODERATE |
| `cobol.alter_removed` / `cobol.remove_alter` | preserving | Detect-only ALTER flag | HIGH |
| `cobol.display_to_print` etc. | translate | COBOL→Java-ish migration stubs | varies |

A worked example lives at [`examples/legacy-cobol/PAYROLL.cob`](examples/legacy-cobol/PAYROLL.cob) (`cobc -fsyntax-only` clean).

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
│   ├── language-adapters/ # LanguageAdapter interface + Java/COBOL/Python/JS/C#
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
│   ├── legacy-cobol/     # Example COBOL-85 program (drives CobolAdapter rules)
│   ├── legacy-javascript/ # Example CommonJS/ES5 module
│   └── legacy-csharp/    # Example legacy .NET Framework snippet
├── scripts/
│   ├── demo.sh           # Full workflow demo
│   ├── setup.sh          # Environment setup
│   └── build.sh          # Build all modules
├── docker-compose.yml    # Full local stack
└── pom.xml               # Parent Maven POM
```

---

## Company demo (honest path)

**Prerequisites:** Java 21, Maven 3.9+, `python3` with LibCST (`pip install -r packages/language-adapters/native-engines/python/requirements.txt`), Node 20+ (JS gate / UI), and optionally `dotnet` 8+ (C# gate) and `cobc` / GnuCOBOL (COBOL full gate). Run `./scripts/setup.sh --check` to verify.

Do **not** pitch the product until this path works on your machine:

```bash
# 1) API without Postgres (seeds examples/legacy-* into the review queue)
mvn -pl apps/api -am package -DskipTests
java -jar apps/api/target/shadowstack-api-*.jar --spring.profiles.active=demo

# 2) Fail-closed API walkthrough (API must already be running; exits non-zero if anything is fake/empty)
./scripts/demo.sh

# 3) Optional Web UI against the live API
cd apps/web && npm install && npm run dev
# open http://localhost:3000/queue
```

Credentials: `admin` / `admin` (HTTP Basic or `POST /api/v1/auth/login`).

What is real today: multi-language live convert (Java/Python/COBOL/JS/C#) → analyze → generate → verify → review queue → accept/reject.
Gates: Java `javac`, Python `py_compile` (LibCST AST + regex fallback), JS `node --check` (Acorn AST), C# `dotnet build` (Roslyn AST; requires `.csproj` in the project tree), COBOL-preserving `cobc` (translate track is detect-only).
What is stubbed: Postgres corpus analytics and invented dashboard KPIs.

> **Docker note:** `infra/docker/Dockerfile.api` is a JVM-only runtime. Full converter parity (LibCST / Acorn / Roslyn / cobc) is intended for the host demo path above, or an image that also installs those toolchains and copies `packages/language-adapters/native-engines/`.

## Quick Start

### Prerequisites

- Java 21 (Eclipse Temurin recommended)
- Maven 3.9+
- Python 3.10+ with LibCST (`pip install -r packages/language-adapters/native-engines/python/requirements.txt`)
- Node.js 20+ (web UI and JS `--check` gate)
- Optionally: .NET 8 SDK (C# gate), GnuCOBOL / `cobc` (COBOL full gate)
- Docker & Docker Compose (full local stack)

### Local Development

```bash
# 1. Clone and setup
git clone <repo-url> && cd shadowstack
./scripts/setup.sh

# 2. Start the full stack
docker compose up -d

# 3. Run the API demo (API must already be running — see Company demo above)
./scripts/demo.sh

# Optional UI
cd apps/web && npm install && npm run dev
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
11. ✅ Python adapter — Python 2 → 3 modernization (83 rules)
12. ✅ COBOL adapter — preserving (full/cobc) + translate tracks
13. ✅ JavaScript/TypeScript adapter — CommonJS/ES5 → modern ESM (20 rules)
14. ✅ C# adapter — .NET Framework → modern patterns (19 rules)
15. ✅ Java industry rule catalog expanded to 45 OpenRewrite/Sonar/JDK rules

---

## License

Proprietary. All rights reserved.

---

<p align="center">
<strong>ShadowStack</strong>. More serious. More verifiable. More enterprise-ready.
</p>
