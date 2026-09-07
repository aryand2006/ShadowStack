package com.shadowstack.adapters.cobol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MVP COBOL→Java semantic rehost translator.
 *
 * <p>Converts a practical subset of WORKING-STORAGE + PROCEDURE DIVISION into a
 * compilable Java class {@code Translated{ProgramId}} with {@code main} and
 * paragraph methods. Aimed toward Blu Age–class rehost progress — not full
 * Blu Age (CICS/IMS/JCL out of scope).</p>
 */
public final class CobolToJavaTranslator {

    private static final int INDICATOR_COL = 6;
    private static final int PROGRAM_AREA_START = 7;
    private static final int PROGRAM_AREA_END = 72;

    private static final Pattern PROGRAM_ID = Pattern.compile(
            "(?i)PROGRAM-ID\\s*\\.?\\s*([A-Z0-9][A-Z0-9-]*)\\s*\\.?");
    private static final Pattern DATA_ITEM = Pattern.compile(
            "(?i)^(\\d{1,2})\\s+([A-Z0-9][A-Z0-9-]*)(?:\\s+PIC(?:TURE)?\\s+(\\S+))?"
                    + "(?:\\s+VALUE\\s+(\"[^\"]*\"|'[^']*'|\\S+))?\\s*\\.?\\s*$");
    private static final Pattern LEVEL_88 = Pattern.compile(
            "(?i)^88\\s+([A-Z0-9][A-Z0-9-]*)\\s+VALUE\\s+(\"[^\"]*\"|'[^']*'|\\S+)\\s*\\.?\\s*$");
    private static final Pattern PARAGRAPH_HEADER = Pattern.compile(
            "(?i)^([A-Z][A-Z0-9-]*)\\s*\\.\\s*$");
    private static final Pattern SECTION_HEADER = Pattern.compile(
            "(?i)^([A-Z][A-Z0-9-]*)\\s+SECTION\\s*\\.\\s*$");

    private CobolToJavaTranslator() {}

    /**
     * Translate COBOL source into a Java class skeleton.
     *
     * @param cobolSource full COBOL program text (fixed or free format)
     * @return translation result with Java source and metadata
     */
    public static Result translate(String cobolSource) {
        Objects.requireNonNull(cobolSource, "cobolSource");
        ParsedProgram parsed = parse(cobolSource);
        // Class name mirrors PROGRAM-ID: TranslatedSAMPLE (valid Java identifier).
        String className = "Translated" + (parsed.programId != null
                ? parsed.programId.replace('-', '_') : "Program");
        String java = emitJava(className, parsed);
        return new Result(className, className + ".java", java, parsed.programId,
                parsed.fields.size(), parsed.paragraphs.size());
    }

