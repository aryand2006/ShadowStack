# Converter parity — what we claim vs roadmap

ShadowStack labels language tracks as **full**, **adapter**, or **detect-only** in Meta (`/api/v1/meta/languages`) and the README. This note defines the claim language so product and engineering stay honest.

## Claim language (now)

| Phrase | Meaning |
|--------|---------|
| **Full fail-closed AST converter with industry-aligned gates** | Parse/detect/apply via AST (or structured COBOL parse) for Meta-listed transformative rules; verify with a native syntax/compile gate; **missing gate → FAIL** (never soft structural PASS); orchestration promotes only on overall PASS. |
| **Full syntax-gated converter** | Same bar: industry *class* of tools (OpenRewrite, .NET Upgrade Assistant, GnuCOBOL-gated COBOL→COBOL, ESLint/jscodeshift-style JS). Syntax and structure gates — not semantic whole-system rehost. |
| **Detect-only** | Candidates may be listed; apply is identity / stub / advisory; must **not** receive Meta status `full` for that track. |

### Current Meta statuses

| Track | Status | Native gate |
|-------|--------|-------------|
| Java | full | `javac` / JDT pipeline |
| Python | full | `python3` + `py_compile` |
| JavaScript/TS | full | `node --check` |
| C# | full | `dotnet build` (+ `.csproj`) |
| COBOL **preserving** | full | `cobc -fsyntax-only` |
| COBOL **translate** | detect-only | cobc intentionally skipped |

## What we do **not** claim

- **Blu Age equivalent** / **Blu Age semantic rehost** for COBOL→Java (or any language). Translate stubs are migration *hints*, not certified behavioral rehost.
- Mainframe environment parity (CICS/IMS/JCL, data stores, batch schedulers).
- Bit-identical runtime equivalence across every modernization rule without human review.

## Industry alignment (toward, not equal)

We align **toward** the converter *class* of:

- **OpenRewrite** / Sonar / JDK deprecation recipes (Java)
- **lib2to3 / modernize / pyupgrade / LibCST** (Python)
- **ESLint / jscodeshift** (JavaScript)
- **.NET Upgrade Assistant / Roslyn analyzers** (C#)
- **GnuCOBOL-gated COBOL→COBOL** patterns (preserving); roadmap interest in Blu Age–style *semantic* COBOL→Java

That enables marketing claims of **full AST-gated converters** and **syntax-gated modernization parity with industry converter-class tools**. It does **not** authorize “Blu Age parity” for translate.

## Roadmap (not claimed yet)

1. Deeper semantic COBOL→Java (data division mapping, PERFORM graphs, file I/O) with behavioral certificates — *toward* Blu Age–class rehost, still fail-closed.
2. Broader AST apply coverage so fewer Meta-listed rules fall back to regex (Py2-only syntax may remain regex when LibCST cannot parse).
3. Stronger multi-file / project-graph gates beyond single-file syntax checks.

## Engineering invariants

1. Hard-fail verify when the native gate binary is missing (mirror C# / `dotnet`).
2. Transformative AST apply for Meta-listed rules on a `full` track; detect-only tracks stay `detect-only`.
3. Fail-closed orchestration: only PASS enters the review queue.
