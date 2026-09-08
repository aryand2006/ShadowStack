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
 *
 * <p>Phase 6: CALL → {@code TranslatedX.main}, READ AT END, light SELECT/ASSIGN.</p>
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
    private static final Pattern SELECT_ASSIGN = Pattern.compile(
            "(?i)^SELECT\\s+([A-Z0-9-]+)\\s+ASSIGN\\s+(?:TO\\s+)?([A-Z0-9-]+)\\b.*$");
    private static final Pattern ORGANIZATION_IS = Pattern.compile(
            "(?i)^(?:ORGANIZATION\\s+IS\\s+|ORGANISATION\\s+IS\\s+)(INDEXED|SEQUENTIAL|RELATIVE)\\s*\\.?\\s*$");
    private static final Pattern RECORD_KEY_IS = Pattern.compile(
            "(?i)^RECORD\\s+KEY\\s+IS\\s+([A-Z0-9-]+)\\s*\\.?\\s*$");
    private static final Pattern CALL_LITERAL = Pattern.compile(
            "(?i)^CALL\\s+(?:'([^']+)'|\"([^\"]+)\"|([A-Z0-9][A-Z0-9-]*))"
                    + "(?:\\s+USING\\s+(.+))?$");
    private static final Pattern READ_AT_END_HEAD = Pattern.compile(
            "(?i)^READ\\s+([A-Z0-9-]+)(?:\\s+INTO\\s+\\S+)?\\s+AT\\s+END\\b(.*)$");

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
                List.copyOf(parsed.gaps), List.copyOf(parsed.resolvedCalls));
    }

    /** Outcome of a COBOL→Java translation. */
    public record Result(
            String className,
            String relativeJavaPath,
            String javaSource,
            String programId,
            int fieldCount,
            int paragraphCount,
            List<String> unsupportedGaps,
            List<String> resolvedCalls) {
        public Result {
            unsupportedGaps = unsupportedGaps == null ? List.of() : List.copyOf(unsupportedGaps);
            resolvedCalls = resolvedCalls == null ? List.of() : List.copyOf(resolvedCalls);
        }

        /** Backward-compatible constructor without resolved CALL list. */
        public Result(
                String className,
                String relativeJavaPath,
                String javaSource,
                String programId,
                int fieldCount,
                int paragraphCount,
                List<String> unsupportedGaps) {
            this(className, relativeJavaPath, javaSource, programId,
                    fieldCount, paragraphCount, unsupportedGaps, List.of());
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
        final boolean linkage;

        Field(String cobolName, String javaName, String javaType, String initExpr,
              boolean level88, int occurs, String redefines, String picComment,
              String aliasOfJavaName) {
            this(cobolName, javaName, javaType, initExpr, level88, occurs, redefines, picComment, aliasOfJavaName, false);
        }

        Field(String cobolName, String javaName, String javaType, String initExpr,
              boolean level88, int occurs, String redefines, String picComment,
              String aliasOfJavaName, boolean linkage) {
            this.cobolName = cobolName;
            this.javaName = javaName;
            this.javaType = javaType;
            this.initExpr = initExpr;
            this.level88 = level88;
            this.occurs = occurs;
            this.redefines = redefines;
            this.picComment = picComment;
            this.aliasOfJavaName = aliasOfJavaName;
            this.linkage = linkage;
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
        /** CALL targets resolved to TranslatedX.main naming. */
        final List<String> resolvedCalls = new ArrayList<>();
        /** SELECT logical-file → ASSIGN dd-name. */
        final Map<String, String> selectAssign = new LinkedHashMap<>();
        /** SELECT logical-file → SEQUENTIAL|INDEXED|RELATIVE. */
        final Map<String, String> fileOrg = new LinkedHashMap<>();
        /** SELECT logical-file → RECORD KEY field. */
        final Map<String, String> recordKeys = new LinkedHashMap<>();
        /** Last SELECT logical name (for multi-line ORGANIZATION / RECORD KEY). */
        String lastSelectLogical;
        /** Nested PROGRAM-ID names found inside outer program. */
        final List<String> nestedPrograms = new ArrayList<>();
        /** FD file-name currently being described (parse-time). */
        String currentFd;
        /** 01 record-name → FD file-name. */
        final Map<String, String> recordToFd = new LinkedHashMap<>();
        /** Record or FD name → byte length hint from PIC. */
        final Map<String, Integer> recordLength = new LinkedHashMap<>();
        boolean needsBigDecimal;
        boolean needsFileFacade;
        boolean needsIndexed;
        boolean needsCics;
        boolean needsSql;
        boolean needsIms;
        boolean needsSort;
        /** Parse-time: WORKING-STORAGE vs LINKAGE. */
        String dataSection = "WORKING-STORAGE";
        final List<String> linkageOrder = new ArrayList<>();
    }

    private static ParsedProgram parse(String source) {
        boolean fixed = isFixedFormat(source);
        String[] lines = source.split("\n", -1);
        ParsedProgram out = new ParsedProgram();
        String division = null;
        Paragraph current = null;
        List<String> pendingBlock = null;
        boolean pendingIsEvaluate = false;
        boolean pendingIsReadAtEnd = false;
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
                String id = pid.group(1).toUpperCase(Locale.ROOT);
                if (out.programId != null && !out.programId.equals(id)) {
                    out.nestedPrograms.add(id);
                    String gap = "Nested PROGRAM-ID (outer continues; nested body not separately emitted): " + id;
                    if (!out.gaps.contains(gap)) out.gaps.add(gap);
                    continue;
                }
                out.programId = id;
                continue;
            }

            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (upper.endsWith("DIVISION.") || upper.endsWith("DIVISION")) {
                division = upper.replace(".", "").trim();
                continue;
            }
            if (upper.contains("FILE-CONTROL") || upper.contains("I-O CONTROL")) {
                division = "ENVIRONMENT DIVISION";
                continue;
            }
            if (upper.contains("WORKING-STORAGE SECTION")
                    || upper.contains("LINKAGE SECTION")
                    || upper.contains("LOCAL-STORAGE SECTION")
                    || upper.contains("FILE SECTION")) {
                division = "DATA DIVISION";
                if (!upper.contains("FILE SECTION")) {
                    out.currentFd = null;
                }
                if (upper.contains("LINKAGE SECTION")) {
                    out.dataSection = "LINKAGE";
                } else if (upper.contains("WORKING-STORAGE SECTION")
                        || upper.contains("LOCAL-STORAGE SECTION")) {
                    out.dataSection = "WORKING-STORAGE";
                } else if (upper.contains("FILE SECTION")) {
                    out.dataSection = "FILE";
                }
                continue;
            }
            if (upper.startsWith("PROCEDURE DIVISION")) {
                division = "PROCEDURE DIVISION";
                Matcher using = Pattern.compile("(?i)PROCEDURE\\s+DIVISION\\s+USING\\s+(.+?)\\.?\\s*$").matcher(trimmed);
                if (using.matches()) {
                    for (String tok : using.group(1).trim().split("\\s+")) {
                        String name = tok.replace(",", "").trim().toUpperCase(Locale.ROOT);
                        if (name.isEmpty()) continue;
                        if (!out.linkageOrder.contains(name)) out.linkageOrder.add(name);
                        Field f = out.fields.get(name);
                        if (f != null && !f.linkage) {
                            out.fields.put(name, new Field(f.cobolName, f.javaName, f.javaType, f.initExpr,
                                    f.level88, f.occurs, f.redefines, f.picComment, f.aliasOfJavaName, true));
                        }
                    }
                }
                continue;
            }

            // Light SELECT / ASSIGN (FILE-CONTROL) — also accept before PROCEDURE.
            Matcher sel = SELECT_ASSIGN.matcher(trimmed);
            if (sel.matches()) {
                String logical = sel.group(1).toUpperCase(Locale.ROOT);
                String assign = sel.group(2).toUpperCase(Locale.ROOT);
                out.selectAssign.put(logical, assign);
                out.lastSelectLogical = logical;
                out.fileOrg.putIfAbsent(logical, "SEQUENTIAL");
                if (upper.contains("ORGANIZATION") && upper.contains("INDEXED")) {
                    out.fileOrg.put(logical, "INDEXED");
                    out.needsIndexed = true;
                } else if (upper.contains("ORGANIZATION") && upper.contains("RELATIVE")) {
                    out.fileOrg.put(logical, "RELATIVE");
                    out.needsIndexed = true; // relative uses same keyed MVP store
                }
                continue;
            }
            Matcher orgIs = ORGANIZATION_IS.matcher(trimmed);
            if (orgIs.matches() && out.lastSelectLogical != null) {
                String org = orgIs.group(1).toUpperCase(Locale.ROOT);
                out.fileOrg.put(out.lastSelectLogical, org);
                if ("INDEXED".equals(org) || "RELATIVE".equals(org)) {
                    out.needsIndexed = true;
                }
                continue;
            }
            Matcher rk = RECORD_KEY_IS.matcher(trimmed);
            if (rk.matches() && out.lastSelectLogical != null) {
                out.recordKeys.put(out.lastSelectLogical, rk.group(1).toUpperCase(Locale.ROOT));
                continue;
            }
            // FD / SD headers — bind following 01 records to the file.
            Matcher fd = Pattern.compile("(?i)^(FD|SD)\\s+([A-Z0-9-]+).*").matcher(trimmed);
            if (fd.matches()) {
                String kind = fd.group(1).toUpperCase(Locale.ROOT);
                String name = fd.group(2).toUpperCase(Locale.ROOT);
                if ("SD".equals(kind)) {
                    String gap = "SORT file SD not supported: " + name;
                    if (!out.gaps.contains(gap)) out.gaps.add(gap);
                    out.currentFd = null;
                } else {
                    out.currentFd = name;
                }
                continue;
            }

            if (division != null && division.startsWith("DATA")) {
                parseDataLine(trimmed, out);
                continue;
            }
            if (division != null && division.startsWith("ENVIRONMENT")) {
                continue;
            }

            if (division != null && division.startsWith("PROCEDURE")) {
                Matcher sm = SECTION_HEADER.matcher(trimmed);
                if (sm.matches()) {
                    if (current != null && pendingBlock != null) {
                        current.statements.addAll(flushPending(pendingBlock,
                                pendingIsEvaluate, pendingIsReadAtEnd));
                        pendingBlock = null;
                        pendingIsEvaluate = false;
                        pendingIsReadAtEnd = false;
                        ifDepth = 0;
                    }
                    // Phase 1: SECTION headers are paragraph entry points.
                    current = new Paragraph(sm.group(1).toUpperCase(Locale.ROOT));
                    out.paragraphs.add(current);
                    continue;
                }
                Matcher pm = PARAGRAPH_HEADER.matcher(trimmed);
                if (pm.matches()) {
                    String paraName = pm.group(1).toUpperCase(Locale.ROOT);
                    // Structured-statement terminators only — not names like END-PARA.
                    if (isScopeTerminator(paraName)) {
                        // fall through to statement / pending-block handling
                    } else {
                        if (current != null && pendingBlock != null) {
                            current.statements.addAll(flushPending(pendingBlock,
                                    pendingIsEvaluate, pendingIsReadAtEnd));
                            pendingBlock = null;
                            pendingIsEvaluate = false;
                            pendingIsReadAtEnd = false;
                            ifDepth = 0;
                        }
                        current = new Paragraph(paraName);
                        out.paragraphs.add(current);
                        continue;
                    }
                }
                if (current == null) {
                    current = new Paragraph("MAIN");
                    out.paragraphs.add(current);
                }

                if (pendingBlock != null) {
                    pendingBlock.add(trimmed);
                    String u = trimmed.toUpperCase(Locale.ROOT);
                    if (pendingIsReadAtEnd) {
                        if (u.contains("END-READ")) {
                            current.statements.addAll(flushReadAtEndBlock(pendingBlock));
                            pendingBlock = null;
                            pendingIsReadAtEnd = false;
                        }
                    } else if (pendingIsEvaluate) {
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
                    pendingIsReadAtEnd = false;
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
                    pendingIsReadAtEnd = false;
                    continue;
                }

                Matcher readAtEnd = READ_AT_END_HEAD.matcher(stripPeriod(trimmed));
                if (readAtEnd.matches()) {
                    String after = readAtEnd.group(2) == null ? "" : readAtEnd.group(2).trim();
                    String afterUpper = after.toUpperCase(Locale.ROOT);
                    // Single-line: READ x AT END stmt.  or  … END-READ
                    if (afterUpper.contains("END-READ")
                            || (trimmed.endsWith(".") && !after.isEmpty())) {
                        current.statements.add(trimmed);
                        continue;
                    }
                    // Multi-line block until END-READ
                    pendingBlock = new ArrayList<>();
                    pendingBlock.add(trimmed);
                    pendingIsReadAtEnd = true;
                    pendingIsEvaluate = false;
                    continue;
                }

                current.statements.add(trimmed);
            }
        }
        if (current != null && pendingBlock != null) {
            current.statements.addAll(flushPending(pendingBlock,
                    pendingIsEvaluate, pendingIsReadAtEnd));
        }
        if (out.paragraphs.isEmpty()) {
            out.paragraphs.add(new Paragraph("MAIN"));
        }
        return out;
    }

    private static List<String> flushPending(
            List<String> pendingBlock, boolean evaluate, boolean readAtEnd) {
        if (readAtEnd) return flushReadAtEndBlock(pendingBlock);
        if (evaluate) return flushEvaluateBlock(pendingBlock);
        return flushIfBlock(pendingBlock);
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

        // FILE SECTION: bind 01 record names to the current FD and capture length.
        if (out.currentFd != null && level == 1) {
            out.recordToFd.put(name, out.currentFd);
            int len = picByteLength(pic);
            if (len > 0) {
                out.recordLength.put(name, len);
                out.recordLength.putIfAbsent(out.currentFd, len);
            }
        }

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
        boolean linkage = "LINKAGE".equals(out.dataSection);
        out.fields.put(name, new Field(name, javaName, javaType, init, false,
                occursN, redefines, picComment, aliasOf, linkage));
        if (linkage && aliasOf == null && !out.linkageOrder.contains(name)) {
            out.linkageOrder.add(name);
        }
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

    private static boolean isScopeTerminator(String name) {
        return switch (name) {
            case "END-IF", "END-EVALUATE", "END-PERFORM", "END-SEARCH",
                 "END-COMPUTE", "END-READ", "END-WRITE", "END-DELETE",
                 "END-START", "END-RETURN", "END-ACCEPT", "END-STRING",
                 "END-UNSTRING", "END-CALL", "END-ADD", "END-SUBTRACT",
                 "END-MULTIPLY", "END-DIVIDE", "ELSE", "WHEN", "CONTINUE" -> true;
            default -> false;
        };
    }

    private static List<String> flushIfBlock(List<String> lines) {
        return List.of("@@IF@@" + String.join("\n", lines));
    }

    private static List<String> flushEvaluateBlock(List<String> lines) {
        return List.of("@@EVAL@@" + String.join("\n", lines));
    }

    private static List<String> flushReadAtEndBlock(List<String> lines) {
        return List.of("@@READATEND@@" + String.join("\n", lines));
    }

    // ── Emit ─────────────────────────────────────────────────────────────

    private static String emitJava(String className, ParsedProgram parsed) {
        // translateStatement sets needsFileFacade; pre-walk so helpers emit first.
        for (Paragraph p : parsed.paragraphs) {
            for (String stmt : p.statements) {
                previewFileIoNeed(stmt, parsed);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("/**\n");
        sb.append(" * Generated by ShadowStack CobolToJavaTranslator (semantic rehost MVP).\n");
        sb.append(" * Source PROGRAM-ID: ")
                .append(parsed.programId != null ? parsed.programId : "UNKNOWN")
                .append("\n");
        sb.append(" * Toward Blu Age–class rehost — see docs/blu-age-cobol-roadmap.md.\n");
        sb.append(" */\n");
        if (parsed.needsBigDecimal) {
            sb.append("import java.math.BigDecimal;\n\n");
        }
        sb.append("public class ").append(className).append(" {\n\n");

        if (parsed.needsFileFacade) {
            sb.append("    /** Phase 2 sequential file helpers (javac-friendly; swap for CobolFileFacade in host). */\n");
            sb.append("    private static final java.nio.file.Path __DATA = java.nio.file.Path.of(\".\");\n");
            sb.append("    private static final java.util.Map<String, java.io.BufferedReader> __IN = new java.util.HashMap<>();\n");
            sb.append("    private static final java.util.Map<String, java.io.OutputStream> __OUT = new java.util.HashMap<>();\n");
            sb.append("    private static void __openInput(String dd) throws Exception {\n");
            sb.append("        __IN.put(dd, java.nio.file.Files.newBufferedReader(__DATA.resolve(dd + \".dat\")));\n");
            sb.append("    }\n");
            sb.append("    private static void __openOutput(String dd, boolean append) throws Exception {\n");
            sb.append("        java.nio.file.Path p = __DATA.resolve(dd + \".dat\");\n");
            sb.append("        __OUT.put(dd, java.nio.file.Files.newOutputStream(p, append\n");
            sb.append("            ? new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND}\n");
            sb.append("            : new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE}));\n");
            sb.append("    }\n");
            sb.append("    private static boolean __read(String dd, byte[] buf) throws Exception {\n");
            sb.append("        java.io.BufferedReader r = __IN.get(dd); if (r == null) return false;\n");
            sb.append("        String line = r.readLine(); if (line == null) return false;\n");
            sb.append("        byte[] b = line.getBytes(java.nio.charset.StandardCharsets.UTF_8);\n");
            sb.append("        System.arraycopy(b, 0, buf, 0, Math.min(buf.length, b.length)); return true;\n");
            sb.append("    }\n");
            sb.append("    private static void __write(String dd, byte[] rec) throws Exception {\n");
            sb.append("        java.io.OutputStream o = __OUT.get(dd); if (o == null) return;\n");
            sb.append("        o.write(rec); o.write('\\n'); o.flush();\n");
            sb.append("    }\n");
            sb.append("    private static final java.util.Map<String, java.io.RandomAccessFile> __IO = new java.util.HashMap<>();\n");
            sb.append("    private static final java.util.Map<String, Long> __LAST_POS = new java.util.HashMap<>();\n");
            sb.append("    private static void __openIo(String dd) throws Exception {\n");
            sb.append("        java.nio.file.Path p = __DATA.resolve(dd + \".dat\");\n");
            sb.append("        if (!java.nio.file.Files.exists(p)) java.nio.file.Files.createFile(p);\n");
            sb.append("        __IO.put(dd, new java.io.RandomAccessFile(p.toFile(), \"rw\"));\n");
            sb.append("    }\n");
            sb.append("    private static boolean __readIo(String dd, byte[] buf) throws Exception {\n");
            sb.append("        java.io.RandomAccessFile raf = __IO.get(dd); if (raf == null) return false;\n");
            sb.append("        long pos = raf.getFilePointer(); String line = raf.readLine(); if (line == null) return false;\n");
            sb.append("        __LAST_POS.put(dd, pos);\n");
            sb.append("        byte[] b = line.getBytes(java.nio.charset.StandardCharsets.UTF_8);\n");
            sb.append("        System.arraycopy(b, 0, buf, 0, Math.min(buf.length, b.length)); return true;\n");
            sb.append("    }\n");
            sb.append("    private static void __rewrite(String dd, byte[] rec) throws Exception {\n");
            sb.append("        java.io.RandomAccessFile raf = __IO.get(dd); if (raf == null) return;\n");
            sb.append("        Long pos = __LAST_POS.get(dd); if (pos == null) return;\n");
            sb.append("        long resume = raf.getFilePointer();\n");
            sb.append("        raf.seek(pos); raf.write(rec); raf.write('\\n'); raf.seek(resume);\n");
            sb.append("    }\n");
            sb.append("    private static void __close(String dd) throws Exception {\n");
            sb.append("        java.io.BufferedReader r = __IN.remove(dd); if (r != null) r.close();\n");
            sb.append("        java.io.OutputStream o = __OUT.remove(dd); if (o != null) o.close();\n");
            sb.append("        java.io.RandomAccessFile raf = __IO.remove(dd); if (raf != null) raf.close();\n");
            sb.append("        __LAST_POS.remove(dd);\n");
            sb.append("    }\n");
            sb.append("    private static void __start(String dd) throws Exception {\n");
            sb.append("        java.io.RandomAccessFile raf = __IO.get(dd); if (raf != null) { raf.seek(0); return; }\n");
            sb.append("        java.io.BufferedReader r = __IN.remove(dd); if (r != null) r.close();\n");
            sb.append("        __openInput(dd);\n");
            sb.append("    }\n");
            sb.append("    private static void __deleteCurrent(String dd) throws Exception {\n");
            sb.append("        // Sequential MVP: blank the last READ line (I-O only).\n");
            sb.append("        __rewrite(dd, new byte[0]);\n");
            sb.append("    }\n\n");
        }

        if (parsed.needsIndexed) {
            sb.append("    /** INDEXED/RELATIVE MVP — key→record map persisted as dd.idxdat. */\n");
            sb.append("    private static final java.util.Map<String, java.util.LinkedHashMap<String, String>> __IDX = new java.util.HashMap<>();\n");
            sb.append("    private static final java.util.Map<String, java.util.Iterator<java.util.Map.Entry<String, String>>> __IDX_CUR = new java.util.HashMap<>();\n");
            sb.append("    private static final java.util.Map<String, String> __IDX_LAST = new java.util.HashMap<>();\n");
            sb.append("    private static void __idxLoad(String dd) throws Exception {\n");
            sb.append("        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();\n");
            sb.append("        java.nio.file.Path p = java.nio.file.Path.of(\".\").resolve(dd + \".idxdat\");\n");
            sb.append("        if (java.nio.file.Files.isRegularFile(p)) {\n");
            sb.append("            for (String line : java.nio.file.Files.readAllLines(p)) {\n");
            sb.append("                int t = line.indexOf('\\t'); if (t <= 0) continue;\n");
            sb.append("                m.put(line.substring(0, t), line.substring(t + 1));\n");
            sb.append("            }\n");
            sb.append("        }\n");
            sb.append("        __IDX.put(dd, m); __IDX_CUR.put(dd, m.entrySet().iterator());\n");
            sb.append("    }\n");
            sb.append("    private static void __idxSave(String dd) throws Exception {\n");
            sb.append("        java.util.LinkedHashMap<String, String> m = __IDX.get(dd); if (m == null) return;\n");
            sb.append("        StringBuilder sb = new StringBuilder();\n");
            sb.append("        for (var e : m.entrySet()) sb.append(e.getKey()).append('\\t').append(e.getValue()).append('\\n');\n");
            sb.append("        java.nio.file.Files.writeString(java.nio.file.Path.of(\".\").resolve(dd + \".idxdat\"), sb.toString());\n");
            sb.append("    }\n");
            sb.append("    private static void __idxOpen(String dd) throws Exception { __idxLoad(dd); }\n");
            sb.append("    private static void __idxWrite(String dd, String key, String rec) throws Exception {\n");
            sb.append("        __IDX.computeIfAbsent(dd, k -> new java.util.LinkedHashMap<>()).put(key, rec);\n");
            sb.append("        __IDX_LAST.put(dd, key);\n");
            sb.append("        __IDX_CUR.put(dd, __IDX.get(dd).entrySet().iterator());\n");
            sb.append("    }\n");
            sb.append("    private static boolean __idxReadKey(String dd, String key, String[] outHolder) {\n");
            sb.append("        var m = __IDX.get(dd); if (m == null || !m.containsKey(key)) return false;\n");
            sb.append("        outHolder[0] = m.get(key); __IDX_LAST.put(dd, key); return true;\n");
            sb.append("    }\n");
            sb.append("    private static boolean __idxReadNext(String dd, String[] outHolder) {\n");
            sb.append("        var it = __IDX_CUR.get(dd); if (it == null || !it.hasNext()) return false;\n");
            sb.append("        var e = it.next(); __IDX_LAST.put(dd, e.getKey()); outHolder[0] = e.getValue(); return true;\n");
            sb.append("    }\n");
            sb.append("    private static void __idxDelete(String dd, String key) {\n");
            sb.append("        var m = __IDX.get(dd); if (m != null) m.remove(key);\n");
            sb.append("        if (m != null) __IDX_CUR.put(dd, m.entrySet().iterator());\n");
            sb.append("    }\n");
            sb.append("    private static void __idxStart(String dd, String key) {\n");
            sb.append("        var m = __IDX.get(dd); if (m == null) return;\n");
            sb.append("        var rest = new java.util.LinkedHashMap<String, String>();\n");
            sb.append("        boolean on = false;\n");
            sb.append("        for (var e : m.entrySet()) { if (!on && e.getKey().compareTo(key) >= 0) on = true; if (on) rest.put(e.getKey(), e.getValue()); }\n");
            sb.append("        __IDX_CUR.put(dd, rest.entrySet().iterator());\n");
            sb.append("    }\n");
            sb.append("    private static void __idxClose(String dd) throws Exception { __idxSave(dd); __IDX.remove(dd); __IDX_CUR.remove(dd); __IDX_LAST.remove(dd); }\n\n");
        }

        if (parsed.needsSort) {
            sb.append("    private static void __sortLines(String inDd, String outDd) throws Exception {\n");
            sb.append("        java.nio.file.Path in = java.nio.file.Path.of(\".\").resolve(inDd + \".dat\");\n");
            sb.append("        java.nio.file.Path out = java.nio.file.Path.of(\".\").resolve(outDd + \".dat\");\n");
            sb.append("        java.util.List<String> lines = java.nio.file.Files.readAllLines(in);\n");
            sb.append("        java.util.Collections.sort(lines);\n");
            sb.append("        java.nio.file.Files.write(out, lines);\n");
            sb.append("    }\n\n");
        }

        if (parsed.needsCics) {
            sb.append("    /** Inline CICS MVP — replace with host CicsFacade for production. */\n");
            sb.append("    private static final java.util.Map<String, byte[]> __TSQ = new java.util.HashMap<>();\n");
            sb.append("    private static void __cicsLink(String program) {\n");
            sb.append("        try {\n");
            sb.append("            Class<?> c = Class.forName(\"Translated\" + program.replace('-', '_'));\n");
            sb.append("            c.getMethod(\"main\", String[].class).invoke(null, (Object) new String[0]);\n");
            sb.append("        } catch (ReflectiveOperationException e) {\n");
            sb.append("            throw new RuntimeException(\"CICS LINK \" + program + \": \" + e.getMessage(), e);\n");
            sb.append("        }\n");
            sb.append("    }\n");
            sb.append("    private static void __cicsXctl(String program) { __cicsLink(program); }\n");
            sb.append("    private static void __cicsWriteQ(String q, String data) {\n");
            sb.append("        __TSQ.put(q, data.getBytes(java.nio.charset.StandardCharsets.UTF_8));\n");
            sb.append("    }\n");
            sb.append("    private static String __cicsReadQ(String q) {\n");
            sb.append("        byte[] b = __TSQ.get(q); return b == null ? \"\" : new String(b, java.nio.charset.StandardCharsets.UTF_8);\n");
            sb.append("    }\n");
            sb.append("    private static void __cicsSyncpoint() { /* no-op MVP */ }\n");
            sb.append("    private static void __cicsRollback() { throw new UnsupportedOperationException(\"CICS SYNCPOINT ROLLBACK\"); }\n\n");
        }

        if (parsed.needsSql) {
            sb.append("    /** Inline EXEC SQL MVP — host should inject JDBC. Fail-closed by default. */\n");
            sb.append("    private static void __sqlExec(String sql) {\n");
            sb.append("        throw new UnsupportedOperationException(\"EXEC SQL (inject JDBC): \" + sql);\n");
            sb.append("    }\n\n");
        }

        if (parsed.needsIms) {
            sb.append("    /** Inline IMS DL/I MVP — PCB→segment map (replace with ImsFacade). */\n");
            sb.append("    private static final java.util.Map<String, String> __IMS = new java.util.HashMap<>();\n");
            sb.append("    private static String __imsGu(String pcb) { return __IMS.getOrDefault(pcb, \"\"); }\n");
            sb.append("    private static String __imsGn(String pcb) { return __imsGu(pcb); }\n");
            sb.append("    private static void __imsIsrt(String pcb, String seg) { __IMS.put(pcb, seg); }\n");
            sb.append("    private static void __imsRepl(String pcb, String seg) { __IMS.put(pcb, seg); }\n");
            sb.append("    private static void __imsDlet(String pcb) { __IMS.remove(pcb); }\n\n");
        }

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
        if (!parsed.linkageOrder.isEmpty()) {
            sb.append("        __bindLinkage(args);\n");
        }
        sb.append("        ").append(toCamel(entry)).append("();\n");
        sb.append("    }\n\n");
        if (!parsed.linkageOrder.isEmpty()) {
            sb.append("    private static void __bindLinkage(String[] args) {\n");
            sb.append("        if (args == null) return;\n");
            int li = 0;
            for (String linkName : parsed.linkageOrder) {
                Field lf = parsed.fields.get(linkName);
                if (lf == null || lf.aliasOfJavaName != null) continue;
                String jn = lf.javaName;
                String jt = lf.javaType;
                sb.append("        if (args.length > ").append(li).append(" && args[").append(li).append("] != null) {\n");
                if ("int".equals(jt)) {
                    sb.append("            ").append(jn).append(" = Integer.parseInt(args[").append(li).append("]);\n");
                } else if ("long".equals(jt)) {
                    sb.append("            ").append(jn).append(" = Long.parseLong(args[").append(li).append("]);\n");
                } else if ("double".equals(jt)) {
                    sb.append("            ").append(jn).append(" = Double.parseDouble(args[").append(li).append("]);\n");
                } else if ("java.math.BigDecimal".equals(jt) || "BigDecimal".equals(jt)) {
                    sb.append("            ").append(jn).append(" = new ").append(
                            parsed.needsBigDecimal ? "BigDecimal" : "java.math.BigDecimal")
                            .append("(args[").append(li).append("]);\n");
                } else {
                    sb.append("            ").append(jn).append(" = args[").append(li).append("];\n");
                }
                sb.append("        }\n");
                li++;
            }
            sb.append("    }\n\n");
        }

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
        if (stmt.startsWith("@@READATEND@@")) {
            return translateReadAtEnd(stmt.substring(13), parsed);
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

        // CALL before generic file I/O so CALL is not swallowed.
        if (upper.startsWith("CALL ")) {
            return translateCall(s, parsed);
        }

        List<String> fileIo = translateFileIo(s, parsed);
        if (fileIo != null) {
            return fileIo;
        }

        // EXEC CICS / EXEC SQL / EXEC DLI → inline MVP façades (javac-friendly).
        if (upper.startsWith("EXEC CICS") || upper.contains(" EXEC CICS")) {
            return translateExecCics(s, parsed);
        }
        if (upper.startsWith("EXEC DLI") || upper.contains(" EXEC DLI")) {
            return translateExecDli(s, parsed);
        }
        if (upper.startsWith("EXEC SQL") || upper.contains(" EXEC SQL")) {
            return translateExecSql(s, parsed);
        }
        if (upper.startsWith("SORT ") || upper.startsWith("MERGE ")) {
            return translateSortMerge(s, parsed);
        }

        // Unsupported — emit comment so class still compiles; record gap.
        String gap = unsupportedGapLabel(upper, s);
        if (!parsed.gaps.contains(gap)) {
            parsed.gaps.add(gap);
        }
        return List.of("// COBOL: " + s);
    }

    private static List<String> translateCall(String s, ParsedProgram parsed) {
        Matcher cm = CALL_LITERAL.matcher(s);
        if (cm.find()) {
            boolean literal = cm.group(1) != null || cm.group(2) != null;
            String callee = cm.group(1) != null ? cm.group(1)
                    : cm.group(2) != null ? cm.group(2) : cm.group(3);
            callee = callee.toUpperCase(Locale.ROOT);
            String usingClause = cm.group(4);
            List<String> args = new ArrayList<>();
            if (usingClause != null && !usingClause.isBlank()) {
                for (String tok : usingClause.trim().split("\\s+")) {
                    String t = tok.replace(",", "").trim();
                    if (t.isEmpty()) continue;
                    String u = t.toUpperCase(Locale.ROOT);
                    if (u.equals("BY") || u.equals("REFERENCE") || u.equals("CONTENT") || u.equals("VALUE")) {
                        continue;
                    }
                    args.add("String.valueOf(" + toJavaIdent(t) + ")");
                }
            }
            String argsExpr = args.isEmpty() ? "new String[0]" : "new String[]{ " + String.join(", ", args) + " }";

            // Identifier CALL to a WORKING-STORAGE field → dynamic Class.forName target.
            if (!literal && parsed.fields.containsKey(callee)) {
                String dyn = toJavaIdent(callee);
                return List.of(
                        "{ String __dyn = String.valueOf(" + dyn + ").trim().toUpperCase().replace('-', '_');",
                        "  try { Class.forName(\"Translated\" + __dyn).getMethod(\"main\", String[].class)"
                                + ".invoke(null, (Object) " + argsExpr + "); }",
                        "  catch (ReflectiveOperationException __ex) { throw new RuntimeException(\"dynamic CALL \" + __dyn, __ex); } }");
            }

            if (!parsed.resolvedCalls.contains(callee)) {
                parsed.resolvedCalls.add(callee);
            }
            String javaClass = "Translated" + callee.replace('-', '_');
            return List.of(javaClass + ".main(" + argsExpr + ");");
        }
        String gap = "CALL program (unresolved target): " + s;
        if (!parsed.gaps.contains(gap)) parsed.gaps.add(gap);
        return List.of("// " + gap);
    }

    /**
     * Phase 2: map sequential OPEN/READ/WRITE/CLOSE onto {@code CobolFileFacade}.
     * REWRITE/DELETE/START remain gaps. Returns null when not a file verb.
     */
    private static void previewFileIoNeed(String stmt, ParsedProgram parsed) {
        if (stmt == null) return;
        if (stmt.startsWith("@@IF@@")) {
            for (String line : stmt.substring(6).split("\n")) {
                previewFileIoNeed(line, parsed);
            }
            return;
        }
        if (stmt.startsWith("@@EVAL@@")) {
            for (String line : stmt.substring(8).split("\n")) {
                previewFileIoNeed(line, parsed);
            }
            return;
        }
        if (stmt.startsWith("@@READATEND@@")) {
            for (String line : stmt.substring(13).split("\n")) {
                previewFileIoNeed(line, parsed);
            }
            return;
        }
        String s = stripPeriod(stmt.trim());
        String upper = s.toUpperCase(Locale.ROOT);
        if (upper.startsWith("OPEN ") || upper.startsWith("READ ")
                || upper.startsWith("WRITE ") || upper.startsWith("CLOSE ")
                || upper.startsWith("REWRITE ") || upper.startsWith("DELETE ")
                || upper.startsWith("START ")) {
            parsed.needsFileFacade = true;
        }
        if (upper.startsWith("SORT ") || upper.startsWith("MERGE ")) {
            parsed.needsSort = true;
        }
        if (upper.startsWith("EXEC CICS") || upper.contains(" EXEC CICS")) {
            parsed.needsCics = true;
        }
        if (upper.startsWith("EXEC SQL") || upper.contains(" EXEC SQL")) {
            parsed.needsSql = true;
        }
        if (upper.startsWith("EXEC DLI") || upper.contains(" EXEC DLI")
                || upper.contains("CBLTDLI") || upper.startsWith("CALL 'CBLTDLI'")
                || upper.startsWith("CALL \"CBLTDLI\"")) {
            parsed.needsIms = true;
        }
        if (!parsed.fileOrg.isEmpty() && parsed.fileOrg.containsValue("INDEXED")) {
            parsed.needsIndexed = true;
        }
        if (!parsed.fileOrg.isEmpty() && parsed.fileOrg.containsValue("RELATIVE")) {
            parsed.needsIndexed = true;
        }
    }

    /**
     * Resolve a logical file, FD name, or 01 record name to the ASSIGN dd-name.
     */
    private static String resolveAssign(String logicalOrRecord, ParsedProgram parsed) {
        String key = logicalOrRecord.toUpperCase(Locale.ROOT);
        if (parsed.selectAssign.containsKey(key)) {
            return parsed.selectAssign.get(key);
        }
        String fd = parsed.recordToFd.get(key);
        if (fd != null) {
            return parsed.selectAssign.getOrDefault(fd, fd);
        }
        // Direct FD open/close without SELECT — use FD name as dd.
        if (parsed.recordToFd.containsValue(key)) {
            return parsed.selectAssign.getOrDefault(key, key);
        }
        return key;
    }

    private static int recordBufSize(String logicalOrRecord, ParsedProgram parsed) {
        String key = logicalOrRecord.toUpperCase(Locale.ROOT);
        Integer len = parsed.recordLength.get(key);
        if (len == null) {
            String fd = parsed.recordToFd.get(key);
            if (fd != null) len = parsed.recordLength.get(fd);
        }
        if (len == null || len <= 0) return 256;
        return Math.max(len, 1);
    }

    private static boolean isIndexed(String logicalOrRecord, ParsedProgram parsed) {
        String key = logicalOrRecord.toUpperCase(Locale.ROOT);
        String org = parsed.fileOrg.get(key);
        if (org == null) {
            String fd = parsed.recordToFd.get(key);
            if (fd != null) org = parsed.fileOrg.get(fd);
        }
        return "INDEXED".equals(org) || "RELATIVE".equals(org);
    }

    private static String resolveLogicalFile(String logicalOrRecord, ParsedProgram parsed) {
        String key = logicalOrRecord.toUpperCase(Locale.ROOT);
        if (parsed.selectAssign.containsKey(key)) return key;
        String fd = parsed.recordToFd.get(key);
        return fd != null ? fd : key;
    }

    private static String indexedKeyExpr(String logical, ParsedProgram parsed) {
        String file = resolveLogicalFile(logical, parsed);
        String keyField = parsed.recordKeys.get(file);
        if (keyField != null) {
            return "String.valueOf(" + lhs(keyField, parsed) + ")";
        }
        return "\"KEY\"";
    }

    private static List<String> translateFileIo(String s, ParsedProgram parsed) {
        Matcher readAtEnd = READ_AT_END_HEAD.matcher(s);
        if (readAtEnd.matches()) {
            return translateReadAtEnd(s, parsed);
        }

        Matcher open = Pattern.compile(
                "(?i)^OPEN\\s+(INPUT|OUTPUT|EXTEND|I-O)\\s+([A-Z0-9-]+)(?:\\s+.*)?").matcher(s);
        if (open.matches()) {
            String mode = open.group(1).toUpperCase(Locale.ROOT);
            String logical = open.group(2).toUpperCase(Locale.ROOT);
            if (!parsed.selectAssign.isEmpty() && !parsed.selectAssign.containsKey(logical)
                    && !parsed.recordToFd.containsValue(logical)) {
                String gap = "OPEN references unknown SELECT: " + logical;
                if (!parsed.gaps.contains(gap)) parsed.gaps.add(gap);
            }
            String dd = resolveAssign(logical, parsed);
            if (isIndexed(logical, parsed)) {
                parsed.needsIndexed = true;
                return List.of("try { __idxOpen(\"" + dd + "\"); } catch (Exception __ex) { throw new RuntimeException(__ex); }");
            }
            parsed.needsFileFacade = true;
            String method = switch (mode) {
                case "INPUT" -> "__openInput(\"" + dd + "\")";
                case "OUTPUT" -> "__openOutput(\"" + dd + "\", false)";
                case "EXTEND" -> "__openOutput(\"" + dd + "\", true)";
                case "I-O" -> "__openIo(\"" + dd + "\")";
                default -> "__openInput(\"" + dd + "\")";
            };
            return List.of("try { " + method + "; } catch (Exception __ex) { throw new RuntimeException(__ex); }");
        }
        Matcher readKey = Pattern.compile(
                "(?i)^READ\\s+([A-Z0-9-]+)\\s+KEY\\s+(?:IS\\s+)?(\\S+)(?:\\s+INTO\\s+([A-Z0-9-]+))?").matcher(s);
        if (readKey.matches()) {
            String logical = readKey.group(1).toUpperCase(Locale.ROOT);
            String keyTok = readKey.group(2);
            String into = readKey.group(3);
            String dd = resolveAssign(logical, parsed);
            parsed.needsIndexed = true;
            String id = toJavaIdent(dd);
            List<String> lines = new ArrayList<>();
            lines.add("String[] __hold_" + id + " = new String[1];");
            lines.add("boolean __ok_" + id + " = __idxReadKey(\"" + dd + "\", String.valueOf("
                    + exprOperand(keyTok) + "), __hold_" + id + ");");
            if (into != null) {
                lines.add("if (__ok_" + id + ") { " + lhs(into, parsed) + " = __hold_" + id + "[0]; }");
            }
            return lines;
        }
        Matcher read = Pattern.compile(
                "(?i)^READ\\s+([A-Z0-9-]+)(?:\\s+INTO\\s+([A-Z0-9-]+))?(?:\\s+.*)?").matcher(s);
        if (read.matches()) {
            String logical = read.group(1).toUpperCase(Locale.ROOT);
            String into = read.group(2);
            String dd = resolveAssign(logical, parsed);
            String id = toJavaIdent(dd);
            if (isIndexed(logical, parsed)) {
                parsed.needsIndexed = true;
                List<String> lines = new ArrayList<>();
                lines.add("String[] __hold_" + id + " = new String[1];");
                lines.add("boolean __ok_" + id + " = __idxReadNext(\"" + dd + "\", __hold_" + id + ");");
                if (into != null) {
                    lines.add("if (__ok_" + id + ") { " + lhs(into, parsed) + " = __hold_" + id + "[0]; }");
                }
                return lines;
            }
            parsed.needsFileFacade = true;
            int buf = recordBufSize(logical, parsed);
            List<String> lines = new ArrayList<>();
            lines.add("byte[] __rec_" + id + " = new byte[" + buf + "];");
            lines.add("boolean __ok_" + id + " = false;");
            lines.add("try { __ok_" + id + " = __IO.containsKey(\"" + dd + "\")"
                    + " ? __readIo(\"" + dd + "\", __rec_" + id + ")"
                    + " : __read(\"" + dd + "\", __rec_" + id + ");"
                    + " } catch (Exception __ex) { throw new RuntimeException(__ex); }");
            if (into != null) {
                String field = lhs(into, parsed);
                lines.add("if (__ok_" + id + ") { " + field
                        + " = new String(__rec_" + id + ", java.nio.charset.StandardCharsets.UTF_8).trim(); }");
            }
            return lines;
        }
        Matcher write = Pattern.compile("(?i)^WRITE\\s+([A-Z0-9-]+)(?:\\s+FROM\\s+(\\S+))?").matcher(s);
        if (write.matches()) {
            String rec = write.group(1).toUpperCase(Locale.ROOT);
            String dd = resolveAssign(rec, parsed);
            String payloadStr;
            if (write.group(2) != null) {
                payloadStr = "String.valueOf(" + exprOperand(write.group(2)) + ")";
            } else if (parsed.fields.containsKey(rec) || parsed.recordToFd.containsKey(rec)) {
                payloadStr = "String.valueOf(" + lhs(rec, parsed) + ")";
            } else {
                payloadStr = "\"\"";
            }
            if (isIndexed(rec, parsed) || isIndexed(resolveLogicalFile(rec, parsed), parsed)) {
                parsed.needsIndexed = true;
                String keyExpr = indexedKeyExpr(rec, parsed);
                return List.of("try { __idxWrite(\"" + dd + "\", " + keyExpr + ", " + payloadStr
                        + "); } catch (Exception __ex) { throw new RuntimeException(__ex); }");
            }
            parsed.needsFileFacade = true;
            String payload = payloadStr + ".getBytes(java.nio.charset.StandardCharsets.UTF_8)";
            return List.of("try { __write(\"" + dd + "\", " + payload + "); } catch (Exception __ex) { throw new RuntimeException(__ex); }");
        }
        Matcher rewrite = Pattern.compile("(?i)^REWRITE\\s+([A-Z0-9-]+)(?:\\s+FROM\\s+(\\S+))?").matcher(s);
        if (rewrite.matches()) {
            String rec = rewrite.group(1).toUpperCase(Locale.ROOT);
            String dd = resolveAssign(rec, parsed);
            String payloadStr;
            if (rewrite.group(2) != null) {
                payloadStr = "String.valueOf(" + exprOperand(rewrite.group(2)) + ")";
            } else if (parsed.fields.containsKey(rec) || parsed.recordToFd.containsKey(rec)) {
                payloadStr = "String.valueOf(" + lhs(rec, parsed) + ")";
            } else {
                String gap = "REWRITE record not in FD/WS: " + rec;
                if (!parsed.gaps.contains(gap)) parsed.gaps.add(gap);
                payloadStr = "\"\"";
            }
            if (isIndexed(rec, parsed) || isIndexed(resolveLogicalFile(rec, parsed), parsed)) {
                parsed.needsIndexed = true;
                return List.of(
                        "try { String __lk = __IDX_LAST.get(\"" + dd + "\"); if (__lk != null) __idxWrite(\""
                                + dd + "\", __lk, " + payloadStr
                                + "); } catch (Exception __ex) { throw new RuntimeException(__ex); }");
            }
            parsed.needsFileFacade = true;
            String payload = payloadStr + ".getBytes(java.nio.charset.StandardCharsets.UTF_8)";
            return List.of("try { __rewrite(\"" + dd + "\", " + payload + "); } catch (Exception __ex) { throw new RuntimeException(__ex); }");
        }
        Matcher close = Pattern.compile("(?i)^CLOSE\\s+([A-Z0-9-]+)(?:\\s+.*)?").matcher(s);
        if (close.matches()) {
            String logical = close.group(1).toUpperCase(Locale.ROOT);
            String dd = resolveAssign(logical, parsed);
            if (isIndexed(logical, parsed)) {
                parsed.needsIndexed = true;
                return List.of("try { __idxClose(\"" + dd + "\"); } catch (Exception __ex) { throw new RuntimeException(__ex); }");
            }
            parsed.needsFileFacade = true;
            return List.of("try { __close(\"" + dd
                    + "\"); } catch (Exception __ex) { throw new RuntimeException(__ex); }");
        }
        Matcher start = Pattern.compile(
                "(?i)^START\\s+([A-Z0-9-]+)(?:\\s+KEY\\s+(?:IS\\s+)?(\\S+))?(?:\\s+.*)?").matcher(s);
        if (start.matches()) {
            String logical = start.group(1).toUpperCase(Locale.ROOT);
            String dd = resolveAssign(logical, parsed);
            if (isIndexed(logical, parsed)) {
                parsed.needsIndexed = true;
                String keyExpr = start.group(2) != null
                        ? "String.valueOf(" + exprOperand(start.group(2)) + ")"
                        : indexedKeyExpr(logical, parsed);
                return List.of("__idxStart(\"" + dd + "\", " + keyExpr + ");");
            }
            parsed.needsFileFacade = true;
            return List.of("try { __start(\"" + dd + "\"); } catch (Exception __ex) { throw new RuntimeException(__ex); }");
        }
        Matcher del = Pattern.compile("(?i)^DELETE\\s+([A-Z0-9-]+)(?:\\s+.*)?").matcher(s);
        if (del.matches()) {
            String logical = del.group(1).toUpperCase(Locale.ROOT);
            String dd = resolveAssign(logical, parsed);
            if (isIndexed(logical, parsed)) {
                parsed.needsIndexed = true;
                return List.of("__idxDelete(\"" + dd + "\", " + indexedKeyExpr(logical, parsed) + ");");
            }
            parsed.needsFileFacade = true;
            return List.of("try { __deleteCurrent(\"" + dd + "\"); } catch (Exception __ex) { throw new RuntimeException(__ex); }");
        }
        return null;
    }

    /**
     * Emit READ + {@code if (!__ok_X) { … }} for AT END body.
     * Accepts a single-line statement or a multi-line block (newline-separated).
     */
    private static List<String> translateReadAtEnd(String block, ParsedProgram parsed) {
        String[] lines = block.split("\n");
        String head = stripPeriod(lines[0].trim());
        Matcher m = READ_AT_END_HEAD.matcher(head);
        if (!m.matches()) {
            // Fall back: treat as plain READ of first token after READ.
            Matcher plain = Pattern.compile("(?i)^READ\\s+([A-Z0-9-]+)").matcher(head);
            if (plain.find()) {
                return translateFileIo("READ " + plain.group(1), parsed);
            }
            return List.of("// COBOL: " + head);
        }
        String logical = m.group(1).toUpperCase(Locale.ROOT);
        String dd = resolveAssign(logical, parsed);
        String id = toJavaIdent(dd);
        parsed.needsFileFacade = true;
        int buf = recordBufSize(logical, parsed);

        List<String> bodyStmts = new ArrayList<>();
        String inline = m.group(2) == null ? "" : m.group(2).trim();
        if (!inline.isEmpty()) {
            String cleaned = inline.replaceAll("(?i)\\s*END-READ\\s*$", "").trim();
            cleaned = stripPeriod(cleaned);
            if (!cleaned.isEmpty()) {
                bodyStmts.addAll(translateStatement(cleaned, parsed));
            }
        }
        for (int i = 1; i < lines.length; i++) {
            String t = stripPeriod(lines[i].trim());
            String u = t.toUpperCase(Locale.ROOT);
            if (u.startsWith("END-READ") || u.isEmpty()) continue;
            if (u.startsWith("AT END")) {
                String rest = t.substring(6).trim();
                if (!rest.isEmpty()) bodyStmts.addAll(translateStatement(rest, parsed));
                continue;
            }
            bodyStmts.addAll(translateStatement(t, parsed));
        }

        List<String> out = new ArrayList<>();
        out.add("byte[] __rec_" + id + " = new byte[" + buf + "];");
        out.add("boolean __ok_" + id + " = false;");
        out.add("try { __ok_" + id + " = __IO.containsKey(\"" + dd + "\")"
                + " ? __readIo(\"" + dd + "\", __rec_" + id + ")"
                + " : __read(\"" + dd + "\", __rec_" + id + ");"
                + " } catch (Exception __ex) { throw new RuntimeException(__ex); }");
        out.add("if (!__ok_" + id + ") {");
        for (String line : bodyStmts) {
            out.add("    " + line);
        }
        out.add("}");
        return out;
    }


    private static List<String> translateExecCics(String s, ParsedProgram parsed) {
        parsed.needsCics = true;
        String u = s.toUpperCase(Locale.ROOT);
        Matcher link = Pattern.compile("(?i)EXEC\\s+CICS\\s+LINK\\s+PROGRAM\\s*\\(\\s*['\"]?([A-Z0-9-]+)['\"]?\\s*\\)").matcher(s);
        if (link.find()) {
            return List.of("__cicsLink(\"" + link.group(1).toUpperCase(Locale.ROOT) + "\");");
        }
        Matcher xctl = Pattern.compile("(?i)EXEC\\s+CICS\\s+XCTL\\s+PROGRAM\\s*\\(\\s*['\"]?([A-Z0-9-]+)['\"]?\\s*\\)").matcher(s);
        if (xctl.find()) {
            return List.of("__cicsXctl(\"" + xctl.group(1).toUpperCase(Locale.ROOT) + "\");");
        }
        Matcher wq = Pattern.compile("(?i)EXEC\\s+CICS\\s+WRITEQ\\s+TS\\s+QUEUE\\s*\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\).*FROM\\s*\\(\\s*([A-Z0-9-]+)\\s*\\)").matcher(s);
        if (wq.find()) {
            return List.of("__cicsWriteQ(\"" + wq.group(1).trim() + "\", String.valueOf("
                    + toJavaIdent(wq.group(2)) + "));");
        }
        Matcher rq = Pattern.compile("(?i)EXEC\\s+CICS\\s+READQ\\s+TS\\s+QUEUE\\s*\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\).*INTO\\s*\\(\\s*([A-Z0-9-]+)\\s*\\)").matcher(s);
        if (rq.find()) {
            return List.of(toJavaIdent(rq.group(2)) + " = __cicsReadQ(\"" + rq.group(1).trim() + "\");");
        }
        if (u.contains("SYNCPOINT") && u.contains("ROLLBACK")) {
            return List.of("__cicsRollback();");
        }
        if (u.contains("SYNCPOINT")) {
            return List.of("__cicsSyncpoint();");
        }
        if (u.contains("RETURN")) {
            return List.of("return; // CICS RETURN");
        }
        String gap = "CICS verb (unsupported subset): " + s;
        if (!parsed.gaps.contains(gap)) parsed.gaps.add(gap);
        return List.of("// " + gap);
    }

    private static List<String> translateExecSql(String s, ParsedProgram parsed) {
        parsed.needsSql = true;
        // Strip EXEC SQL ... END-EXEC wrapper for the stub call.
        String sql = s.replaceAll("(?i)^EXEC\\s+SQL\\s*", "")
                .replaceAll("(?i)\\s*END-EXEC\\s*$", "")
                .trim();
        if (sql.isEmpty()) sql = s;
        String lit = sql.replace("\\\\", "\\\\\\\\").replace("\"", "\\\\\"");
        return List.of("try { __sqlExec(\"" + lit + "\"); } catch (UnsupportedOperationException __sqlEx) { throw __sqlEx; }");
    }

    private static List<String> translateExecDli(String s, ParsedProgram parsed) {
        parsed.needsIms = true;
        String u = s.toUpperCase(Locale.ROOT);
        Matcher gu = Pattern.compile("(?i)EXEC\\s+DLI\\s+GU\\s+.*?PCB\\s*\\(\\s*([A-Z0-9-]+)\\s*\\).*INTO\\s*\\(\\s*([A-Z0-9-]+)\\s*\\)").matcher(s);
        if (gu.find()) {
            return List.of(toJavaIdent(gu.group(2)) + " = __imsGu(\"" + gu.group(1).toUpperCase(Locale.ROOT) + "\");");
        }
        Matcher gn = Pattern.compile("(?i)EXEC\\s+DLI\\s+GN\\s+.*?PCB\\s*\\(\\s*([A-Z0-9-]+)\\s*\\).*INTO\\s*\\(\\s*([A-Z0-9-]+)\\s*\\)").matcher(s);
        if (gn.find()) {
            return List.of(toJavaIdent(gn.group(2)) + " = __imsGn(\"" + gn.group(1).toUpperCase(Locale.ROOT) + "\");");
        }
        Matcher isrt = Pattern.compile("(?i)EXEC\\s+DLI\\s+ISRT\\s+.*?PCB\\s*\\(\\s*([A-Z0-9-]+)\\s*\\).*FROM\\s*\\(\\s*([A-Z0-9-]+)\\s*\\)").matcher(s);
        if (isrt.find()) {
            return List.of("__imsIsrt(\"" + isrt.group(1).toUpperCase(Locale.ROOT) + "\", String.valueOf("
                    + toJavaIdent(isrt.group(2)) + "));");
        }
        if (u.contains(" REPL ")) {
            Matcher repl = Pattern.compile("(?i)PCB\\s*\\(\\s*([A-Z0-9-]+)\\s*\\).*FROM\\s*\\(\\s*([A-Z0-9-]+)\\s*\\)").matcher(s);
            if (repl.find()) {
                return List.of("__imsRepl(\"" + repl.group(1).toUpperCase(Locale.ROOT) + "\", String.valueOf("
                        + toJavaIdent(repl.group(2)) + "));");
            }
        }
        if (u.contains(" DLET ")) {
            Matcher dlet = Pattern.compile("(?i)PCB\\s*\\(\\s*([A-Z0-9-]+)\\s*\\)").matcher(s);
            if (dlet.find()) {
                return List.of("__imsDlet(\"" + dlet.group(1).toUpperCase(Locale.ROOT) + "\");");
            }
        }
        String gap = "EXEC DLI (unsupported subset): " + s;
        if (!parsed.gaps.contains(gap)) parsed.gaps.add(gap);
        return List.of("// " + gap);
    }

    private static List<String> translateSortMerge(String s, ParsedProgram parsed) {
        parsed.needsSort = true;
        Matcher m = Pattern.compile(
                "(?i)^(?:SORT|MERGE)\\s+\\S+(?:\\s+ON\\s+.+?)?\\s+USING\\s+([A-Z0-9-]+)\\s+GIVING\\s+([A-Z0-9-]+)")
                .matcher(s);
        if (m.find()) {
            String inDd = resolveAssign(m.group(1), parsed);
            String outDd = resolveAssign(m.group(2), parsed);
            String verb = s.toUpperCase(Locale.ROOT).startsWith("MERGE") ? "MERGE" : "SORT";
            return List.of(
                    "try { __sortLines(\"" + inDd + "\", \"" + outDd + "\"); } catch (Exception __ex) { throw new RuntimeException(\""
                            + verb + "\", __ex); }");
        }
        String gap = "unsupported verb: " + (s.toUpperCase(Locale.ROOT).startsWith("MERGE") ? "MERGE" : "SORT")
                + " (need USING … GIVING)";
        if (!parsed.gaps.contains(gap)) parsed.gaps.add(gap);
        return List.of("// " + gap);
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
            String start = thru.group(1).toUpperCase(Locale.ROOT);
            String end = thru.group(2).toUpperCase(Locale.ROOT);
            int i0 = -1, i1 = -1;
            for (int i = 0; i < parsed.paragraphs.size(); i++) {
                String n = parsed.paragraphs.get(i).name;
                if (n.equals(start)) i0 = i;
                if (n.equals(end)) i1 = i;
            }
            if (i0 >= 0 && i1 >= i0) {
                List<String> out = new ArrayList<>();
                for (int i = i0; i <= i1; i++) {
                    out.add(toCamel(parsed.paragraphs.get(i).name) + "();");
                }
                return out;
            }
            String gap = "PERFORM THRU unresolved range: " + start + " THRU " + end;
            if (!parsed.gaps.contains(gap)) parsed.gaps.add(gap);
            return List.of(toCamel(start) + "(); // " + gap);
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
        String subject = null;

        for (String line : lines) {
            String t = stripPeriod(line.trim());
            String u = t.toUpperCase(Locale.ROOT);
            if (u.startsWith("EVALUATE ")) {
                subject = t.substring("EVALUATE".length()).trim();
                if (subject.equalsIgnoreCase("TRUE") || subject.equalsIgnoreCase("FALSE")) {
                    subject = null; // WHEN clauses are full conditions
                }
                continue;
            }
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
                String whenExpr = t.substring(5).trim();
                if (subject != null) {
                    currentCond = rewriteCondition(subject + " = " + whenExpr);
                } else {
                    currentCond = rewriteCondition(whenExpr);
                }
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

    /** Best-effort display length for PIC X/A/9 clauses (record buffer sizing). */
    static int picByteLength(String pic) {
        if (pic == null || pic.isBlank()) return 0;
        String p = pic.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        int len = 0;
        Matcher m = Pattern.compile("([XA9VN])(?:\\((\\d+)\\))?").matcher(p);
        while (m.find()) {
            len += m.group(2) != null ? Integer.parseInt(m.group(2)) : 1;
        }
        return len;
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