    /** Outcome of a COBOL→Java translation. */
    public record Result(
            String className,
            String relativeJavaPath,
            String javaSource,
            String programId,
            int fieldCount,
            int paragraphCount) {
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

        Field(String cobolName, String javaName, String javaType, String initExpr, boolean level88) {
            this.cobolName = cobolName;
            this.javaName = javaName;
            this.javaType = javaType;
            this.initExpr = initExpr;
            this.level88 = level88;
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
            if (trimmed.startsWith("*>")) continue;

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
            if (upper.contains("WORKING-STORAGE SECTION")) {
                division = "DATA DIVISION";
                continue;
            }
            if (upper.startsWith("PROCEDURE DIVISION")) {
                division = "PROCEDURE DIVISION";
                continue;
            }

            if (division != null && division.startsWith("DATA")) {
                Matcher l88 = LEVEL_88.matcher(trimmed);
                if (l88.matches()) {
                    String name = l88.group(1).toUpperCase(Locale.ROOT);
                    out.fields.put(name, new Field(name, toJavaIdent(name), "boolean", "false", true));
                    continue;
                }
                Matcher dm = DATA_ITEM.matcher(trimmed);
                if (dm.find()) {
                    int level = Integer.parseInt(dm.group(1));
                    if (level == 88) continue;
                    String name = dm.group(2).toUpperCase(Locale.ROOT);
                    String pic = dm.group(3);
                    String value = dm.group(4);
                    // Statement-terminating period often glues to unquoted VALUE tokens.
                    if (value != null && value.endsWith(".") && !value.startsWith("\"")) {
                        value = value.substring(0, value.length() - 1);
                    }
                    // Group items without PIC become containers only — skip field emit
                    // unless VALUE present; elementary PIC items become fields.
                    if (pic == null && value == null) continue;
                    String javaType = picToJavaType(pic);
                    String init = valueToJava(value, javaType);
                    out.fields.put(name, new Field(name, toJavaIdent(name), javaType, init, false));
                }
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
                    current = null;
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

    private static int countWords(String s) {
        return s.trim().isEmpty() ? 0 : s.trim().split("\\s+").length;
    }

    private static List<String> flushIfBlock(List<String> lines) {
        // Represent as a synthetic pseudo-statement consumed by translateStatement
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
        sb.append("public class ").append(className).append(" {\n\n");

        for (Field f : parsed.fields.values()) {
            sb.append("    private static ").append(f.javaType).append(' ')
                    .append(f.javaName).append(" = ").append(f.initExpr).append(";\n");
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
            return List.of(translateMove(s) + ";");
        }
        if (upper.startsWith("COMPUTE ")) {
            return List.of(translateCompute(s) + ";");
        }
        if (upper.matches("(?i)PERFORM\\s+[A-Z0-9-]+")) {
            Matcher m = Pattern.compile("(?i)PERFORM\\s+([A-Z0-9-]+)").matcher(s);
            if (m.find()) return List.of(toCamel(m.group(1)) + "();");
        }
        if (upper.matches("(?i)ADD\\s+.+\\s+TO\\s+[A-Z0-9-]+")) {
            Matcher m = Pattern.compile("(?i)ADD\\s+(.+?)\\s+TO\\s+([A-Z0-9-]+)").matcher(s);
            if (m.find()) {
                return List.of(toJavaIdent(m.group(2)) + " += " + exprOperand(m.group(1).trim()) + ";");
            }
        }
        if (upper.matches("(?i)SUBTRACT\\s+.+\\s+FROM\\s+[A-Z0-9-]+")) {
            Matcher m = Pattern.compile("(?i)SUBTRACT\\s+(.+?)\\s+FROM\\s+([A-Z0-9-]+)").matcher(s);
            if (m.find()) {
                return List.of(toJavaIdent(m.group(2)) + " -= " + exprOperand(m.group(1).trim()) + ";");
            }
        }
        if (upper.matches("(?i)MULTIPLY\\s+.+\\s+BY\\s+[A-Z0-9-]+")) {
            Matcher m = Pattern.compile("(?i)MULTIPLY\\s+(.+?)\\s+BY\\s+([A-Z0-9-]+)").matcher(s);
            if (m.find()) {
                return List.of(toJavaIdent(m.group(2)) + " *= " + exprOperand(m.group(1).trim()) + ";");
            }
        }
        if (upper.matches("(?i)DIVIDE\\s+.+\\s+INTO\\s+[A-Z0-9-]+")) {
            Matcher m = Pattern.compile("(?i)DIVIDE\\s+(.+?)\\s+INTO\\s+([A-Z0-9-]+)").matcher(s);
            if (m.find()) {
                return List.of(toJavaIdent(m.group(2)) + " /= " + exprOperand(m.group(1).trim()) + ";");
            }
        }
        if (upper.matches("(?i)ACCEPT\\s+[A-Z0-9-]+")) {
            Matcher m = Pattern.compile("(?i)ACCEPT\\s+([A-Z0-9-]+)").matcher(s);
            if (m.find()) {
                Field f = parsed.fields.get(m.group(1).toUpperCase(Locale.ROOT));
                String cast = f != null && !"String".equals(f.javaType)
                        ? castRead(f.javaType) : "new java.util.Scanner(System.in).nextLine()";
                return List.of(toJavaIdent(m.group(1)) + " = " + cast + ";");
            }
        }
        if (upper.matches("(?i)SET\\s+[A-Z0-9-]+\\s+TO\\s+TRUE")) {
            Matcher m = Pattern.compile("(?i)SET\\s+([A-Z0-9-]+)\\s+TO\\s+TRUE").matcher(s);
            if (m.find()) return List.of(toJavaIdent(m.group(1)) + " = true;");
        }
        if (upper.matches("(?i)INITIALIZE\\s+[A-Z0-9-]+")) {
            Matcher m = Pattern.compile("(?i)INITIALIZE\\s+([A-Z0-9-]+)").matcher(s);
            if (m.find()) {
                Field f = parsed.fields.get(m.group(1).toUpperCase(Locale.ROOT));
                String def = f == null ? "null"
                        : "String".equals(f.javaType) ? "\"\""
                        : "boolean".equals(f.javaType) ? "false"
                        : "int".equals(f.javaType) ? "0" : "0.0";
                return List.of(toJavaIdent(m.group(1)) + " = " + def + ";");
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
        // Unsupported — emit comment so class still compiles
        return List.of("// COBOL: " + s);
    }

    private static String castRead(String javaType) {
        return switch (javaType) {
            case "int" -> "Integer.parseInt(new java.util.Scanner(System.in).nextLine())";
            case "double" -> "Double.parseDouble(new java.util.Scanner(System.in).nextLine())";
            case "boolean" -> "Boolean.parseBoolean(new java.util.Scanner(System.in).nextLine())";
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

    private static String translateMove(String s) {
        Matcher m = Pattern.compile("(?i)MOVE\\s+(.+?)\\s+TO\\s+([A-Z0-9-]+)").matcher(s);
        if (!m.find()) return "// MOVE (unparsed)";
        return toJavaIdent(m.group(2)) + " = " + exprOperand(m.group(1).trim());
    }

    private static String translateCompute(String s) {
        Matcher m = Pattern.compile("(?i)COMPUTE\\s+([A-Z0-9-]+)\\s*=\\s*(.+)").matcher(s);
        if (!m.find()) return "// COMPUTE (unparsed)";
        return toJavaIdent(m.group(1)) + " = " + rewriteExpr(m.group(2).trim());
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
                // Inline THEN on same line after condition is rare; skip
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
                currentCond = null; // else
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
        // EQUAL / = / NOT =
        c = c.replaceAll("(?i)\\s+EQUAL\\s+TO\\s+", " == ");
        c = c.replaceAll("(?i)\\s+EQUALS\\s+", " == ");
        c = c.replaceAll("(?i)\\s+NOT\\s*=\\s*", " != ");
        c = c.replaceAll("(?i)\\s*=\\s*", " == ");
        c = c.replaceAll("(?i)\\s+AND\\s+", " && ");
        c = c.replaceAll("(?i)\\s+OR\\s+", " || ");
        // Fix accidental ==== from previous (shouldn't happen)
        c = c.replace("====", "==").replace("===", "==");
        return rewriteExpr(c);
    }

    private static String rewriteExpr(String expr) {
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile(
                "\"[^\"]*\"|'[^']*'|[A-Za-z][A-Za-z0-9-]*|\\d+(?:\\.\\d+)?|[^A-Za-z0-9\"']+")
                .matcher(expr);
        while (m.find()) {
            String tok = m.group();
            if ((tok.startsWith("\"") && tok.endsWith("\""))
                    || (tok.startsWith("'") && tok.endsWith("'"))) {
                out.append('"').append(tok.substring(1, tok.length() - 1)).append('"');
            } else if (tok.matches("[A-Za-z][A-Za-z0-9-]*")) {
                out.append(toJavaIdent(tok));
            } else {
                out.append(tok);
            }
        }
        return out.toString();
    }

    private static String exprOperand(String tok) {
        tok = tok.trim();
        if ((tok.startsWith("\"") && tok.endsWith("\""))
                || (tok.startsWith("'") && tok.endsWith("'"))) {
            return "\"" + tok.substring(1, tok.length() - 1) + "\"";
        }
        if (tok.matches("-?\\d+(?:\\.\\d+)?")) return tok;
        return toJavaIdent(tok);
    }

    private static List<String> splitCobolOperands(String rest) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        char q = 0;
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
            if (Character.isWhitespace(c)) {
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

    private static String picToJavaType(String pic) {
        if (pic == null) return "String";
        String p = pic.toUpperCase(Locale.ROOT);
        if (p.contains("X") || p.contains("A")) return "String";
        if (p.contains("V") || p.contains(".")) return "double";
        if (p.contains("9")) return "int";
        return "String";
    }

    private static String valueToJava(String value, String javaType) {
        if (value == null) {
            return switch (javaType) {
                case "int" -> "0";
                case "double" -> "0.0";
                case "boolean" -> "false";
                default -> "\"\"";
            };
        }
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            return "\"" + value.substring(1, value.length() - 1) + "\"";
        }
        if ("boolean".equals(javaType)) return "false";
        if ("String".equals(javaType)) return "\"" + value + "\"";
        // Leading-dot COBOL decimals: .150 → 0.150
        if (value.startsWith(".")) return "0" + value;
        return value;
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
