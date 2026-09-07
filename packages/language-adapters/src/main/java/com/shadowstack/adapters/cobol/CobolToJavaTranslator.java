package com.shadowstack.adapters.cobol;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MVP COBOL→Java semantic rehost translator (Phase 1 dialect depth).
 *
 * <p>Converts a practical subset of WORKING-STORAGE + PROCEDURE DIVISION into a
 * compilable Java class {@code Translated{ProgramId}} with {@code main} and
 * paragraph methods. Aimed toward Blu Age–class rehost progress — not full
 * Blu Age (CICS/IMS/JCL out of scope).</p>
 *
 * <p>Phase 1 additions: USAGE COMP/COMP-3, OCCURS, REDEFINES (elementary alias),
 * PERFORM UNTIL/TIMES/VARYING/THRU, SECTION entry points, COPY expansion,
 * and explicit {@link Result#unsupportedGaps()}.</p>
 */
public final class CobolToJavaTranslator {

    private static final int INDICATOR_COL = 6;
    private static final int PROGRAM_AREA_START = 7;
    private static final int PROGRAM_AREA_END = 72;

    private static final Pattern PROGRAM_ID = Pattern.compile(
            "(?i)PROGRAM-ID\\s*\\.?\\s*([A-Z0-9][A-Z0-9-]*)\\s*\\.?");
    /** Level + name; remaining clauses parsed from the rest of the line. */
    private static final Pattern DATA_ITEM_HEAD = Pattern.compile(
            "(?i)^(\\d{1,2})\\s+([A-Z0-9][A-Z0-9-]*)\\b(.*)$");
    private static final Pattern LEVEL_88 = Pattern.compile(
            "(?i)^88\\s+([A-Z0-9][A-Z0-9-]*)\\s+VALUE\\s+(\"[^\"]*\"|'[^']*'|\\S+)\\s*\\.?\\s*$");
    private static final Pattern PARAGRAPH_HEADER = Pattern.compile(
            "(?i)^([A-Z][A-Z0-9-]*)\\s*\\.\\s*$");
    private static final Pattern SECTION_HEADER = Pattern.compile(
            "(?i)^([A-Z][A-Z0-9-]*)\\s+SECTION\\s*\\.\\s*$");
    private static final Pattern SUBSCRIPT = Pattern.compile(
            "(?i)\\b([A-Z][A-Z0-9-]*)\\s*\\(\\s*([A-Z0-9][A-Z0-9-]*|\\d+)\\s*\\)");

    private static final Pattern PERFORM_VARYING = Pattern.compile(
            "(?i)^PERFORM(?:\\s+([A-Z0-9-]+))?\\s+VARYING\\s+([A-Z0-9-]+)\\s+FROM\\s+(\\S+)\\s+BY\\s+(\\S+)\\s+UNTIL\\s+(.+)$");
    private static final Pattern PERFORM_UNTIL = Pattern.compile(
            "(?i)^PERFORM(?:\\s+([A-Z0-9-]+))?\\s+UNTIL\\s+(.+)$");
    private static final Pattern PERFORM_TIMES = Pattern.compile(
            "(?i)^PERFORM(?:\\s+([A-Z0-9-]+))?\\s+(\\d+|\\S+)\\s+TIMES$");
    private static final Pattern PERFORM_THRU = Pattern.compile(
            "(?i)^PERFORM\\s+([A-Z0-9-]+)\\s+THRU\\s+([A-Z0-9-]+)$");
    private static final Pattern PERFORM_SIMPLE = Pattern.compile(
            "(?i)^PERFORM\\s+([A-Z0-9-]+)$");

    private CobolToJavaTranslator() {}

    /**
     * Translate COBOL source into a Java class skeleton (no copybook search).
     */
    public static Result translate(String cobolSource) {
        return translate(cobolSource, null);
    }

    /**
     * Translate COBOL source, expanding COPY books under {@code sourceRoot} first.
     *
     * @param cobolSource full COBOL program text (fixed or free format)
     * @param sourceRoot  project root for copybook resolution; may be null
     * @return translation result with Java source, metadata, and unsupported gaps
     */
    public static Result translate(String cobolSource, Path sourceRoot) {
        Objects.requireNonNull(cobolSource, "cobolSource");
        List<String> gaps = new ArrayList<>();
        String expanded = CobolCopybookExpander.expand(cobolSource, sourceRoot, gaps);
        ParsedProgram parsed = parse(expanded);
        parsed.gaps.addAll(0, gaps);
        String className = "Translated" + (parsed.programId != null
                ? parsed.programId.replace('-', '_') : "Program");
        String java = emitJava(className, parsed);
        return new Result(className, className + ".java", java, parsed.programId,
                parsed.fields.size(), parsed.paragraphs.size(),
                List.copyOf(parsed.gaps));
    }

    /** Outcome of a COBOL→Java translation. */
    public record Result(
            String className,
            String relativeJavaPath,
            String javaSource,
            String programId,
            int fieldCount,
            int paragraphCount,
            List<String> unsupportedGaps) {
        public Result {
            unsupportedGaps = unsupportedGaps == null ? List.of() : List.copyOf(unsupportedGaps);
        }

        public boolean isTransformative() {
            return javaSource != null
                    && javaSource.contains("public class " + className)
                    && javaSource.contains("public static void main")
                    && (javaSource.contains("System.out.println")
                    || javaSource.contains(" = ")
                    || paragraphCount > 0);
        }
    }

    // ── Parse ────────────────────────────────────────────────────────────

    private static final class Field {
        final String cobolName;
        final String javaName;
        final String javaType;
        final String initExpr;
        final boolean level88;
        final int occurs;
        final String redefines;
        final String picComment;
        /** When REDEFINES shares storage with another field of compatible type. */
        final String aliasOfJavaName;

        Field(String cobolName, String javaName, String javaType, String initExpr,
              boolean level88, int occurs, String redefines, String picComment,
              String aliasOfJavaName) {
            this.cobolName = cobolName;
            this.javaName = javaName;
            this.javaType = javaType;
            this.initExpr = initExpr;
            this.level88 = level88;
            this.occurs = occurs;
            this.redefines = redefines;
            this.picComment = picComment;
            this.aliasOfJavaName = aliasOfJavaName;
        }

        boolean isArray() {
            return occurs > 0;
        }
    }

    private static final class Paragraph {
        final String name;
        final List<String> statements = new ArrayList<>();

        Paragraph(String name) {
            this.name = name;
        }
    }

    private static final class ParsedProgram {
        String programId;
        final Map<String, Field> fields = new LinkedHashMap<>();
        final List<Paragraph> paragraphs = new ArrayList<>();
        final List<String> gaps = new ArrayList<>();
        boolean needsBigDecimal;
    }

    private static ParsedProgram parse(String source) {
        boolean fixed = isFixedFormat(source);
        String[] lines = source.split("\n", -1);
        ParsedProgram out = new ParsedProgram();
        String division = null;
        Paragraph current = null;
        List<String> pendingBlock = null;
        boolean pendingIsEvaluate = false;
        int ifDepth = 0;

        for (String raw : lines) {
            if (raw.length() > INDICATOR_COL && fixed) {
                char ind = raw.charAt(INDICATOR_COL);
                if (ind == '*' || ind == '/') continue;
            }
            String code = fixed ? programAreaOf(raw) : raw;
            String trimmed = code.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("*>") || trimmed.startsWith("*>>")) continue;

            Matcher pid = PROGRAM_ID.matcher(trimmed);
            if (pid.find()) {
                out.programId = pid.group(1).toUpperCase(Locale.ROOT);
                continue;
            }

            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (upper.endsWith("DIVISION.") || upper.endsWith("DIVISION")) {
                division = upper.replace(".", "").trim();
                continue;
            }
            if (upper.contains("WORKING-STORAGE SECTION")
                    || upper.contains("LINKAGE SECTION")
                    || upper.contains("LOCAL-STORAGE SECTION")
                    || upper.contains("FILE SECTION")) {
                division = "DATA DIVISION";
                continue;
            }
            if (upper.startsWith("PROCEDURE DIVISION")) {
                division = "PROCEDURE DIVISION";
                continue;
            }

            if (division != null && division.startsWith("DATA")) {
                parseDataLine(trimmed, out);
                continue;
            }

            if (division != null && division.startsWith("PROCEDURE")) {
                Matcher sm = SECTION_HEADER.matcher(trimmed);
                if (sm.matches()) {
                    if (current != null && pendingBlock != null) {
                        current.statements.addAll(pendingIsEvaluate
                                ? flushEvaluateBlock(pendingBlock) : flushIfBlock(pendingBlock));
                        pendingBlock = null;
                        pendingIsEvaluate = false;
                        ifDepth = 0;
                    }
                    // Phase 1: SECTION headers are paragraph entry points.
                    current = new Paragraph(sm.group(1).toUpperCase(Locale.ROOT));
                    out.paragraphs.add(current);
                    continue;
                }
                Matcher pm = PARAGRAPH_HEADER.matcher(trimmed);
                if (pm.matches()) {
                    if (current != null && pendingBlock != null) {
                        current.statements.addAll(pendingIsEvaluate
                                ? flushEvaluateBlock(pendingBlock) : flushIfBlock(pendingBlock));
                        pendingBlock = null;
                        pendingIsEvaluate = false;
                        ifDepth = 0;
                    }
                    current = new Paragraph(pm.group(1).toUpperCase(Locale.ROOT));
                    out.paragraphs.add(current);
                    continue;
                }
                if (current == null) {
                    current = new Paragraph("MAIN");
                    out.paragraphs.add(current);
                }

                if (pendingBlock != null) {
                    pendingBlock.add(trimmed);
                    String u = trimmed.toUpperCase(Locale.ROOT);
                    if (pendingIsEvaluate) {
                        if (u.contains("END-EVALUATE")) {
                            current.statements.addAll(flushEvaluateBlock(pendingBlock));
                            pendingBlock = null;
                            pendingIsEvaluate = false;
                            ifDepth = 0;
                        }
                    } else {
                        if (u.startsWith("IF ")) ifDepth++;
                        if (u.contains("END-IF") || (u.endsWith(".") && ifDepth <= 1
                                && !u.startsWith("IF ") && !u.startsWith("ELSE")
                                && !u.equals("ELSE") && !u.startsWith("NEXT "))) {
                            current.statements.addAll(flushIfBlock(pendingBlock));
                            pendingBlock = null;
                            ifDepth = 0;
                        }
                    }
                    continue;
                }

                if (upper.startsWith("IF ")) {
                    pendingBlock = new ArrayList<>();
                    pendingBlock.add(trimmed);
                    pendingIsEvaluate = false;
                    ifDepth = 1;
                    if (upper.contains("END-IF") || (countWords(upper) > 3 && upper.endsWith(".")
                            && !upper.contains(" ELSE "))) {
                        current.statements.addAll(flushIfBlock(pendingBlock));
                        pendingBlock = null;
                        ifDepth = 0;
                    }
                    continue;
                }
                if (upper.startsWith("EVALUATE ")) {
                    pendingBlock = new ArrayList<>();
                    pendingBlock.add(trimmed);
                    pendingIsEvaluate = true;
                    continue;
                }

                current.statements.add(trimmed);
            }
        }
        if (current != null && pendingBlock != null) {
            current.statements.addAll(pendingIsEvaluate
                    ? flushEvaluateBlock(pendingBlock) : flushIfBlock(pendingBlock));
        }
        if (out.paragraphs.isEmpty()) {
            out.paragraphs.add(new Paragraph("MAIN"));
        }
        return out;
    }

    private static void parseDataLine(String trimmed, ParsedProgram out) {
        Matcher l88 = LEVEL_88.matcher(trimmed);
        if (l88.matches()) {
            String name = l88.group(1).toUpperCase(Locale.ROOT);
            out.fields.put(name, new Field(name, toJavaIdent(name), "boolean", "false",
                    true, 0, null, null, null));
            return;
        }
        Matcher dm = DATA_ITEM_HEAD.matcher(trimmed);
        if (!dm.find()) return;
        int level = Integer.parseInt(dm.group(1));
        if (level == 88) return;
        String name = dm.group(2).toUpperCase(Locale.ROOT);
        String rest = dm.group(3) == null ? "" : dm.group(3).trim();
        if (rest.endsWith(".")) rest = rest.substring(0, rest.length() - 1).trim();

        String redefines = extractClause(rest, "(?i)\\bREDEFINES\\s+([A-Z0-9][A-Z0-9-]*)");
        Integer occurs = extractIntClause(rest, "(?i)\\bOCCURS\\s+(\\d+)(?:\\s+TIMES)?");
        String pic = extractClause(rest, "(?i)\\bPIC(?:TURE)?\\s+(\\S+)");
        String usage = extractUsage(rest);
        String value = extractValue(rest);

        // Group items without PIC / VALUE / OCCURS become containers only.
        if (pic == null && value == null && occurs == null && redefines == null) return;

        String javaType = picToJavaType(pic, usage);
        if ("java.math.BigDecimal".equals(javaType)) {
            out.needsBigDecimal = true;
        }
        int occursN = occurs == null ? 0 : occurs;
        String init = valueToJava(value, javaType, occursN);
        String picComment = buildPicComment(pic, usage, occursN);

        String aliasOf = null;
        if (redefines != null) {
            Field target = out.fields.get(redefines.toUpperCase(Locale.ROOT));
            if (target == null) {
                out.gaps.add("REDEFINES target missing: " + name + " REDEFINES " + redefines);
            } else if (!compatibleRedefines(target.javaType, javaType, target.occurs, occursN)) {
                out.gaps.add("REDEFINES type conflict: " + name + " (" + javaType
                        + ") REDEFINES " + redefines + " (" + target.javaType + ")");
            } else {
                // Share storage: map to same Java field; emit alias comment only.
                aliasOf = target.aliasOfJavaName != null ? target.aliasOfJavaName : target.javaName;
                javaType = target.javaType;
                init = target.initExpr;
                occursN = target.occurs;
            }
        }

        String javaName = aliasOf != null ? aliasOf : toJavaIdent(name);
        out.fields.put(name, new Field(name, javaName, javaType, init, false,
                occursN, redefines, picComment, aliasOf));
    }

    private static boolean compatibleRedefines(String a, String b, int occursA, int occursB) {
        if (occursA != occursB) return false;
        if (Objects.equals(a, b)) return true;
        // Allow numeric widening aliases only when both numeric primitives.
        return isNumericPrimitive(a) && isNumericPrimitive(b);
    }

    private static boolean isNumericPrimitive(String t) {
        return "int".equals(t) || "long".equals(t) || "double".equals(t);
    }

    private static String extractClause(String rest, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(rest);
        return m.find() ? m.group(1) : null;
    }

    private static Integer extractIntClause(String rest, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(rest);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private static String extractUsage(String rest) {
        Matcher m = Pattern.compile(
                "(?i)\\bUSAGE\\s+(IS\\s+)?(COMP(?:-[0-9]+)?|BINARY|PACKED-DECIMAL|DISPLAY)\\b")
                .matcher(rest);
        if (m.find()) return m.group(2).toUpperCase(Locale.ROOT);
        // Bare USAGE keywords after PIC (common shorthand).
        Matcher bare = Pattern.compile(
                "(?i)\\b(COMP-[0-9]+|COMP|BINARY|PACKED-DECIMAL)\\b").matcher(rest);
        if (bare.find()) return bare.group(1).toUpperCase(Locale.ROOT);
        return null;
    }

    private static String extractValue(String rest) {
        Matcher m = Pattern.compile(
                "(?i)\\bVALUE\\s+(IS\\s+)?(\"[^\"]*\"|'[^']*'|\\S+)").matcher(rest);
        if (!m.find()) return null;
        String value = m.group(2);
        if (value != null && value.endsWith(".") && !value.startsWith("\"")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String buildPicComment(String pic, String usage, int occurs) {
        StringBuilder sb = new StringBuilder();
        if (pic != null) sb.append("PIC ").append(pic);
        if (usage != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("USAGE ").append(usage);
        }
        if (occurs > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("OCCURS ").append(occurs);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static int countWords(String s) {
        return s.trim().isEmpty() ? 0 : s.trim().split("\\s+").length;
    }

    private static List<String> flushIfBlock(List<String> lines) {
        return List.of("@@IF@@" + String.join("\n", lines));
    }

    private static List<String> flushEvaluateBlock(List<String> lines) {
        return List.of("@@EVAL@@" + String.join("\n", lines));
    }

    // ── Emit ─────────────────────────────────────────────────────────────

    private static String emitJava(String className, ParsedProgram parsed) {
        StringBuilder sb = new StringBuilder();
        sb.append("/**\n");
        sb.append(" * Generated by ShadowStack CobolToJavaTranslator (semantic rehost MVP).\n");
        sb.append(" * Source PROGRAM-ID: ")
                .append(parsed.programId != null ? parsed.programId : "UNKNOWN")
                .append("\n");
        sb.append(" * Not full Blu Age — CICS/IMS/JCL out of scope.\n");
        sb.append(" */\n");
        if (parsed.needsBigDecimal) {
            sb.append("import java.math.BigDecimal;\n\n");
        }
        sb.append("public class ").append(className).append(" {\n\n");

        // Emit unique Java fields (REDEFINES aliases share one declaration).
        Map<String, Field> emitted = new LinkedHashMap<>();
        for (Field f : parsed.fields.values()) {
            if (f.aliasOfJavaName != null) {
                sb.append("    // REDEFINES alias: ").append(f.cobolName)
                        .append(" → ").append(f.aliasOfJavaName);
                if (f.picComment != null) sb.append(" (").append(f.picComment).append(')');
                sb.append('\n');
                continue;
            }
            if (emitted.containsKey(f.javaName)) continue;
            emitted.put(f.javaName, f);
            if (f.picComment != null) {
                sb.append("    // ").append(f.picComment).append('\n');
            }
            String type = f.isArray() ? f.javaType + "[]" : f.javaType;
            String typeEmit = type.replace("java.math.BigDecimal",
                    parsed.needsBigDecimal ? "BigDecimal" : "java.math.BigDecimal");
            String init = f.initExpr;
            if (parsed.needsBigDecimal) {
                init = init.replace("java.math.BigDecimal", "BigDecimal");
            }
            sb.append("    private static ").append(typeEmit).append(' ')
                    .append(f.javaName).append(" = ").append(init).append(";\n");
        }
        if (!parsed.fields.isEmpty()) sb.append('\n');

        String entry = parsed.paragraphs.get(0).name;
        sb.append("    public static void main(String[] args) {\n");
        sb.append("        ").append(toCamel(entry)).append("();\n");
        sb.append("    }\n\n");

        for (Paragraph p : parsed.paragraphs) {
            sb.append("    public static void ").append(toCamel(p.name)).append("() {\n");
            boolean returned = false;
            for (String stmt : p.statements) {
                if (returned) break;
                for (String javaLine : translateStatement(stmt, parsed)) {
                    sb.append("        ").append(javaLine).append('\n');
                    String trimmed = javaLine.trim();
                    if (trimmed.equals("return;") || trimmed.endsWith(" return;")) {
                        returned = true;
                    }
                }
            }
            sb.append("    }\n\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    private static List<String> translateStatement(String stmt, ParsedProgram parsed) {
        if (stmt.startsWith("@@IF@@")) {
            return translateIf(stmt.substring(6), parsed);
        }
        if (stmt.startsWith("@@EVAL@@")) {
            return translateEvaluate(stmt.substring(8), parsed);
        }

        String s = stripPeriod(stmt.trim());
        String upper = s.toUpperCase(Locale.ROOT);

        if (upper.startsWith("DISPLAY ")) {
            return List.of(translateDisplay(s) + ";");
        }
        if (upper.startsWith("MOVE ")) {
            return List.of(translateMove(s, parsed) + ";");
        }
        if (upper.startsWith("COMPUTE ")) {
            return List.of(translateCompute(s) + ";");
        }

        List<String> perform = translatePerform(s, parsed);
        if (perform != null) return perform;

        if (upper.matches("(?i)ADD\\s+.+\\s+TO\\s+.+")) {
            Matcher m = Pattern.compile("(?i)ADD\\s+(.+?)\\s+TO\\s+(.+)").matcher(s);
            if (m.find()) {
                return List.of(lhs(m.group(2).trim(), parsed) + " += "
                        + exprOperand(m.group(1).trim()) + ";");
            }
        }
        if (upper.matches("(?i)SUBTRACT\\s+.+\\s+FROM\\s+.+")) {
            Matcher m = Pattern.compile("(?i)SUBTRACT\\s+(.+?)\\s+FROM\\s+(.+)").matcher(s);
            if (m.find()) {
                return List.of(lhs(m.group(2).trim(), parsed) + " -= "
                        + exprOperand(m.group(1).trim()) + ";");
            }
        }
        if (upper.matches("(?i)MULTIPLY\\s+.+\\s+BY\\s+.+")) {
            Matcher m = Pattern.compile("(?i)MULTIPLY\\s+(.+?)\\s+BY\\s+(.+)").matcher(s);
            if (m.find()) {
                return List.of(lhs(m.group(2).trim(), parsed) + " *= "
                        + exprOperand(m.group(1).trim()) + ";");
            }
        }
        if (upper.matches("(?i)DIVIDE\\s+.+\\s+INTO\\s+.+")) {
            Matcher m = Pattern.compile("(?i)DIVIDE\\s+(.+?)\\s+INTO\\s+(.+)").matcher(s);
            if (m.find()) {
                return List.of(lhs(m.group(2).trim(), parsed) + " /= "
                        + exprOperand(m.group(1).trim()) + ";");
            }
        }
        if (upper.matches("(?i)ACCEPT\\s+[A-Z0-9-]+(?:\\s*\\([^)]*\\))?")) {
            Matcher m = Pattern.compile("(?i)ACCEPT\\s+(.+)").matcher(s);
            if (m.find()) {
                String target = m.group(1).trim();
                Field f = lookupField(target, parsed);
                String cast = f != null && !"String".equals(elementType(f))
                        ? castRead(elementType(f)) : "new java.util.Scanner(System.in).nextLine()";
                return List.of(lhs(target, parsed) + " = " + cast + ";");
            }
        }
        if (upper.matches("(?i)SET\\s+[A-Z0-9-]+\\s+TO\\s+TRUE")) {
            Matcher m = Pattern.compile("(?i)SET\\s+([A-Z0-9-]+)\\s+TO\\s+TRUE").matcher(s);
            if (m.find()) return List.of(toJavaIdent(m.group(1)) + " = true;");
        }
        if (upper.matches("(?i)INITIALIZE\\s+.+")) {
            Matcher m = Pattern.compile("(?i)INITIALIZE\\s+(.+)").matcher(s);
            if (m.find()) {
                String target = m.group(1).trim();
                Field f = lookupField(target, parsed);
                String def = defaultInit(f == null ? "String" : elementType(f));
                return List.of(lhs(target, parsed) + " = " + def + ";");
            }
        }
        if (upper.equals("STOP RUN") || upper.equals("GOBACK") || upper.equals("EXIT PROGRAM")) {
            return List.of("return;");
        }
        if (upper.equals("CONTINUE") || upper.equals("NEXT SENTENCE")) {
            return List.of("// " + upper.toLowerCase(Locale.ROOT));
        }
        if (upper.startsWith("GO TO ")) {
            Matcher m = Pattern.compile("(?i)GO\\s+TO\\s+([A-Z0-9-]+)").matcher(s);
            if (m.find()) return List.of(toCamel(m.group(1)) + "(); return;");
        }
        if (upper.startsWith("STRING ")) {
            return List.of(translateStringInto(s) + ";");
        }

        // Unsupported — emit comment so class still compiles; record gap.
        String gap = unsupportedGapLabel(upper, s);
        if (!parsed.gaps.contains(gap)) {
            parsed.gaps.add(gap);
        }
        return List.of("// COBOL: " + s);
    }

    private static String unsupportedGapLabel(String upper, String s) {
        if (upper.startsWith("OPEN ")) return "unsupported verb: OPEN";
        if (upper.startsWith("READ ")) return "unsupported verb: READ";
        if (upper.startsWith("WRITE ")) return "unsupported verb: WRITE";
        if (upper.startsWith("REWRITE ")) return "unsupported verb: REWRITE";
        if (upper.startsWith("DELETE ")) return "unsupported verb: DELETE";
        if (upper.startsWith("CLOSE ")) return "unsupported verb: CLOSE";
        if (upper.startsWith("CALL ")) return "unsupported verb: CALL";
        if (upper.startsWith("SORT ")) return "unsupported verb: SORT";
        if (upper.startsWith("MERGE ")) return "unsupported verb: MERGE";
        if (upper.startsWith("SEARCH ")) return "unsupported verb: SEARCH";
        if (upper.startsWith("EXEC ")) return "unsupported verb: EXEC";
        if (upper.startsWith("START ")) return "unsupported verb: START";
        if (upper.startsWith("RELEASE ")) return "unsupported verb: RELEASE";
        if (upper.startsWith("RETURN ")) return "unsupported verb: RETURN";
        String verb = s.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        return "unsupported verb: " + verb;
    }

    private static List<String> translatePerform(String s, ParsedProgram parsed) {
        Matcher varying = PERFORM_VARYING.matcher(s);
        if (varying.matches()) {
            String para = varying.group(1);
            String idx = toJavaIdent(varying.group(2));
            String from = exprOperand(varying.group(3));
            String by = exprOperand(varying.group(4));
            String cond = rewriteCondition(varying.group(5).trim());
            List<String> out = new ArrayList<>();
            out.add("for (" + idx + " = " + from + "; !(" + cond + "); " + idx + " += " + by + ") {");
            if (para != null) {
                out.add("    " + toCamel(para) + "();");
            }
            out.add("}");
            return out;
        }
        Matcher until = PERFORM_UNTIL.matcher(s);
        if (until.matches()) {
            String para = until.group(1);
            String cond = rewriteCondition(until.group(2).trim());
            List<String> out = new ArrayList<>();
            out.add("while (!(" + cond + ")) {");
            if (para != null) {
                out.add("    " + toCamel(para) + "();");
            }
            out.add("}");
            return out;
        }
        Matcher times = PERFORM_TIMES.matcher(s);
        if (times.matches()) {
            String para = times.group(1);
            String n = exprOperand(times.group(2));
            List<String> out = new ArrayList<>();
            out.add("for (int _ssTimes = 0; _ssTimes < " + n + "; _ssTimes++) {");
            if (para != null) {
                out.add("    " + toCamel(para) + "();");
            }
            out.add("}");
            return out;
        }
        Matcher thru = PERFORM_THRU.matcher(s);
        if (thru.matches()) {
            String start = thru.group(1);
            String end = thru.group(2);
            String gap = "PERFORM THRU partial: " + start + " THRU " + end
                    + " (calls start only; full paragraph graph not expanded)";
            if (!parsed.gaps.contains(gap)) parsed.gaps.add(gap);
            return List.of(
                    toCamel(start) + "();",
                    "// COBOL gap: PERFORM " + start + " THRU " + end);
        }
        Matcher simple = PERFORM_SIMPLE.matcher(s);
        if (simple.matches()) {
            return List.of(toCamel(simple.group(1)) + "();");
        }
        return null;
    }

    private static String castRead(String javaType) {
        return switch (javaType) {
            case "int" -> "Integer.parseInt(new java.util.Scanner(System.in).nextLine())";
            case "long" -> "Long.parseLong(new java.util.Scanner(System.in).nextLine())";
            case "double" -> "Double.parseDouble(new java.util.Scanner(System.in).nextLine())";
            case "boolean" -> "Boolean.parseBoolean(new java.util.Scanner(System.in).nextLine())";
            case "java.math.BigDecimal", "BigDecimal" ->
                    "new java.math.BigDecimal(new java.util.Scanner(System.in).nextLine())";
            default -> "new java.util.Scanner(System.in).nextLine()";
        };
    }

    private static String translateDisplay(String s) {
        String rest = s.substring("DISPLAY".length()).trim();
        List<String> parts = splitCobolOperands(rest);
        if (parts.isEmpty()) return "System.out.println(\"\")";
        if (parts.size() == 1) return "System.out.println(" + exprOperand(parts.get(0)) + ")";
        StringBuilder expr = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) expr.append(" + ");
            expr.append(exprOperand(parts.get(i)));
        }
        return "System.out.println(" + expr + ")";
    }

    private static String translateMove(String s, ParsedProgram parsed) {
        Matcher m = Pattern.compile("(?i)MOVE\\s+(.+?)\\s+TO\\s+(.+)").matcher(s);
        if (!m.find()) return "// MOVE (unparsed)";
        return lhs(m.group(2).trim(), parsed) + " = " + exprOperand(m.group(1).trim());
    }

    private static String translateCompute(String s) {
        Matcher m = Pattern.compile("(?i)COMPUTE\\s+(.+?)\\s*=\\s*(.+)").matcher(s);
        if (!m.find()) return "// COMPUTE (unparsed)";
        return rewriteExpr(m.group(1).trim()) + " = " + rewriteExpr(m.group(2).trim());
    }

    private static String translateStringInto(String s) {
        Matcher m = Pattern.compile("(?i)STRING\\s+(.+?)\\s+INTO\\s+([A-Z0-9-]+)").matcher(s);
        if (!m.find()) return "// STRING (unparsed)";
        String parts = m.group(1).replaceAll("(?i)\\s+DELIMITED\\s+BY\\s+\\S+", "");
        List<String> ops = splitCobolOperands(parts.trim());
        StringBuilder expr = new StringBuilder();
        for (String op : ops) {
            if (expr.length() > 0) expr.append(" + ");
            expr.append(exprOperand(op));
        }
        if (expr.length() == 0) expr.append("\"\"");
        return toJavaIdent(m.group(2)) + " = " + expr;
    }

    private static List<String> translateIf(String block, ParsedProgram parsed) {
        String[] lines = block.split("\n");
        List<String> out = new ArrayList<>();
        String cond = null;
        List<String> thenBody = new ArrayList<>();
        List<String> elseBody = new ArrayList<>();
        boolean inElse = false;

        for (String line : lines) {
            String t = stripPeriod(line.trim());
            String u = t.toUpperCase(Locale.ROOT);
            if (u.startsWith("IF ")) {
                cond = rewriteCondition(t.substring(3).trim());
                continue;
            }
            if (u.equals("ELSE") || u.startsWith("ELSE ")) {
                inElse = true;
                if (u.startsWith("ELSE ") && u.length() > 5) {
                    elseBody.addAll(translateStatement(t.substring(5).trim(), parsed));
                }
                continue;
            }
            if (u.startsWith("END-IF")) continue;
            List<String> translated = translateStatement(t, parsed);
            if (inElse) elseBody.addAll(translated);
            else thenBody.addAll(translated);
        }

        if (cond == null) cond = "true";
        out.add("if (" + cond + ") {");
        for (String l : thenBody) out.add("    " + l);
        if (!elseBody.isEmpty()) {
            out.add("} else {");
            for (String l : elseBody) out.add("    " + l);
        }
        out.add("}");
        return out;
    }

    private static List<String> translateEvaluate(String block, ParsedProgram parsed) {
        String[] lines = block.split("\n");
        List<String> out = new ArrayList<>();
        boolean first = true;
        List<String> currentBody = null;
        String currentCond = null;

        for (String line : lines) {
            String t = stripPeriod(line.trim());
            String u = t.toUpperCase(Locale.ROOT);
            if (u.startsWith("EVALUATE ")) continue;
            if (u.startsWith("END-EVALUATE")) continue;
            if (u.startsWith("WHEN OTHER")) {
                if (currentBody != null) {
                    flushWhen(out, first, currentCond, currentBody);
                    first = false;
                }
                currentCond = null;
                currentBody = new ArrayList<>();
                continue;
            }
            if (u.startsWith("WHEN ")) {
                if (currentBody != null) {
                    flushWhen(out, first, currentCond, currentBody);
                    first = false;
                }
                currentCond = rewriteCondition(t.substring(5).trim());
                currentBody = new ArrayList<>();
                continue;
            }
            if (currentBody != null) {
                currentBody.addAll(translateStatement(t, parsed));
            }
        }
        flushWhen(out, first, currentCond, currentBody);
        if (!out.isEmpty() && !out.get(out.size() - 1).equals("}")) {
            out.add("}");
        }
        return out;
    }

    private static void flushWhen(List<String> out, boolean first, String cond, List<String> body) {
        if (body == null) return;
        if (first) {
            if (cond != null) out.add("if (" + cond + ") {");
            else out.add("if (true) {");
        } else if (cond != null) {
            out.add("} else if (" + cond + ") {");
        } else {
            out.add("} else {");
        }
        for (String l : body) out.add("    " + l);
    }

    private static String rewriteCondition(String cond) {
        String c = cond.trim();
        c = c.replaceAll("(?i)\\s+EQUAL\\s+TO\\s+", " == ");
        c = c.replaceAll("(?i)\\s+EQUALS\\s+", " == ");
        c = c.replaceAll("(?i)\\s+NOT\\s*=\\s*", " != ");
        c = c.replaceAll("(?i)\\s+GREATER\\s+THAN\\s+", " > ");
        c = c.replaceAll("(?i)\\s+LESS\\s+THAN\\s+", " < ");
        c = c.replaceAll("(?i)\\s+NOT\\s+GREATER\\s+THAN\\s+", " <= ");
        c = c.replaceAll("(?i)\\s+NOT\\s+LESS\\s+THAN\\s+", " >= ");
        c = c.replaceAll("(?i)\\s*>\\s*", " > ");
        c = c.replaceAll("(?i)\\s*<\\s*", " < ");
        c = c.replaceAll("(?i)\\s*=\\s*", " == ");
        c = c.replaceAll("(?i)\\s+AND\\s+", " && ");
        c = c.replaceAll("(?i)\\s+OR\\s+", " || ");
        c = c.replace("====", "==").replace("===", "==");
        return rewriteExpr(c);
    }

    private static String rewriteExpr(String expr) {
        // First rewrite COBOL subscripts NAME(I) → NAME[I-1]
        String withSubs = rewriteSubscripts(expr);
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile(
                "\"[^\"]*\"|'[^']*'|[A-Za-z][A-Za-z0-9_-]*|\\d+(?:\\.\\d+)?|[^A-Za-z0-9\"']+")
                .matcher(withSubs);
        while (m.find()) {
            String tok = m.group();
            if ((tok.startsWith("\"") && tok.endsWith("\""))
                    || (tok.startsWith("'") && tok.endsWith("'"))) {
                out.append('"').append(tok.substring(1, tok.length() - 1)).append('"');
            } else if (tok.matches("[A-Za-z][A-Za-z0-9_-]*")) {
                out.append(toJavaIdent(tok));
            } else {
                out.append(tok);
            }
        }
        return out.toString();
    }

    private static String rewriteSubscripts(String expr) {
        Matcher m = SUBSCRIPT.matcher(expr);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = toJavaIdent(m.group(1));
            String idx = m.group(2);
            String idxExpr = idx.matches("\\d+")
                    ? String.valueOf(Integer.parseInt(idx) - 1)
                    : (toJavaIdent(idx) + " - 1");
            m.appendReplacement(sb, Matcher.quoteReplacement(name + "[" + idxExpr + "]"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String exprOperand(String tok) {
        tok = tok.trim();
        if ((tok.startsWith("\"") && tok.endsWith("\""))
                || (tok.startsWith("'") && tok.endsWith("'"))) {
            return "\"" + tok.substring(1, tok.length() - 1) + "\"";
        }
        if (tok.matches("-?\\d+(?:\\.\\d+)?")) return tok;
        if (SUBSCRIPT.matcher(tok).matches()) {
            return rewriteSubscripts(tok);
        }
        return toJavaIdent(tok);
    }

    private static String lhs(String target, ParsedProgram parsed) {
        target = target.trim();
        if (SUBSCRIPT.matcher(target).find()) {
            return rewriteSubscripts(target);
        }
        Field f = parsed.fields.get(target.toUpperCase(Locale.ROOT));
        if (f != null) return f.javaName;
        return toJavaIdent(target);
    }

    private static Field lookupField(String target, ParsedProgram parsed) {
        String name = target.trim();
        Matcher m = SUBSCRIPT.matcher(name);
        if (m.find()) name = m.group(1);
        return parsed.fields.get(name.toUpperCase(Locale.ROOT));
    }

    private static String elementType(Field f) {
        return f.javaType;
    }

    private static List<String> splitCobolOperands(String rest) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        char q = 0;
        int paren = 0;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (inQuote) {
                cur.append(c);
                if (c == q) inQuote = false;
                continue;
            }
            if (c == '"' || c == '\'') {
                inQuote = true;
                q = c;
                cur.append(c);
                continue;
            }
            if (c == '(') {
                paren++;
                cur.append(c);
                continue;
            }
            if (c == ')' && paren > 0) {
                paren--;
                cur.append(c);
                continue;
            }
            if (Character.isWhitespace(c) && paren == 0) {
                if (cur.length() > 0) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) parts.add(cur.toString());
        return parts;
    }

    /**
     * Map PIC (+ optional USAGE) to a Java type.
     * COMP/BINARY → int/long; COMP-3/PACKED-DECIMAL → BigDecimal;
     * edited pics → String; S9 / 9 with V → double; plain 9 → int.
     */
    static String picToJavaType(String pic, String usage) {
        String u = usage == null ? "" : usage.toUpperCase(Locale.ROOT);
        if (u.equals("COMP-3") || u.equals("PACKED-DECIMAL")) {
            return "java.math.BigDecimal";
        }
        boolean binary = u.equals("COMP") || u.equals("COMP-4") || u.equals("COMP-5")
                || u.equals("BINARY");
        if (pic == null) {
            return binary ? "int" : "String";
        }
        String p = pic.toUpperCase(Locale.ROOT);
        // Edited numeric / alphanumeric pictures
        if (p.matches(".*[Z*$,/B+].*") || p.contains("CR") || p.contains("DB")) {
            return "String";
        }
        if (p.contains("X") || p.contains("A")) return "String";

        int digits = countPicDigits(p);
        boolean signed = p.startsWith("S") || p.contains("S9");
        boolean fractional = p.contains("V") || p.contains(".");

        if (binary) {
            if (fractional) return "double";
            return digits > 9 ? "long" : "int";
        }
        if (fractional) return "double";
        if (p.contains("9")) {
            if (digits > 9) return "long";
            return "int";
        }
        if (signed) return "int";
        return "String";
    }

    /** Backward-compatible overload used by older call sites / tests. */
    static String picToJavaType(String pic) {
        return picToJavaType(pic, null);
    }

    private static int countPicDigits(String pic) {
        int count = 0;
        Matcher m = Pattern.compile("9\\((\\d+)\\)|9").matcher(pic);
        while (m.find()) {
            if (m.group(1) != null) count += Integer.parseInt(m.group(1));
            else count += 1;
        }
        return count;
    }

    private static String valueToJava(String value, String javaType, int occurs) {
        if (occurs > 0) {
            String elemInit = valueToJava(value, javaType, 0);
            if ("int".equals(javaType) || "long".equals(javaType) || "double".equals(javaType)
                    || "boolean".equals(javaType)) {
                // Primitive arrays get language default zeros/false when VALUE absent.
                if (value == null) return "new " + javaType + "[" + occurs + "]";
            }
            if ("java.math.BigDecimal".equals(javaType)) {
                if (value == null) {
                    return "new java.math.BigDecimal[" + occurs + "]";
                }
            }
            // Non-null VALUE on OCCURS: allocate then rely on first-element assign elsewhere;
            // emit filled array literal only for simple scalar repeats.
            if (value != null && ("int".equals(javaType) || "long".equals(javaType)
                    || "double".equals(javaType))) {
                StringBuilder sb = new StringBuilder("new " + javaType + "[] {");
                for (int i = 0; i < occurs; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(elemInit);
                }
                sb.append('}');
                return sb.toString();
            }
            if (value != null && "String".equals(javaType)) {
                StringBuilder sb = new StringBuilder("new String[] {");
                for (int i = 0; i < occurs; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(elemInit);
                }
                sb.append('}');
                return sb.toString();
            }
            return "new " + javaType + "[" + occurs + "]";
        }
        return valueToJavaScalar(value, javaType);
    }

    private static String valueToJavaScalar(String value, String javaType) {
        if (value == null) {
            return defaultInit(javaType);
        }
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            return "\"" + value.substring(1, value.length() - 1) + "\"";
        }
        if ("boolean".equals(javaType)) return "false";
        if ("String".equals(javaType)) return "\"" + value + "\"";
        if ("java.math.BigDecimal".equals(javaType) || "BigDecimal".equals(javaType)) {
            String lit = value.startsWith(".") ? "0" + value : value;
            return "new java.math.BigDecimal(\"" + lit + "\")";
        }
        if (value.startsWith(".")) return "0" + value;
        return value;
    }

    private static String defaultInit(String javaType) {
        return switch (javaType) {
            case "int", "long" -> "0";
            case "double" -> "0.0";
            case "boolean" -> "false";
            case "java.math.BigDecimal", "BigDecimal" -> "java.math.BigDecimal.ZERO";
            default -> "\"\"";
        };
    }

    private static String stripPeriod(String s) {
        String t = s.trim();
        if (t.endsWith(".")) return t.substring(0, t.length() - 1).trim();
        return t;
    }

    static String toJavaIdent(String cobolName) {
        if (cobolName == null) return "value";
        if (cobolName.chars().allMatch(c -> Character.isDigit(c) || c == '.')) return cobolName;
        return cobolName.replace('-', '_');
    }

    static String toCamel(String cobolName) {
        String[] parts = cobolName.toLowerCase(Locale.ROOT).split("-");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            sb.append(Character.toUpperCase(parts[i].charAt(0)));
            if (parts[i].length() > 1) sb.append(parts[i].substring(1));
        }
        return sb.toString();
    }

    static String toPascal(String cobolName) {
        String camel = toCamel(cobolName);
        if (camel.isEmpty()) return "Program";
        return Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
    }

    private static boolean isFixedFormat(String source) {
        String[] lines = source.split("\n", -1);
        int fixedSignals = 0;
        int totalSignals = 0;
        for (String line : lines) {
            if (line.length() < 7 || line.isBlank()) continue;
            totalSignals++;
            char indicator = line.charAt(INDICATOR_COL);
            if (indicator == '*' || indicator == '/' || indicator == '-'
                    || indicator == 'D' || indicator == 'd' || indicator == ' ') {
                String prefix = line.substring(0, 6);
                if (prefix.chars().allMatch(c -> Character.isDigit(c) || c == ' ')) {
                    fixedSignals++;
                }
            }
        }
        return totalSignals > 0 && (double) fixedSignals / totalSignals > 0.6;
    }

    private static String programAreaOf(String line) {
        if (line.length() <= INDICATOR_COL) return "";
        char indicator = line.charAt(INDICATOR_COL);
        if (indicator == '*' || indicator == '/') return "";
        if (line.length() <= PROGRAM_AREA_START) return "";
        int end = Math.min(line.length(), PROGRAM_AREA_END);
        return line.substring(PROGRAM_AREA_START, end);
    }
}
