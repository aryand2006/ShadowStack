# Converter parity — what we claim vs roadmap

ShadowStack labels language tracks as **full**, **adapter**, or **detect-only** in Meta (`/api/v1/meta/languages`) and the README. This note defines the claim language so product and engineering stay honest.

## Claim language (now)

| Phrase | Meaning |
|--------|---------|
| **Full fail-closed AST converter with industry-aligned gates** | Parse/detect/apply via AST (or structured COBOL parse) for Meta-listed transformative rules; verify with a native syntax/compile gate; **missing gate → FAIL** (never soft structural PASS); orchestration promotes only on overall PASS. |
| **Full syntax-gated converter** | Same bar: industry *class* of tools (OpenRewrite, .NET Upgrade Assistant, GnuCOBOL-gated COBOL→COBOL, ESLint/jscodeshift-style JS). Syntax and structure gates — not semantic whole-system rehost. |
| **Blu Age–class for supported surfaces** | COBOL→Java translate covers documented mainframe surfaces (files, CALL/LINKAGE, CICS/BMS MVP, IMS/SQL stub, JCL INCLUDE/PROC, nested programs) with `javac` hard gate — **not** bit-identical IBM or a licensed Blu Age clone. |
| **Detect-only** | Candidates may be listed; apply is identity / stub / advisory; must **not** receive Meta status `full` for that track. |

### Current Meta statuses

| Track | Status | Native gate |
|-------|--------|-------------|
| Java | full | `javac` / JDT pipeline |
| Python | full | `python3` + `py_compile` |
| JavaScript/TS | full | `node --check` |
| C# | full | `dotnet build` (+ `.csproj`) |
| COBOL **preserving** | full | `cobc -fsyntax-only` |
| COBOL **translate** | full | `javac` (Blu Age–class supported surfaces; missing javac → FAIL) |

## What we do **not** claim

- **Bit-identical IBM** CICS/IMS/VSAM/BMS/IDCAMS or a **licensed Blu Age product** replacement.
- Certified whole-system semantic parity with every mainframe scheduler/data store.
- Bit-identical runtime equivalence across every modernization rule without human review.

## Industry alignment (toward, not equal)

We align **toward** the converter *class* of:

- **OpenRewrite** / Sonar / JDK deprecation recipes (Java)
- **lib2to3 / modernize / pyupgrade / LibCST** (Python)
- **ESLint / jscodeshift** (JavaScript)
- **.NET Upgrade Assistant / Roslyn analyzers** (C#)
- **GnuCOBOL-gated COBOL→COBOL** (preserving) and **javac-gated COBOL→Java Blu Age–class rehost for supported surfaces** (translate)

That enables marketing claims of **full AST-gated converters**, **syntax-gated modernization parity with industry converter-class tools**, and **Blu Age–class COBOL translate for supported surfaces** — still **not** “full Blu Age” / bit-identical IBM.

## Roadmap (stretch / non-goals)

1. Deeper dialect verbs and I/O correctness beyond the current supported-surface set — still fail-closed. See **`docs/blu-age-cobol-roadmap.md`**.
2. Broader AST apply coverage so fewer Meta-listed rules fall back to regex (Py2-only syntax may remain regex when LibCST cannot parse).
3. Stronger multi-file / project-graph gates beyond single-file syntax checks.
4. Explicit non-goals: POINTER/BASED beyond string-key BY REFERENCE heap; cataloged JCL dataset resolution; bit-identical IBM runtimes.

## Engineering invariants

1. Hard-fail verify when the native gate binary is missing (mirror C# / `dotnet`; COBOL translate uses `javac`).
2. Transformative apply for Meta-listed rules on a `full` track; detect-only tracks stay `detect-only`.
3. Fail-closed orchestration: only PASS enters the review queue.
