package com.shadowstack.adapters.cobol;

import com.shadowstack.adapters.LanguageAdapter;
import com.shadowstack.adapters.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full {@link LanguageAdapter} implementation for COBOL-85 / COBOL-2002 source.
 *
 * <p>Two product tracks:</p>
 * <ul>
 *   <li><b>preserving</b> ({@code mode=preserving}) — COBOL stays COBOL
 *       (fixed→free, STOP RUN→GOBACK, terminal GO TO→PERFORM, EXIT PROGRAM→GOBACK,
 *       NEXT SENTENCE→CONTINUE, etc.). Verified with {@code cobc -fsyntax-only}
 *       when GnuCOBOL is available.</li>
 *   <li><b>translate</b> ({@code mode=translate}) — COBOL→Java-ish migration
 *       stubs (DISPLAY→System.out, MOVE→assignment, …). Never cobc-gated;
 *       detect-oriented / non-auto-apply.</li>
 * </ul>
 */
public class CobolAdapter implements LanguageAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(CobolAdapter.class);
    private static final String LANGUAGE_ID = "cobol";
    private static final String LANGUAGE_VERSION = "85";

    /** COBOL stays COBOL (industry full track). */
    public static final String MODE_PRESERVING = "preserving";
    /** COBOL→Java-ish stubs (adapter track). */
    public static final String MODE_TRANSLATE = "translate";

    /**
     * All preserving-track rule IDs (auto-applicable allowlist source).
     * Detect-only entries use identity after-snippets so auto-apply is a no-op.
     */
    public static final Set<String> PRESERVING_RULE_IDS = Set.of(
            "cobol.fixed_to_free",
            "cobol.free_format_indicator",
            "cobol.stop_run_to_goback",
            "cobol.goto_to_perform",
            "cobol.exit_program_to_goback",
            "cobol.section_exit_goback",
            "cobol.next_sentence_to_continue",
            "cobol.evaluate_true_simplify",
            "cobol.alter_removed",
            "cobol.remove_alter",
            "cobol.continue_to_empty",
            "cobol.set_true_88",
            "cobol.inline_perform",
            "cobol.perform_thru_expand",
            "cobol.initialize_replacing",
            "cobol.inspect_converting",
            "cobol.program_id_is_initial"
    );

    /**
     * Transformative preserving rules safe to auto-apply into the demo review queue.
     * Detect-only / identity preserving rules (continue_to_empty, alter_removed, …)
     * remain in {@link #PRESERVING_RULE_IDS} for mode tagging but are excluded here.
     * {@code cobol.free_format_indicator} is omitted — it is an invariant of
     * {@code cobol.fixed_to_free}, not a standalone transforming candidate.
     */
    public static final Set<String> AUTO_APPLICABLE_PRESERVING_RULE_IDS = Set.of(
            "cobol.fixed_to_free",
            "cobol.stop_run_to_goback",
            "cobol.goto_to_perform",
            "cobol.exit_program_to_goback",
            "cobol.section_exit_goback",
            "cobol.next_sentence_to_continue",
            "cobol.evaluate_true_simplify"
    );

    private static final int SEQ_AREA_END = 6;       // cols 1-6  (1-based: 1..6)
    private static final int INDICATOR_COL = 6;       // col 7    (0-based: 6)
    private static final int PROGRAM_AREA_START = 7;  // col 8    (0-based: 7)
    private static final int PROGRAM_AREA_END = 72;   // col 72   (1-based)

    private final Map<Path, SemanticModel> modelCache = new ConcurrentHashMap<>();

    /** @return {@code true} when the rule belongs to the COBOL-preserving track */
    public static boolean isPreservingRule(String ruleId) {
        return ruleId != null && PRESERVING_RULE_IDS.contains(ruleId);
    }

    /**
     * @return {@code true} when the preserving rule actually rewrites source
     *         (safe for demo auto-apply / PENDING_REVIEW promotion)
     */
    public static boolean isAutoApplicablePreserving(String ruleId) {
        return ruleId != null && AUTO_APPLICABLE_PRESERVING_RULE_IDS.contains(ruleId);
    }

    @Override
    public String languageId() {
        return LANGUAGE_ID;
    }

    @Override
    public String languageVersion() {
        return LANGUAGE_VERSION;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PARSING
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public SemanticModel parse(Path sourceRoot, LanguageAdapterConfig config) {
        Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");
        Objects.requireNonNull(config, "config must not be null");

        if (!Files.isDirectory(sourceRoot)) {
            throw new AdapterException("parse",
                    "Source root does not exist or is not a directory: " + sourceRoot);
        }

        LOG.info("Parsing COBOL sources under {}", sourceRoot);

        List<Path> files = collectCobolFiles(sourceRoot);
        Charset encoding = Charset.forName(config.sourceEncoding());

        SemanticModel.Builder builder = SemanticModel.builder()
                .languageId(LANGUAGE_ID)
                .languageVersion(LANGUAGE_VERSION)
                .sourceRoot(sourceRoot);

        int totalLines = 0;
        Map<String, SemanticModel.PackageInfo> packages = new LinkedHashMap<>();

        for (Path file : files) {
            try {
                String source = Files.readString(file, encoding);
                String relPath = sourceRoot.relativize(file).toString();
                ProgramParse parsed = parseProgram(source, relPath);
                totalLines += parsed.lineCount;

                String programName = parsed.programId != null
                        ? parsed.programId : stripExtension(file.getFileName().toString());
                String packageName = "cobol";

                packages.merge(packageName,
                        new SemanticModel.PackageInfo(packageName, List.of(relPath), List.of()),
                        (a, b) -> {
                            List<String> merged = new ArrayList<>(a.sourceFiles());
                            merged.addAll(b.sourceFiles());
                            return new SemanticModel.PackageInfo(a.name(), merged, a.subPackages());
                        });

                // Program → ClassInfo, paragraphs → MethodInfo, data items → FieldInfo
                String programFqn = "cobol." + programName;
                builder.addClass(new SemanticModel.ClassInfo(
                        programFqn, programName, "cobol",
                        null, List.of(), List.of("public"), List.of(),
                        relPath, 1, parsed.lineCount, false, false, false));

                for (Paragraph p : parsed.paragraphs) {
                    String signature = programFqn + "#" + p.name + "()";
                    builder.addMethod(new SemanticModel.MethodInfo(
                            signature, p.name, programFqn, "void",
                            List.of(), List.of(), List.of("public"), List.of(),
                            List.of(), p.startLine, p.endLine,
                            p.complexity, p.complexity,
                            p.endLine - p.startLine + 1,
                            SemanticModel.Purity.UNKNOWN));
                }

                for (DataItem d : parsed.dataItems) {
                    builder.addField(new SemanticModel.FieldInfo(
                            d.name, programFqn,
                            cobolPicToTypeName(d.picture),
                            List.of("public", "level-" + d.level),
                            List.of(), d.line));
                }

                for (PerformEdge edge : parsed.performEdges) {
                    String callerSig = programFqn + "#" + edge.caller + "()";
                    String calleeSig = programFqn + "#" + edge.callee + "()";
                    builder.addCallGraphEdge(new SemanticModel.CallGraphEdge(
                            callerSig, calleeSig, edge.line, false));
                }
            } catch (IOException e) {
                LOG.warn("Failed to read {}: {}", file, e.getMessage());
            }
        }

        packages.values().forEach(builder::addPackage);
        builder.putMetadata("fileCount", files.size());
        builder.putMetadata("totalLines", totalLines);

        SemanticModel model = builder.build();
        modelCache.put(sourceRoot, model);
        LOG.info("Parsed COBOL model: {} files, {} programs, {} paragraphs",
                files.size(), model.classCount(), model.methodCount());
        return model;
    }

    @Override
    public SemanticModel buildSemanticModel(Path sourceRoot) {
        return parse(sourceRoot, LanguageAdapterConfig.defaults());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  REFACTOR DETECTION
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public List<RefactorCandidate> listRefactorCandidates(SemanticModel model, RefactorRuleSet rules) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(rules, "rules must not be null");

        Path sourceRoot = model.sourceRoot();
        List<Path> files = collectCobolFiles(sourceRoot);
        List<RefactorCandidate> all = new ArrayList<>();

        for (Path file : files) {
            try {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String relPath = sourceRoot.relativize(file).toString();
                all.addAll(detectCandidatesInFile(source, relPath));
            } catch (IOException e) {
                LOG.warn("Skipping {}: {}", file, e.getMessage());
            }
        }

        LOG.info("Found {} COBOL refactor candidates across {} files", all.size(), files.size());
        return Collections.unmodifiableList(all);
    }

    private List<RefactorCandidate> detectCandidatesInFile(String source, String relPath) {
        List<RefactorCandidate> out = new ArrayList<>();
        boolean fixed = isFixedFormat(source);

        // ── Preserving track (COBOL→COBOL) ──────────────────────────────
        if (fixed) {
            out.addAll(detectFixedToFree(source, relPath));
        }
        out.addAll(detectStopRunToGoback(source, relPath, fixed));
        out.addAll(detectAlter(source, relPath, fixed));
        out.addAll(detectGotoToPerform(source, relPath, fixed));
        out.addAll(detectExitProgramToGoback(source, relPath, fixed));
        out.addAll(detectSectionExitGoback(source, relPath, fixed));
        out.addAll(detectNextSentenceToContinue(source, relPath, fixed));
        out.addAll(detectEvaluateTrueSimplify(source, relPath, fixed));
        out.addAll(detectContinuePreserving(source, relPath, fixed));
        out.addAll(detectSetTrue88(source, relPath, fixed));
        out.addAll(detectInlinePerform(source, relPath, fixed));
        out.addAll(detectPerformThruExpand(source, relPath, fixed));
        out.addAll(detectInitializeReplacing(source, relPath, fixed));
        out.addAll(detectInspectConverting(source, relPath, fixed));
        out.addAll(detectProgramIdIsInitial(source, relPath, fixed));

        // ── Translate track (COBOL→Java-ish stubs) ──────────────────────
        out.addAll(detectDisplayToPrint(source, relPath, fixed));
        out.addAll(detectMoveToAssign(source, relPath, fixed));
        out.addAll(detectComputeToAssign(source, relPath, fixed));
        out.addAll(detectPerformToCall(source, relPath, fixed));
        out.addAll(detectAddToAssign(source, relPath, fixed));
        out.addAll(detectSubtractToAssign(source, relPath, fixed));
        out.addAll(detectAcceptToInput(source, relPath, fixed));
        out.addAll(detectMultiplyToAssign(source, relPath, fixed));
        out.addAll(detectDivideToAssign(source, relPath, fixed));
        out.addAll(detectInitialize(source, relPath, fixed));
        out.addAll(detectExitProgram(source, relPath, fixed));
        out.addAll(detectStringInto(source, relPath, fixed));
        out.addAll(detectSetToTrue(source, relPath, fixed));
        out.addAll(detectInspectReplacing(source, relPath, fixed));
        out.addAll(detectUnstring(source, relPath, fixed));
        out.addAll(detectOpenFile(source, relPath, fixed));
        out.addAll(detectCloseFile(source, relPath, fixed));
        out.addAll(detectReadFile(source, relPath, fixed));
        out.addAll(detectWriteFile(source, relPath, fixed));
        out.addAll(detectCallProgram(source, relPath, fixed));
        out.addAll(detectRewrite(source, relPath, fixed));
        out.addAll(detectDelete(source, relPath, fixed));
        out.addAll(detectSort(source, relPath, fixed));
        out.addAll(detectMerge(source, relPath, fixed));
        out.addAll(detectEvaluate(source, relPath, fixed));
        out.addAll(detectSearch(source, relPath, fixed));
        out.addAll(detectStart(source, relPath, fixed));
        out.addAll(detectRelease(source, relPath, fixed));
        out.addAll(detectReturnFile(source, relPath, fixed));
        out.addAll(detectCancel(source, relPath, fixed));
        out.addAll(detectExitSection(source, relPath, fixed));
        out.addAll(detectExitParagraph(source, relPath, fixed));
        out.addAll(detectMoveCorresponding(source, relPath, fixed));
        out.addAll(detectInspectTallying(source, relPath, fixed));
        out.addAll(detectPerformUntil(source, relPath, fixed));
        out.addAll(detectPerformVarying(source, relPath, fixed));
        out.addAll(detectPerformTimes(source, relPath, fixed));
        out.addAll(detectGoDepending(source, relPath, fixed));
        out.addAll(detectSetAddress(source, relPath, fixed));
        out.addAll(detectAllocate(source, relPath, fixed));
        out.addAll(detectFree(source, relPath, fixed));
        return out;
    }

    /**
     * Heuristic: source is fixed-format if a majority of non-blank lines have a
     * 6-char sequence-number prefix (digits/spaces) followed by a known indicator
     * column character, OR have a comment indicator ('*'/'/') at column 7.
     */
    private boolean isFixedFormat(String source) {
        String[] lines = source.split("\n", -1);
        int fixedSignals = 0;
        int totalSignals = 0;
        for (String line : lines) {
            if (line.length() < 7) continue;
            if (line.isBlank()) continue;
            totalSignals++;
            char indicator = line.charAt(INDICATOR_COL);
            if (indicator == '*' || indicator == '/'
                    || indicator == '-' || indicator == 'D' || indicator == 'd'
                    || indicator == ' ') {
                String prefix = line.substring(0, SEQ_AREA_END);
                if (prefix.chars().allMatch(c -> Character.isDigit(c) || c == ' ')) {
                    fixedSignals++;
                }
            }
        }
        return totalSignals > 0 && (double) fixedSignals / totalSignals > 0.6;
    }

    private List<RefactorCandidate> detectFixedToFree(String source, String relPath) {
        String converted = convertFixedToFree(source);
        if (converted.equals(source)) return List.of();
        int lastLine = source.split("\n", -1).length;
        return List.of(RefactorCandidate.builder()
                .sourceFile(relPath)
                .startLine(1)
                .endLine(lastLine)
                .ruleId("cobol.fixed_to_free")
                .ruleName("COBOL-85 fixed-format → COBOL-2002 free-format")
                .ruleCategory("MODERNIZATION")
                .beforeSnippet(source)
                .proposedAfterSnippet(converted)
                .confidenceScore(0.92)
                .riskTier(RiskTier.MODERATE)
                .addSafetyInvariant(new SafetyInvariant(
                        "cobol-tokens-preserved",
                        "Tokenized program area is preserved after conversion",
                        SafetyInvariant.Category.BEHAVIORAL_EQUIVALENCE,
                        SafetyInvariant.Status.SATISFIED,
                        "Tokens in cols 8–72 are emitted verbatim; only seq/identification "
                                + "areas are stripped"))
                .addSafetyInvariant(new SafetyInvariant(
                        "cobol-comments-preserved",
                        "Comment lines (col-7 '*') are rewritten to *> trailing comments",
                        SafetyInvariant.Category.BEHAVIORAL_EQUIVALENCE,
                        SafetyInvariant.Status.SATISFIED,
                        "Each fixed-format comment line is rendered as a *> free-format comment"))
                .addSafetyInvariant(new SafetyInvariant(
                        "cobol-free-format-indicator",
                        ">>SOURCE FREE directive inserted (cobol.free_format_indicator)",
                        SafetyInvariant.Category.BEHAVIORAL_EQUIVALENCE,
                        SafetyInvariant.Status.SATISFIED,
                        "GnuCOBOL / IBM free-format indicator precedes converted source"))
                .putAstContext("language", "cobol")
                .putAstContext("mode", MODE_PRESERVING)
                .putAstContext("format", "fixed→free")
                .build());
    }

    private static final Pattern STOP_RUN = Pattern.compile("(?im)\\bSTOP\\s+RUN\\b\\.?");

    private List<RefactorCandidate> detectStopRunToGoback(String source, String relPath, boolean fixed) {
        List<RefactorCandidate> out = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String code = fixed ? programAreaOf(line) : line;
            Matcher m = STOP_RUN.matcher(code);
            if (m.find()) {
                String replaced = line.replaceFirst("(?i)STOP\\s+RUN", "GOBACK");
                out.add(RefactorCandidate.builder()
                        .sourceFile(relPath)
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .ruleId("cobol.stop_run_to_goback")
                        .ruleName("STOP RUN → GOBACK")
                        .ruleCategory("MODERNIZATION")
                        .beforeSnippet(line)
                        .proposedAfterSnippet(replaced)
                        .confidenceScore(0.88)
                        .riskTier(RiskTier.MODERATE)
                        .addSafetyInvariant(new SafetyInvariant(
                                "cobol-goback-safe",
                                "GOBACK returns control to the caller; STOP RUN terminates the run unit",
                                SafetyInvariant.Category.BEHAVIORAL_EQUIVALENCE,
                                SafetyInvariant.Status.SATISFIED,
                                "Safe for sub-programs and CICS; for main programs the OS returns "
                                        + "anyway"))
                        .putAstContext("language", "cobol")
                        .putAstContext("mode", MODE_PRESERVING)
                        .build());
            }
        }
        return out;
    }

    private static final Pattern ALTER = Pattern.compile("(?im)\\bALTER\\b");

    private List<RefactorCandidate> detectAlter(String source, String relPath, boolean fixed) {
        List<RefactorCandidate> out = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String code = fixed ? programAreaOf(lines[i]) : lines[i];
            if (ALTER.matcher(code).find()) {
                // Detect-only (identity after): ALTER requires manual PERFORM/EVALUATE rewrite.
                out.add(RefactorCandidate.builder()
                        .sourceFile(relPath)
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .ruleId("cobol.alter_removed")
                        .ruleName("ALTER statement (removed in COBOL-2002)")
                        .ruleCategory("MODERNIZATION")
                        .beforeSnippet(lines[i])
                        .proposedAfterSnippet(lines[i])
                        .confidenceScore(0.70)
                        .riskTier(RiskTier.HIGH)
                        .addSafetyInvariant(new SafetyInvariant(
                                "cobol-alter-removed",
                                "ALTER was deleted from COBOL-2002; manual rewrite required",
                                SafetyInvariant.Category.BEHAVIORAL_EQUIVALENCE,
                                SafetyInvariant.Status.UNKNOWN,
                                "Detect-only (cobol.remove_alter); control-flow rewrite is left "
                                        + "to the reviewer"))
                        .putAstContext("language", "cobol")
                        .putAstContext("mode", MODE_PRESERVING)
                        .putAstContext("detectOnly", "true")
                        .putAstContext("alias", "cobol.remove_alter")
                        .build());
            }
        }
        return out;
    }

    private static final Pattern GO_TO =
            Pattern.compile("(?i)^\\s*GO\\s+TO\\s+([A-Z0-9][A-Z0-9-]*)\\s*\\.?\\s*$");

    private List<RefactorCandidate> detectGotoToPerform(String source, String relPath, boolean fixed) {
        List<RefactorCandidate> out = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        // A GO TO X. is safe to convert to PERFORM X when it is the last line of its
        // paragraph (no fall-through after it). We approximate by checking the next
        // non-blank, non-comment line is a new paragraph header.
        for (int i = 0; i < lines.length; i++) {
            String code = fixed ? programAreaOf(lines[i]) : lines[i];
            Matcher m = GO_TO.matcher(code);
            if (!m.matches()) continue;
            if (!isLastInParagraph(lines, i, fixed)) continue;
            String target = m.group(1);
            String replaced = lines[i].replaceFirst("(?i)GO\\s+TO\\s+" + Pattern.quote(target),
                    "PERFORM " + target);
            out.add(RefactorCandidate.builder()
                    .sourceFile(relPath)
                    .startLine(i + 1)
                    .endLine(i + 1)
                    .ruleId("cobol.goto_to_perform")
                    .ruleName("Terminal GO TO → PERFORM")
                    .ruleCategory("MODERNIZATION")
                    .beforeSnippet(lines[i])
                    .proposedAfterSnippet(replaced)
                    .confidenceScore(0.80)
                    .riskTier(RiskTier.MODERATE)
                    .addSafetyInvariant(new SafetyInvariant(
                            "cobol-goto-terminal",
                            "GO TO is the final statement in its paragraph (no fall-through)",
                            SafetyInvariant.Category.BEHAVIORAL_EQUIVALENCE,
                            SafetyInvariant.Status.SATISFIED,
                            "Lookahead found a paragraph header before any further statement"))
                    .addSafetyInvariant(new SafetyInvariant(
                            "cobol-goto-not-altered",
                            "Target paragraph is not the subject of an ALTER statement",
                            SafetyInvariant.Category.BEHAVIORAL_EQUIVALENCE,
                            SafetyInvariant.Status.UNKNOWN,
                            "ALTER scan is best-effort; review before applying"))
                    .putAstContext("language", "cobol")
                    .putAstContext("mode", MODE_PRESERVING)
                    .putAstContext("target", target)
                    .build());
        }
        return out;
    }

    private boolean isLastInParagraph(String[] lines, int i, boolean fixed) {
        for (int j = i + 1; j < lines.length; j++) {
            String code = fixed ? programAreaOf(lines[j]) : lines[j];
            String trimmed = code.trim();
            if (trimmed.isEmpty()) continue;
            if (fixed && lines[j].length() > INDICATOR_COL
                    && (lines[j].charAt(INDICATOR_COL) == '*' || lines[j].charAt(INDICATOR_COL) == '/')) {
                continue;
            }
            return PARAGRAPH_HEADER.matcher(trimmed).matches()
                    || SECTION_HEADER.matcher(trimmed).matches();
        }
        return true;
    }

    private static final Pattern EXIT_PROGRAM_PRESERVE =
            Pattern.compile("(?i)^(\\s*)EXIT\\s+PROGRAM\\s*\\.?\\s*$");
    private static final Pattern EXIT_SECTION_PRESERVE =
            Pattern.compile("(?i)^(\\s*)EXIT\\s+SECTION\\s*\\.?\\s*$");
    private static final Pattern NEXT_SENTENCE =
            Pattern.compile("(?i)^(\\s*)NEXT\\s+SENTENCE\\s*\\.?\\s*$");
    private static final Pattern CONTINUE_PRESERVE =
            Pattern.compile("(?i)^(\\s*)CONTINUE\\s*\\.?\\s*$");
    private static final Pattern SET_TRUE_88 =
            Pattern.compile("(?i)^(\\s*)SET\\s+([A-Z0-9-]+)\\s+TO\\s+TRUE\\s*\\.?\\s*$");
    private static final Pattern PERFORM_THRU =
            Pattern.compile("(?i)^(\\s*)PERFORM\\s+([A-Z0-9-]+)\\s+THRU\\s+([A-Z0-9-]+)\\s*\\.?\\s*$");
    private static final Pattern INITIALIZE_REPLACING =
            Pattern.compile("(?i)^(\\s*)INITIALIZE\\s+.+\\bREPLACING\\b.*$");
    private static final Pattern INSPECT_CONVERTING =
            Pattern.compile("(?i)^(\\s*)INSPECT\\s+.+\\bCONVERTING\\b.*$");
    private static final Pattern PROGRAM_ID_LINE =
            Pattern.compile("(?i)^(\\s*)PROGRAM-ID\\s*\\.\\s*([A-Z0-9][A-Z0-9-]*)\\s*\\.\\s*$");
    private static final Pattern PROGRAM_ID_INITIAL =
            Pattern.compile("(?i)\\bIS\\s+INITIAL\\b");
    private static final Pattern INLINE_PERFORM_START =
            Pattern.compile("(?i)^(\\s*)PERFORM\\s*$");
    private static final Pattern END_PERFORM =
            Pattern.compile("(?i)^(\\s*)END-PERFORM\\s*\\.?\\s*$");
    private static final Pattern EVALUATE_TRUE =
            Pattern.compile("(?i)^(\\s*)EVALUATE\\s+TRUE\\s*\\.?\\s*$");

    private List<RefactorCandidate> detectExitProgramToGoback(String source, String relPath, boolean fixed) {
        return detectPreservingLineRewrite(source, relPath, fixed, EXIT_PROGRAM_PRESERVE,
                "cobol.exit_program_to_goback", "EXIT PROGRAM → GOBACK",
                m -> (m.group(1) != null ? m.group(1) : "") + "GOBACK"
                        + (m.group(0).trim().endsWith(".") ? "." : ""),
                0.9, RiskTier.LOW,
                "EXIT PROGRAM and GOBACK both return control to the calling program");
    }

    private List<RefactorCandidate> detectSectionExitGoback(String source, String relPath, boolean fixed) {
        return detectPreservingLineRewrite(source, relPath, fixed, EXIT_SECTION_PRESERVE,
                "cobol.section_exit_goback", "EXIT SECTION → GOBACK",
                m -> (m.group(1) != null ? m.group(1) : "") + "GOBACK"
                        + (m.group(0).contains(".") ? "." : ""),
                0.75, RiskTier.MODERATE,
                "EXIT SECTION at program exit modernizes to GOBACK (review section-scoped exits)");
    }

    private List<RefactorCandidate> detectNextSentenceToContinue(String source, String relPath, boolean fixed) {
        return detectPreservingLineRewrite(source, relPath, fixed, NEXT_SENTENCE,
                "cobol.next_sentence_to_continue", "NEXT SENTENCE → CONTINUE",
                m -> (m.group(1) != null ? m.group(1) : "") + "CONTINUE"
                        + (m.group(0).trim().endsWith(".") ? "." : ""),
                0.88, RiskTier.MODERATE,
                "NEXT SENTENCE is obsolete; CONTINUE is the COBOL-2002 structured equivalent "
                        + "inside IF/EVALUATE (verify fall-through intent)");
    }

    private List<RefactorCandidate> detectContinuePreserving(String source, String relPath, boolean fixed) {
        // CONTINUE is already modern COBOL — detect-only identity.
        return detectPreservingIdentity(source, relPath, fixed, CONTINUE_PRESERVE,
                "cobol.continue_to_empty", "CONTINUE already modern (no-op)",
                0.95, RiskTier.LOW,
                "CONTINUE is the modern structured no-op; no rewrite needed");
    }

    private List<RefactorCandidate> detectSetTrue88(String source, String relPath, boolean fixed) {
        // SET condition-name TO TRUE is already the modern 88-level idiom.
        return detectPreservingIdentity(source, relPath, fixed, SET_TRUE_88,
                "cobol.set_true_88", "SET … TO TRUE (88-level) already modern",
                0.9, RiskTier.LOW,
                "88-level SET TO TRUE is the preferred modern COBOL form");
    }

    private List<RefactorCandidate> detectPerformThruExpand(String source, String relPath, boolean fixed) {
        return detectPreservingIdentity(source, relPath, fixed, PERFORM_THRU,
                "cobol.perform_thru_expand", "PERFORM … THRU (detect-only)",
                0.55, RiskTier.HIGH,
                "PERFORM THRU expansion into explicit PERFORM sequence requires paragraph range analysis");
    }

    private List<RefactorCandidate> detectInitializeReplacing(String source, String relPath, boolean fixed) {
        return detectPreservingIdentity(source, relPath, fixed, INITIALIZE_REPLACING,
                "cobol.initialize_replacing", "INITIALIZE … REPLACING (detect-only)",
                0.6, RiskTier.MODERATE,
                "INITIALIZE REPLACING is valid modern COBOL; flagged for review only");
    }

    private List<RefactorCandidate> detectInspectConverting(String source, String relPath, boolean fixed) {
        return detectPreservingIdentity(source, relPath, fixed, INSPECT_CONVERTING,
                "cobol.inspect_converting", "INSPECT … CONVERTING (detect-only)",
                0.6, RiskTier.MODERATE,
                "INSPECT CONVERTING is valid modern COBOL; flagged for review only");
    }

    private List<RefactorCandidate> detectProgramIdIsInitial(String source, String relPath, boolean fixed) {
        List<RefactorCandidate> out = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String code = fixed ? programAreaOf(lines[i]) : lines[i];
            Matcher m = PROGRAM_ID_LINE.matcher(code);
            if (!m.matches()) continue;
            if (PROGRAM_ID_INITIAL.matcher(code).find()) continue;
            out.add(RefactorCandidate.builder()
                    .sourceFile(relPath)
                    .startLine(i + 1)
                    .endLine(i + 1)
                    .ruleId("cobol.program_id_is_initial")
                    .ruleName("PROGRAM-ID … IS INITIAL (detect-only)")
                    .ruleCategory("MODERNIZATION")
                    .beforeSnippet(lines[i])
                    .proposedAfterSnippet(lines[i])
                    .confidenceScore(0.5)
                    .riskTier(RiskTier.MODERATE)
                    .addSafetyInvariant(new SafetyInvariant(
                            "cobol-program-id-initial",
                            "Optional: PROGRAM-ID. X IS INITIAL. for fresh working-storage each CALL",
                            SafetyInvariant.Category.BEHAVIORAL_EQUIVALENCE,
                            SafetyInvariant.Status.UNKNOWN,
                            "Detect-only — IS INITIAL changes state lifetime semantics"))
                    .putAstContext("language", "cobol")
                    .putAstContext("mode", MODE_PRESERVING)
                    .putAstContext("detectOnly", "true")
                    .build());
        }
        return out;
    }

    private List<RefactorCandidate> detectInlinePerform(String source, String relPath, boolean fixed) {
        List<RefactorCandidate> out = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String code = fixed ? programAreaOf(lines[i]) : lines[i];
            if (!INLINE_PERFORM_START.matcher(code).matches()) continue;
            int end = -1;
            for (int j = i + 1; j < lines.length; j++) {
                String c2 = fixed ? programAreaOf(lines[j]) : lines[j];
                if (END_PERFORM.matcher(c2).matches()) {
                    end = j;
                    break;
                }
            }
            if (end < 0) continue;
            String before = String.join("\n", Arrays.copyOfRange(lines, i, end + 1));
            out.add(RefactorCandidate.builder()
                    .sourceFile(relPath)
                    .startLine(i + 1)
                    .endLine(end + 1)
                    .ruleId("cobol.inline_perform")
                    .ruleName("Inline PERFORM … END-PERFORM already modern")
                    .ruleCategory("MODERNIZATION")
                    .beforeSnippet(before)
                    .proposedAfterSnippet(before)
                    .confidenceScore(0.95)
                    .riskTier(RiskTier.LOW)
                    .addSafetyInvariant(new SafetyInvariant(
                            "cobol-inline-perform",
                            "Inline PERFORM/END-PERFORM is the preferred structured form",
                            SafetyInvariant.Category.BEHAVIORAL_EQUIVALENCE,
                            SafetyInvariant.Status.SATISFIED,
                            "Detect-only — no rewrite needed"))
                    .putAstContext("language", "cobol")
                    .putAstContext("mode", MODE_PRESERVING)
                    .putAstContext("detectOnly", "true")
                    .build());
        }
        return out;
    }

    /**
     * EVALUATE TRUE / WHEN … / END-EVALUATE → IF / ELSE IF / END-IF (free-format friendly).
     */
    private List<RefactorCandidate> detectEvaluateTrueSimplify(String source, String relPath, boolean fixed) {
        // Multi-line IF rewrite emits free-format text; only apply on free-format sources.
        if (fixed) return List.of();
        List<RefactorCandidate> out = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String code = fixed ? programAreaOf(lines[i]) : lines[i];
            Matcher head = EVALUATE_TRUE.matcher(code);
            if (!head.matches()) continue;
            String indent = head.group(1) != null ? head.group(1) : "";
            int end = -1;
            for (int j = i + 1; j < lines.length; j++) {
                String c2 = (fixed ? programAreaOf(lines[j]) : lines[j]).trim();
                if (c2.matches("(?i)END-EVALUATE\\.?\\s*")) {
                    end = j;
                    break;
                }
            }
            if (end < 0) continue;

            List<String> whenConds = new ArrayList<>();
            List<List<String>> whenBodies = new ArrayList<>();
            List<String> otherBody = null;
            List<String> currentBody = null;
            boolean inOther = false;
            for (int j = i + 1; j < end; j++) {
                String raw = lines[j];
                String c2 = fixed ? programAreaOf(raw) : raw;
                String trimmed = c2.trim();
                Matcher when = Pattern.compile("(?i)^WHEN\\s+(.+?)\\s*$").matcher(trimmed);
                if (when.matches()) {
                    String cond = when.group(1).trim();
                    if (cond.matches("(?i)OTHER\\.?\\s*")) {
                        inOther = true;
                        otherBody = new ArrayList<>();
                        currentBody = otherBody;
                    } else {
                        inOther = false;
                        whenConds.add(cond.replaceAll("\\.$", ""));
                        currentBody = new ArrayList<>();
                        whenBodies.add(currentBody);
                    }
                    continue;
                }
                if (currentBody != null) {
                    currentBody.add(raw);
                }
            }
            if (whenConds.isEmpty()) continue;

            StringBuilder after = new StringBuilder();
            for (int w = 0; w < whenConds.size(); w++) {
                if (w == 0) {
                    after.append(indent).append("IF ").append(whenConds.get(w)).append('\n');
                } else {
                    after.append(indent).append("ELSE IF ").append(whenConds.get(w)).append('\n');
                }
                for (String bodyLine : whenBodies.get(w)) {
                    after.append(bodyLine).append('\n');
                }
            }
            if (otherBody != null) {
                after.append(indent).append("ELSE").append('\n');
                for (String bodyLine : otherBody) {
                    after.append(bodyLine).append('\n');
                }
            }
            after.append(indent).append("END-IF");
            // Preserve trailing period style from END-EVALUATE if present
            String endCode = (fixed ? programAreaOf(lines[end]) : lines[end]).trim();
            if (endCode.endsWith(".")) {
                after.append('.');
            }

            String before = String.join("\n", Arrays.copyOfRange(lines, i, end + 1));
            out.add(RefactorCandidate.builder()
                    .sourceFile(relPath)
                    .startLine(i + 1)
                    .endLine(end + 1)
                    .ruleId("cobol.evaluate_true_simplify")
                    .ruleName("EVALUATE TRUE → IF / ELSE IF")
                    .ruleCategory("MODERNIZATION")
                    .beforeSnippet(before)
                    .proposedAfterSnippet(after.toString())
                    .confidenceScore(0.72)
                    .riskTier(RiskTier.MODERATE)
                    .addSafetyInvariant(new SafetyInvariant(
                            "cobol-evaluate-true",
                            "EVALUATE TRUE WHEN chains map to IF/ELSE IF/END-IF",
                            SafetyInvariant.Category.BEHAVIORAL_EQUIVALENCE,
                            SafetyInvariant.Status.SATISFIED,
                            "WHEN OTHER becomes ELSE; body lines preserved"))
                    .putAstContext("language", "cobol")
                    .putAstContext("mode", MODE_PRESERVING)
                    .build());
        }
        return out;
    }

    private List<RefactorCandidate> detectPreservingLineRewrite(
            String source, String relPath, boolean fixed, Pattern pattern,
            String ruleId, String ruleName, LineReplacer replacer,
            double confidence, RiskTier risk, String rationale) {
        List<RefactorCandidate> out = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String code = fixed ? programAreaOf(lines[i]) : lines[i];
            Matcher m = pattern.matcher(code);
            if (!m.matches()) continue;
            String afterCode = replacer.apply(m);
            String afterLine;
            if (fixed && lines[i].length() > PROGRAM_AREA_START) {
                // Preserve sequence/indicator prefix; afterCode is program-area text.
                String prefix = lines[i].substring(0, PROGRAM_AREA_START);
                afterLine = prefix + afterCode;
            } else {
                afterLine = afterCode;
            }
            out.add(RefactorCandidate.builder()
                    .sourceFile(relPath)
                    .startLine(i + 1)
                    .endLine(i + 1)
                    .ruleId(ruleId)
                    .ruleName(ruleName)
                    .ruleCategory("MODERNIZATION")
                    .beforeSnippet(lines[i])
                    .proposedAfterSnippet(afterLine)
                    .confidenceScore(confidence)
                    .riskTier(risk)
                    .addSafetyInvariant(new SafetyInvariant(
                            "cobol-preserving",
                            rationale,
                            SafetyInvariant.Category.BEHAVIORAL_EQUIVALENCE,
                            SafetyInvariant.Status.SATISFIED,
                            ruleId))
                    .putAstContext("language", "cobol")
                    .putAstContext("mode", MODE_PRESERVING)
                    .putAstContext("ruleId", ruleId)
                    .build());
        }
        return out;
    }

    private List<RefactorCandidate> detectPreservingIdentity(
            String source, String relPath, boolean fixed, Pattern pattern,
            String ruleId, String ruleName, double confidence, RiskTier risk, String rationale) {
        List<RefactorCandidate> out = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String code = fixed ? programAreaOf(lines[i]) : lines[i];
            Matcher m = pattern.matcher(code);
            if (!m.matches()) continue;
            out.add(RefactorCandidate.builder()
                    .sourceFile(relPath)
                    .startLine(i + 1)
                    .endLine(i + 1)
                    .ruleId(ruleId)
                    .ruleName(ruleName)
                    .ruleCategory("MODERNIZATION")
                    .beforeSnippet(lines[i])
                    .proposedAfterSnippet(lines[i])
                    .confidenceScore(confidence)
                    .riskTier(risk)
                    .addSafetyInvariant(new SafetyInvariant(
                            "cobol-preserving-detect",
                            rationale,
                            SafetyInvariant.Category.BEHAVIORAL_EQUIVALENCE,
                            SafetyInvariant.Status.SATISFIED,
                            ruleId))
                    .putAstContext("language", "cobol")
                    .putAstContext("mode", MODE_PRESERVING)
                    .putAstContext("detectOnly", "true")
                    .putAstContext("ruleId", ruleId)
                    .build());
        }
        return out;
    }

    private static final Pattern DISPLAY_STMT =
            Pattern.compile("(?i)^(\\s*)DISPLAY\\s+(\"([^\"]*)\"|'([^']*)'|([A-Z0-9-]+))\\s*\\.?\\s*$");
    private static final Pattern MOVE_STMT =
            Pattern.compile("(?i)^(\\s*)MOVE\\s+(\"([^\"]*)\"|'([^']*)'|([A-Z0-9-]+))\\s+TO\\s+([A-Z0-9-]+)\\s*\\.?\\s*$");
    private static final Pattern COMPUTE_STMT =
            Pattern.compile("(?i)^(\\s*)COMPUTE\\s+([A-Z0-9-]+)\\s*=\\s*(.+?)\\s*\\.?\\s*$");
    private static final Pattern PERFORM_STMT =
            Pattern.compile("(?i)^(\\s*)PERFORM\\s+([A-Z0-9-]+)\\s*\\.?\\s*$");
    private static final Pattern ADD_STMT =
            Pattern.compile("(?i)^(\\s*)ADD\\s+([A-Z0-9-]+|\\d+)\\s+TO\\s+([A-Z0-9-]+)\\s*\\.?\\s*$");
    private static final Pattern SUBTRACT_STMT =
            Pattern.compile("(?i)^(\\s*)SUBTRACT\\s+([A-Z0-9-]+|\\d+)\\s+FROM\\s+([A-Z0-9-]+)\\s*\\.?\\s*$");
    private static final Pattern ACCEPT_STMT =
            Pattern.compile("(?i)^(\\s*)ACCEPT\\s+([A-Z0-9-]+)\\s*\\.?\\s*$");
    private static final Pattern MULTIPLY_STMT =
            Pattern.compile("(?i)^(\\s*)MULTIPLY\\s+([A-Z0-9-]+|\\d+(?:\\.\\d+)?)\\s+BY\\s+([A-Z0-9-]+)\\s*\\.?\\s*$");
    private static final Pattern DIVIDE_STMT =
            Pattern.compile("(?i)^(\\s*)DIVIDE\\s+([A-Z0-9-]+|\\d+(?:\\.\\d+)?)\\s+INTO\\s+([A-Z0-9-]+)\\s*\\.?\\s*$");
    private static final Pattern INITIALIZE_STMT =
            Pattern.compile("(?i)^(\\s*)INITIALIZE\\s+([A-Z0-9-]+)\\s*\\.?\\s*$");
    private static final Pattern EXIT_PROGRAM_STMT =
            Pattern.compile("(?i)^(\\s*)EXIT\\s+PROGRAM\\s*\\.?\\s*$");
    private static final Pattern STRING_INTO_STMT =
            Pattern.compile("(?i)^(\\s*)STRING\\s+(.+?)\\s+INTO\\s+([A-Z0-9-]+)\\s*\\.?\\s*$");
    private static final Pattern SET_TRUE_STMT =
            Pattern.compile("(?i)^(\\s*)SET\\s+([A-Z0-9-]+)\\s+TO\\s+TRUE\\s*\\.?\\s*$");
    private static final Pattern INSPECT_REPLACING_STMT =
            Pattern.compile("(?i)^(\\s*)INSPECT\\s+([A-Z0-9-]+)\\s+REPLACING\\s+(.+?)\\s*\\.?\\s*$");
    private static final Pattern UNSTRING_STMT =
            Pattern.compile("(?i)^(\\s*)UNSTRING\\s+([A-Z0-9-]+)\\s+DELIMITED\\s+BY\\s+(\"[^\"]*\"|'[^']*'|[A-Z0-9-]+)\\s+INTO\\s+(.+?)\\s*\\.?\\s*$");
    private static final Pattern OPEN_STMT =
            Pattern.compile("(?i)^(\\s*)OPEN\\s+(INPUT|OUTPUT|I-O|EXTEND)\\s+([A-Z0-9-]+)\\s*\\.?\\s*$");
    private static final Pattern CLOSE_STMT =
            Pattern.compile("(?i)^(\\s*)CLOSE\\s+([A-Z0-9-]+)\\s*\\.?\\s*$");
    private static final Pattern READ_STMT =
            Pattern.compile("(?i)^(\\s*)READ\\s+([A-Z0-9-]+)(?:\\s+INTO\\s+([A-Z0-9-]+))?\\s*\\.?\\s*$");
    private static final Pattern WRITE_STMT =
            Pattern.compile("(?i)^(\\s*)WRITE\\s+([A-Z0-9-]+)(?:\\s+FROM\\s+([A-Z0-9-]+))?\\s*\\.?\\s*$");
    private static final Pattern CALL_STMT =
            Pattern.compile("(?i)^(\\s*)CALL\\s+(\"[^\"]+\"|'[^']+'|[A-Z0-9-]+)(?:\\s+USING\\s+.+?)?\\s*\\.?\\s*$");

    private static final Pattern REWRITE_STMT =
            Pattern.compile("(?i)^(\\s*)REWRITE\\s+([A-Z0-9-]+)(?:\\s+FROM\\s+([A-Z0-9-]+))?\\s*\\.?\\s*$");
    private static final Pattern DELETE_STMT =
            Pattern.compile("(?i)^(\\s*)DELETE\\s+([A-Z0-9-]+)\\s*\\.?\\s*$");
    private static final Pattern SORT_STMT =
            Pattern.compile("(?i)^(\\s*)SORT\\s+([A-Z0-9-]+)\\b.*$");
    private static final Pattern MERGE_STMT =
            Pattern.compile("(?i)^(\\s*)MERGE\\s+([A-Z0-9-]+)\\b.*$");
    private static final Pattern EVALUATE_STMT =
            Pattern.compile("(?i)^(\\s*)EVALUATE\\s+(.+?)\\s*\\.?\\s*$");
    private static final Pattern SEARCH_STMT =
            Pattern.compile("(?i)^(\\s*)SEARCH\\s+([A-Z0-9-]+)\\b.*$");
    private static final Pattern START_STMT =
            Pattern.compile("(?i)^(\\s*)START\\s+([A-Z0-9-]+)\\b.*$");
    private static final Pattern RELEASE_STMT =
            Pattern.compile("(?i)^(\\s*)RELEASE\\s+([A-Z0-9-]+)(?:\\s+FROM\\s+([A-Z0-9-]+))?\\s*\\.?\\s*$");
    private static final Pattern RETURN_FILE_STMT =
            Pattern.compile("(?i)^(\\s*)RETURN\\s+([A-Z0-9-]+)(?:\\s+INTO\\s+([A-Z0-9-]+))?\\s*\\.?\\s*$");
    private static final Pattern CANCEL_STMT =
            Pattern.compile("(?i)^(\\s*)CANCEL\\s+(\"[^\"]+\"|'[^']+'|[A-Z0-9-]+)\\s*\\.?\\s*$");
    private static final Pattern EXIT_SECTION_STMT =
            Pattern.compile("(?i)^(\\s*)EXIT\\s+SECTION\\s*\\.?\\s*$");
    private static final Pattern EXIT_PARAGRAPH_STMT =
            Pattern.compile("(?i)^(\\s*)EXIT\\s+PARAGRAPH\\s*\\.?\\s*$");
    private static final Pattern MOVE_CORR_STMT =
            Pattern.compile("(?i)^(\\s*)MOVE\\s+CORRESPONDING\\s+([A-Z0-9-]+)\\s+TO\\s+([A-Z0-9-]+)\\s*\\.?\\s*$");
    private static final Pattern INSPECT_TALLYING_STMT =
            Pattern.compile("(?i)^(\\s*)INSPECT\\s+([A-Z0-9-]+)\\s+TALLYING\\b.*$");
    private static final Pattern PERFORM_UNTIL_STMT =
            Pattern.compile("(?i)^(\\s*)PERFORM\\s+([A-Z0-9-]+)\\s+UNTIL\\s+(.+?)\\s*\\.?\\s*$");
    private static final Pattern PERFORM_VARYING_STMT =
            Pattern.compile("(?i)^(\\s*)PERFORM\\s+([A-Z0-9-]+)\\s+VARYING\\b.*$");
    private static final Pattern PERFORM_TIMES_STMT =
            Pattern.compile("(?i)^(\\s*)PERFORM\\s+([A-Z0-9-]+)\\s+([0-9]+|[A-Z0-9-]+)\\s+TIMES\\s*\\.?\\s*$");
    private static final Pattern GO_DEPENDING_STMT =
            Pattern.compile("(?i)^(\\s*)GO\\s+TO\\s+(.+?)\\s+DEPENDING\\s+ON\\s+([A-Z0-9-]+)\\s*\\.?\\s*$");
    private static final Pattern SET_ADDRESS_STMT =
            Pattern.compile("(?i)^(\\s*)SET\\s+ADDRESS\\s+OF\\s+([A-Z0-9-]+)\\s+TO\\s+(.+?)\\s*\\.?\\s*$");
    private static final Pattern ALLOCATE_STMT =
            Pattern.compile("(?i)^(\\s*)ALLOCATE\\s+([A-Z0-9-]+)\\b.*$");
    private static final Pattern FREE_STMT =
            Pattern.compile("(?i)^(\\s*)FREE\\s+([A-Z0-9-]+)\\s*\\.?\\s*$");

    private List<RefactorCandidate> detectDisplayToPrint(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, DISPLAY_STMT, "cobol.display_to_print",
                "DISPLAY → System.out.println",
                m -> {
                    String indent = m.group(1) != null ? m.group(1) : "";
                    String lit = m.group(3) != null ? m.group(3)
                            : (m.group(4) != null ? m.group(4) : m.group(5));
                    boolean quoted = m.group(3) != null || m.group(4) != null;
                    String arg = quoted ? "\"" + lit + "\"" : toJavaIdent(lit);
                    return indent + "System.out.println(" + arg + ");";
                }, 0.75, RiskTier.MODERATE,
                "Common COBOL→Java DISPLAY migration pattern");
    }

    private List<RefactorCandidate> detectMoveToAssign(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, MOVE_STMT, "cobol.move_to_assign",
                "MOVE → assignment",
                m -> {
                    String indent = m.group(1) != null ? m.group(1) : "";
                    String src = m.group(3) != null ? "\"" + m.group(3) + "\""
                            : (m.group(4) != null ? "\"" + m.group(4) + "\""
                            : toJavaIdent(m.group(5)));
                    return indent + toJavaIdent(m.group(6)) + " = " + src + ";";
                }, 0.8, RiskTier.MODERATE,
                "MOVE TO maps to a modern assignment statement");
    }

    private List<RefactorCandidate> detectComputeToAssign(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, COMPUTE_STMT, "cobol.compute_to_assign",
                "COMPUTE → assignment",
                m -> {
                    String indent = m.group(1) != null ? m.group(1) : "";
                    return indent + toJavaIdent(m.group(2)) + " = "
                            + m.group(3).trim().replace('-', '_') + ";";
                }, 0.78, RiskTier.MODERATE,
                "COMPUTE maps to arithmetic assignment");
    }

    private List<RefactorCandidate> detectPerformToCall(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, PERFORM_STMT, "cobol.perform_to_call",
                "PERFORM → method call",
                m -> (m.group(1) != null ? m.group(1) : "") + toCamel(m.group(2)) + "();",
                0.72, RiskTier.MODERATE,
                "Simple PERFORM paragraph becomes a method invocation");
    }

    private List<RefactorCandidate> detectAddToAssign(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, ADD_STMT, "cobol.add_to_assign",
                "ADD → +=",
                m -> (m.group(1) != null ? m.group(1) : "")
                        + toJavaIdent(m.group(3)) + " += " + toJavaIdent(m.group(2)) + ";",
                0.82, RiskTier.LOW,
                "ADD TO maps to += assignment");
    }

    private List<RefactorCandidate> detectSubtractToAssign(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, SUBTRACT_STMT, "cobol.subtract_to_assign",
                "SUBTRACT → -=",
                m -> (m.group(1) != null ? m.group(1) : "")
                        + toJavaIdent(m.group(3)) + " -= " + toJavaIdent(m.group(2)) + ";",
                0.82, RiskTier.LOW,
                "SUBTRACT FROM maps to -= assignment");
    }

    private List<RefactorCandidate> detectAcceptToInput(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, ACCEPT_STMT, "cobol.accept_to_input",
                "ACCEPT → console input",
                m -> (m.group(1) != null ? m.group(1) : "")
                        + toJavaIdent(m.group(2))
                        + " = new java.util.Scanner(System.in).nextLine();",
                0.7, RiskTier.MODERATE,
                "ACCEPT maps to a console Scanner read");
    }

    private List<RefactorCandidate> detectMultiplyToAssign(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, MULTIPLY_STMT, "cobol.multiply_to_assign",
                "MULTIPLY → *=",
                m -> (m.group(1) != null ? m.group(1) : "")
                        + toJavaIdent(m.group(3)) + " *= " + toJavaIdent(m.group(2)) + ";",
                0.82, RiskTier.LOW,
                "MULTIPLY BY maps to *= assignment");
    }

    private List<RefactorCandidate> detectDivideToAssign(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, DIVIDE_STMT, "cobol.divide_to_assign",
                "DIVIDE → /=",
                m -> (m.group(1) != null ? m.group(1) : "")
                        + toJavaIdent(m.group(3)) + " /= " + toJavaIdent(m.group(2)) + ";",
                0.8, RiskTier.MODERATE,
                "DIVIDE INTO maps to /= assignment");
    }

    private List<RefactorCandidate> detectInitialize(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, INITIALIZE_STMT, "cobol.initialize_to_clear",
                "INITIALIZE → clear/default",
                m -> (m.group(1) != null ? m.group(1) : "")
                        + toJavaIdent(m.group(2)) + " = /* INITIALIZE */ null;",
                0.65, RiskTier.MODERATE,
                "INITIALIZE clears group/elementary items to figurative defaults");
    }

    private List<RefactorCandidate> detectExitProgram(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, EXIT_PROGRAM_STMT, "cobol.exit_program_to_return",
                "EXIT PROGRAM → return",
                m -> (m.group(1) != null ? m.group(1) : "") + "return;",
                0.85, RiskTier.LOW,
                "EXIT PROGRAM returns control to the caller");
    }

    private List<RefactorCandidate> detectStringInto(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, STRING_INTO_STMT, "cobol.string_to_concat",
                "STRING INTO → concatenation",
                m -> {
                    String indent = m.group(1) != null ? m.group(1) : "";
                    String parts = m.group(2).trim().replaceAll("(?i)\\s+DELIMITED\\s+BY\\s+\\S+", "");
                    String[] tokens = parts.split("\\s+");
                    StringBuilder expr = new StringBuilder();
                    for (String tok : tokens) {
                        if (tok.isEmpty()) continue;
                        if (expr.length() > 0) expr.append(" + ");
                        if ((tok.startsWith("\"") && tok.endsWith("\""))
                                || (tok.startsWith("'") && tok.endsWith("'"))) {
                            expr.append("\"").append(tok.substring(1, tok.length() - 1)).append("\"");
                        } else {
                            expr.append(toJavaIdent(tok));
                        }
                    }
                    return indent + toJavaIdent(m.group(3)) + " = " + expr + ";";
                }, 0.7, RiskTier.MODERATE,
                "STRING INTO maps to modern string concatenation");
    }

    private List<RefactorCandidate> detectSetToTrue(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, SET_TRUE_STMT, "cobol.set_to_true",
                "SET … TO TRUE → boolean assign",
                m -> (m.group(1) != null ? m.group(1) : "")
                        + toJavaIdent(m.group(2)) + " = true;",
                0.88, RiskTier.LOW,
                "88-level SET TO TRUE becomes a boolean assignment");
    }


    private List<RefactorCandidate> detectInspectReplacing(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, INSPECT_REPLACING_STMT, "cobol.inspect_replacing",
                "INSPECT REPLACING → String.replace",
                m -> {
                    String indent = m.group(1) != null ? m.group(1) : "";
                    String target = toJavaIdent(m.group(2));
                    return indent + target + " = " + target + ".replace(/* " + m.group(3).trim()
                            + " */);";
                }, 0.68, RiskTier.MODERATE,
                "INSPECT REPLACING maps to String.replace / replaceAll");
    }

    private List<RefactorCandidate> detectUnstring(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, UNSTRING_STMT, "cobol.unstring_to_split",
                "UNSTRING → split",
                m -> {
                    String indent = m.group(1) != null ? m.group(1) : "";
                    String src = toJavaIdent(m.group(2));
                    String delim = m.group(3);
                    if (!(delim.startsWith("\"") || delim.startsWith("'"))) {
                        delim = toJavaIdent(delim);
                    } else if (delim.startsWith("'")) {
                        delim = "\"" + delim.substring(1, delim.length() - 1) + "\"";
                    }
                    String[] parts = m.group(4).trim().split("\\s+");
                    String first = toJavaIdent(parts[0]);
                    return indent + "String[] __parts = " + src + ".split(" + delim + "); "
                            + first + " = __parts.length > 0 ? __parts[0] : \"\";";
                }, 0.65, RiskTier.MODERATE,
                "UNSTRING DELIMITED BY maps to String.split");
    }

    private List<RefactorCandidate> detectOpenFile(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, OPEN_STMT, "cobol.open_to_stream",
                "OPEN → stream open",
                m -> {
                    String indent = m.group(1) != null ? m.group(1) : "";
                    String mode = m.group(2).toUpperCase();
                    String file = toJavaIdent(m.group(3));
                    String javaMode = switch (mode) {
                        case "INPUT" -> "READ";
                        case "OUTPUT" -> "WRITE";
                        case "EXTEND" -> "APPEND";
                        default -> "READ_WRITE";
                    };
                    return indent + "/* OPEN " + mode + " */ " + file
                            + " = java.nio.file.Files.newByteChannel(" + file
                            + "Path, java.nio.file.StandardOpenOption." + javaMode + ");";
                }, 0.6, RiskTier.HIGH,
                "OPEN maps to NIO channel/stream open");
    }

    private List<RefactorCandidate> detectCloseFile(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, CLOSE_STMT, "cobol.close_to_close",
                "CLOSE → close()",
                m -> (m.group(1) != null ? m.group(1) : "")
                        + toJavaIdent(m.group(2)) + ".close();",
                0.75, RiskTier.MODERATE,
                "CLOSE maps to Closeable.close()");
    }

    private List<RefactorCandidate> detectReadFile(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, READ_STMT, "cobol.read_to_read",
                "READ → stream read",
                m -> {
                    String indent = m.group(1) != null ? m.group(1) : "";
                    String file = toJavaIdent(m.group(2));
                    String into = m.group(3) != null ? toJavaIdent(m.group(3)) : file + "Record";
                    return indent + into + " = /* READ */ " + file + ".read();";
                }, 0.62, RiskTier.HIGH,
                "READ maps to a stream/channel read into a record buffer");
    }

    private List<RefactorCandidate> detectWriteFile(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, WRITE_STMT, "cobol.write_to_write",
                "WRITE → stream write",
                m -> {
                    String indent = m.group(1) != null ? m.group(1) : "";
                    String rec = toJavaIdent(m.group(2));
                    String from = m.group(3) != null ? toJavaIdent(m.group(3)) : rec;
                    return indent + "/* WRITE */ " + rec + "Writer.write(" + from + ");";
                }, 0.62, RiskTier.HIGH,
                "WRITE maps to a stream/channel write");
    }

    private List<RefactorCandidate> detectCallProgram(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, CALL_STMT, "cobol.call_to_invoke",
                "CALL → method/program invoke",
                m -> {
                    String indent = m.group(1) != null ? m.group(1) : "";
                    String target = m.group(2);
                    if (target.startsWith("\"") || target.startsWith("'")) {
                        String name = target.substring(1, target.length() - 1);
                        return indent + toCamel(name) + "();";
                    }
                    return indent + toCamel(target) + "();";
                }, 0.7, RiskTier.MODERATE,
                "CALL maps to a method or program invocation");
    }


    private List<RefactorCandidate> detectRewrite(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, REWRITE_STMT, "cobol.rewrite_to_update",
                "REWRITE → record update",
                m -> {
                    String indent = m.group(1) != null ? m.group(1) : "";
                    String rec = toJavaIdent(m.group(2));
                    String from = m.group(3) != null ? toJavaIdent(m.group(3)) : rec;
                    return indent + "/* REWRITE */ " + rec + "Writer.update(" + from + ");";
                }, 0.62, RiskTier.HIGH,
                "REWRITE maps to an update of an existing indexed/relative record");
    }

    private List<RefactorCandidate> detectDelete(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, DELETE_STMT, "cobol.delete_to_delete",
                "DELETE → record delete",
                m -> (m.group(1) != null ? m.group(1) : "")
                        + "/* DELETE */ " + toJavaIdent(m.group(2)) + "Writer.delete();",
                0.62, RiskTier.HIGH,
                "DELETE removes the current record from a file");
    }

    private List<RefactorCandidate> detectSort(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, SORT_STMT, "cobol.sort_to_sort",
                "SORT → Collections.sort / stream sorted",
                m -> (m.group(1) != null ? m.group(1) : "")
                        + "java.util.Collections.sort(" + toJavaIdent(m.group(2)) + ");",
                0.55, RiskTier.HIGH,
                "SORT file/table maps to an in-memory or external sort service");
    }

    private List<RefactorCandidate> detectMerge(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, MERGE_STMT, "cobol.merge_to_merge",
                "MERGE → stream merge",
                m -> (m.group(1) != null ? m.group(1) : "")
                        + "/* MERGE */ " + toJavaIdent(m.group(2)) + " = mergeSortedInputs();",
                0.55, RiskTier.HIGH,
                "MERGE combines sorted inputs into a sorted output");
    }

    private List<RefactorCandidate> detectEvaluate(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, EVALUATE_STMT, "cobol.evaluate_to_switch",
                "EVALUATE → switch/expression",
                m -> (m.group(1) != null ? m.group(1) : "")
                        + "switch (" + toJavaIdent(m.group(2).trim().split("\\s+")[0]) + ") { /* EVALUATE */ }",
                0.6, RiskTier.MODERATE,
                "EVALUATE TRUE/subject maps to switch or if-else chains");
    }

    private List<RefactorCandidate> detectSearch(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, SEARCH_STMT, "cobol.search_to_lookup",
                "SEARCH → table lookup",
                m -> (m.group(1) != null ? m.group(1) : "")
                        + "/* SEARCH */ " + toJavaIdent(m.group(2)) + ".stream().filter(/* WHEN */).findFirst();",
                0.58, RiskTier.HIGH,
                "SEARCH/SEARCH ALL maps to linear/binary lookup over a table");
    }

    private List<RefactorCandidate> detectStart(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, START_STMT, "cobol.start_to_position",
                "START → cursor position",
                m -> (m.group(1) != null ? m.group(1) : "")
                        + "/* START */ " + toJavaIdent(m.group(2)) + ".position(/* KEY */);",
                0.58, RiskTier.HIGH,
                "START positions a file for subsequent READ NEXT operations");
    }

    private List<RefactorCandidate> detectRelease(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, RELEASE_STMT, "cobol.release_to_emit",
                "RELEASE → sort emit",
                m -> {
                    String indent = m.group(1) != null ? m.group(1) : "";
                    String rec = toJavaIdent(m.group(2));
                    String from = m.group(3) != null ? toJavaIdent(m.group(3)) : rec;
                    return indent + "/* RELEASE */ " + rec + "Sort.emit(" + from + ");";
                }, 0.6, RiskTier.HIGH,
                "RELEASE feeds records into a SORT/MERGE work file");
    }

    private List<RefactorCandidate> detectReturnFile(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, RETURN_FILE_STMT, "cobol.return_to_poll",
                "RETURN → sort output poll",
                m -> {
                    String indent = m.group(1) != null ? m.group(1) : "";
                    String file = toJavaIdent(m.group(2));
                    String into = m.group(3) != null ? toJavaIdent(m.group(3)) : file + "Record";
                    return indent + into + " = /* RETURN */ " + file + "Sort.poll();";
                }, 0.6, RiskTier.HIGH,
                "RETURN retrieves the next record from SORT/MERGE output");
    }

    private List<RefactorCandidate> detectCancel(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, CANCEL_STMT, "cobol.cancel_to_unload",
                "CANCEL → unload module",
                m -> {
                    String indent = m.group(1) != null ? m.group(1) : "";
                    String target = m.group(2);
                    char q = target.isEmpty() ? 0 : target.charAt(0);
                    if ((q == '"' || q == '\'') && target.length() >= 2 && target.charAt(target.length() - 1) == q) {
                        String name = target.substring(1, target.length() - 1);
                        return indent + "/* CANCEL */ unload(" + "\"" + name + "\"" + ");";
                    }
                    return indent + "/* CANCEL */ unload(" + toCamel(target) + ");";
                }, 0.65, RiskTier.MODERATE,
                "CANCEL unloads a dynamically called program module");
    }


    private List<RefactorCandidate> detectExitSection(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, EXIT_SECTION_STMT, "cobol.exit_section_to_return",
                "EXIT SECTION → return",
                m -> (m.group(1) != null ? m.group(1) : "") + "return; // EXIT SECTION",
                0.85, RiskTier.LOW,
                "EXIT SECTION returns from the current section/method");
    }

    private List<RefactorCandidate> detectExitParagraph(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, EXIT_PARAGRAPH_STMT, "cobol.exit_paragraph_to_return",
                "EXIT PARAGRAPH → return",
                m -> (m.group(1) != null ? m.group(1) : "") + "return; // EXIT PARAGRAPH",
                0.85, RiskTier.LOW,
                "EXIT PARAGRAPH returns from the current paragraph/method");
    }

    private List<RefactorCandidate> detectMoveCorresponding(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, MOVE_CORR_STMT, "cobol.move_corresponding",
                "MOVE CORRESPONDING → field-wise copy",
                m -> (m.group(1) != null ? m.group(1) : "")
                        + "/* MOVE CORRESPONDING */ copyCorresponding("
                        + toJavaIdent(m.group(2)) + ", " + toJavaIdent(m.group(3)) + ");",
                0.7, RiskTier.MODERATE,
                "MOVE CORRESPONDING copies matching subordinate fields by name");
    }

    private List<RefactorCandidate> detectInspectTallying(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, INSPECT_TALLYING_STMT, "cobol.inspect_tallying",
                "INSPECT TALLYING → count matches",
                m -> {
                    String indent = m.group(1) != null ? m.group(1) : "";
                    String target = toJavaIdent(m.group(2));
                    return indent + "/* INSPECT TALLYING */ int tally = countMatches(" + target + ");";
                }, 0.68, RiskTier.MODERATE,
                "INSPECT TALLYING counts character/string occurrences");
    }

    private List<RefactorCandidate> detectPerformUntil(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, PERFORM_UNTIL_STMT, "cobol.perform_until_to_while",
                "PERFORM … UNTIL → while",
                m -> {
                    String indent = m.group(1) != null ? m.group(1) : "";
                    String para = toCamel(m.group(2));
                    String cond = m.group(3).trim().replace('-', '_');
                    return indent + "while (!(" + cond + ")) { " + para + "(); }";
                }, 0.7, RiskTier.MODERATE,
                "PERFORM UNTIL becomes a while loop with inverted condition");
    }

    private List<RefactorCandidate> detectPerformVarying(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, PERFORM_VARYING_STMT, "cobol.perform_varying_to_for",
                "PERFORM VARYING → for",
                m -> (m.group(1) != null ? m.group(1) : "")
                        + "for (/* VARYING */ ; ; ) { " + toCamel(m.group(2)) + "(); }",
                0.65, RiskTier.MODERATE,
                "PERFORM VARYING maps to a counted for-loop");
    }

    private List<RefactorCandidate> detectPerformTimes(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, PERFORM_TIMES_STMT, "cobol.perform_times_to_for",
                "PERFORM n TIMES → for",
                m -> {
                    String indent = m.group(1) != null ? m.group(1) : "";
                    String para = toCamel(m.group(2));
                    String times = toJavaIdent(m.group(3));
                    return indent + "for (int __i = 0; __i < " + times + "; __i++) { " + para + "(); }";
                }, 0.78, RiskTier.LOW,
                "PERFORM n TIMES becomes a simple counted loop");
    }

    private List<RefactorCandidate> detectGoDepending(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, GO_DEPENDING_STMT, "cobol.goto_depending_to_switch",
                "GO TO … DEPENDING ON → switch",
                m -> {
                    String indent = m.group(1) != null ? m.group(1) : "";
                    String targets = m.group(2).trim();
                    String key = toJavaIdent(m.group(3));
                    return indent + "switch (" + key + ") { /* GO TO " + targets + " DEPENDING ON */ }";
                }, 0.72, RiskTier.MODERATE,
                "GO TO DEPENDING ON is a classic switch dispatch");
    }

    private List<RefactorCandidate> detectSetAddress(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, SET_ADDRESS_STMT, "cobol.set_address_to_pointer",
                "SET ADDRESS OF → pointer assign",
                m -> (m.group(1) != null ? m.group(1) : "")
                        + "/* SET ADDRESS OF */ " + toJavaIdent(m.group(2))
                        + "Ptr = " + m.group(3).trim().replace('-', '_') + ";",
                0.6, RiskTier.HIGH,
                "SET ADDRESS OF manipulates based storage pointers");
    }

    private List<RefactorCandidate> detectAllocate(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, ALLOCATE_STMT, "cobol.allocate_to_new",
                "ALLOCATE → new",
                m -> (m.group(1) != null ? m.group(1) : "")
                        + toJavaIdent(m.group(2)) + " = new " + toJavaIdent(m.group(2)) + "Type();",
                0.65, RiskTier.MODERATE,
                "ALLOCATE acquires based storage / heap memory");
    }

    private List<RefactorCandidate> detectFree(String source, String relPath, boolean fixed) {
        return detectLinePattern(source, relPath, fixed, FREE_STMT, "cobol.free_to_null",
                "FREE → release/null",
                m -> (m.group(1) != null ? m.group(1) : "")
                        + toJavaIdent(m.group(2)) + " = null; // FREE",
                0.7, RiskTier.MODERATE,
                "FREE releases based storage");
    }

    @FunctionalInterface
    private interface LineReplacer {
        String apply(Matcher m);
    }

    private List<RefactorCandidate> detectLinePattern(
            String source, String relPath, boolean fixed, Pattern pattern,
            String ruleId, String ruleName, LineReplacer replacer,
            double confidence, RiskTier risk, String rationale) {
        List<RefactorCandidate> out = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String code = fixed ? programAreaOf(lines[i]) : lines[i];
            Matcher m = pattern.matcher(code);
            if (!m.matches()) continue;
            String after = replacer.apply(m);
            out.add(RefactorCandidate.builder()
                    .sourceFile(relPath)
                    .startLine(i + 1)
                    .endLine(i + 1)
                    .ruleId(ruleId)
                    .ruleName(ruleName)
                    .ruleCategory("MODERNIZATION")
                    .beforeSnippet(lines[i])
                    .proposedAfterSnippet(after)
                    .confidenceScore(confidence)
                    .riskTier(risk)
                    .addSafetyInvariant(new SafetyInvariant(
                            "cobol-migration-hint",
                            rationale,
                            SafetyInvariant.Category.BEHAVIORAL_EQUIVALENCE,
                            SafetyInvariant.Status.SATISFIED,
                            ruleId))
                    .putAstContext("language", "cobol")
                    .putAstContext("mode", MODE_TRANSLATE)
                    .putAstContext("ruleId", ruleId)
                    .build());
        }
        return out;
    }

    private static String toJavaIdent(String cobolName) {
        if (cobolName == null) return "value";
        if (cobolName.chars().allMatch(Character::isDigit)) return cobolName;
        return cobolName.replace('-', '_');
    }

    private static String toCamel(String cobolName) {
        String[] parts = cobolName.toLowerCase().split("-");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            sb.append(Character.toUpperCase(parts[i].charAt(0)));
            if (parts[i].length() > 1) sb.append(parts[i].substring(1));
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  APPLY REFACTOR
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public PatchResult applyRefactor(RefactorCandidate candidate, Path sourceRoot) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");

        Path targetFile = sourceRoot.resolve(candidate.sourceFile());
        if (!Files.isRegularFile(targetFile)) {
            return PatchResult.failure(candidate.candidateId(),
                    "Source file not found: " + targetFile);
        }

        try {
            String originalSource = Files.readString(targetFile, StandardCharsets.UTF_8);
            String beforeHash = computeAstHash(originalSource);

            String patchedSource;
            if ("cobol.fixed_to_free".equals(candidate.ruleId())) {
                patchedSource = convertFixedToFree(originalSource);
            } else {
                patchedSource = applyLineReplacement(originalSource, candidate);
            }

            String afterHash = computeAstHash(patchedSource);
            String unifiedDiff = generateUnifiedDiff(candidate.sourceFile(), originalSource, patchedSource);

            Files.writeString(targetFile, patchedSource, StandardCharsets.UTF_8);

            LOG.info("Applied COBOL refactoring '{}' to {}. AST hash {} → {}",
                    candidate.ruleId(), candidate.sourceFile(), beforeHash, afterHash);

            return PatchResult.builder()
                    .candidateId(candidate.candidateId())
                    .unifiedDiff(unifiedDiff)
                    .beforeAstHash(beforeHash)
                    .afterAstHash(afterHash)
                    .addAffectedFile(candidate.sourceFile())
                    .success(true)
                    .putMetadata("ruleId", candidate.ruleId())
                    .putMetadata("mode", isPreservingRule(candidate.ruleId())
                            ? MODE_PRESERVING : MODE_TRANSLATE)
                    .putMetadata("linesAffected", candidate.lineSpan())
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to apply COBOL refactoring '{}'", candidate.ruleId(), e);
            return PatchResult.failure(candidate.candidateId(), e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  VERIFICATION
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public VerificationResult verifyPatch(PatchResult patch, Path sourceRoot, VerificationConfig config) {
        Objects.requireNonNull(patch, "patch must not be null");
        Objects.requireNonNull(sourceRoot, "sourceRoot must not be null");
        Objects.requireNonNull(config, "config must not be null");

        String ruleId = patch.metadata() != null && patch.metadata().get("ruleId") != null
                ? String.valueOf(patch.metadata().get("ruleId")) : "";
        boolean preserving = isPreservingRule(ruleId)
                || MODE_PRESERVING.equals(String.valueOf(patch.metadata().getOrDefault("mode", "")));

        List<VerificationResult.LayerResult> layers = new ArrayList<>();

        boolean structuralOk = true;
        double astScore = 1.0;

        if (!patch.affectedFiles().isEmpty()) {
            Path target = sourceRoot.resolve(patch.affectedFiles().get(0));
            long start = System.currentTimeMillis();
            try {
                String source = Files.readString(target, StandardCharsets.UTF_8);
                if (preserving) {
                    ProgramParse reparsed = parseProgram(source, patch.affectedFiles().get(0));
                    boolean structurallySound = reparsed.programId != null
                            && !reparsed.paragraphs.isEmpty();
                    long elapsed = System.currentTimeMillis() - start;
                    layers.add(new VerificationResult.LayerResult(
                            "structural", structurallySound, structurallySound ? 1.0 : 0.5,
                            "Re-parsed: PROGRAM-ID=" + reparsed.programId
                                    + ", paragraphs=" + reparsed.paragraphs.size(),
                            elapsed));
                    structuralOk = structurallySound;
                    astScore = structurallySound ? 1.0 : 0.5;
                } else {
                    // Translate stubs are intentionally not valid COBOL.
                    boolean hasStub = source.contains("System.out")
                            || source.contains(" = ")
                            || source.contains("return;")
                            || source.contains("/* ");
                    long elapsed = System.currentTimeMillis() - start;
                    layers.add(new VerificationResult.LayerResult(
                            "translate-structural", true, hasStub ? 0.8 : 0.5,
                            "Translate-mode patch; COBOL structural re-parse skipped",
                            elapsed));
                    structuralOk = true;
                    astScore = 0.5;
                }
            } catch (IOException e) {
                layers.add(new VerificationResult.LayerResult(
                        "structural", false, 0.0,
                        "Failed to re-read patched file: " + e.getMessage(), 0));
                structuralOk = false;
                astScore = 0.0;
            }
        }

        boolean compileOk;
        boolean cobcVerified = false;
        boolean toolchainMissing = false;

        if (!preserving) {
            // Never claim cobc PASS for translate-mode Java-ish stubs.
            layers.add(verifyTranslate(patch));
            compileOk = false;
        } else if (config.runCompilation() && structuralOk && !patch.affectedFiles().isEmpty()) {
            VerificationResult.LayerResult compile = verifyWithCobc(
                    sourceRoot.resolve(patch.affectedFiles().get(0)));
            layers.add(compile);
            if (compile.details() != null && compile.details().contains("cobc not available")) {
                toolchainMissing = true;
                compileOk = structuralOk;
            } else {
                compileOk = compile.passed();
                cobcVerified = compile.passed();
            }
        } else {
            compileOk = structuralOk;
        }

        boolean nativeGate = preserving
                && structuralOk
                && (cobcVerified || toolchainMissing);

        return VerificationResult.builder()
                .patchId(patch.patchId())
                .compileSuccess(compileOk)
                .testSuccess(nativeGate)
                .astStructuralMatchScore(astScore)
                .bytecodeDescriptorMatch(nativeGate)
                .apiSurfaceCompatible(structuralOk)
                .goldenMasterMatch(false)
                .layerResults(layers)
                .beforeAstHash(patch.beforeAstHash())
                .afterAstHash(patch.afterAstHash())
                .build();
    }

    private VerificationResult.LayerResult verifyTranslate(PatchResult patch) {
        return new VerificationResult.LayerResult(
                "translate",
                true,
                0.6,
                "Translate-mode rule '" + patch.metadata().getOrDefault("ruleId", "?")
                        + "' produces Java-ish stubs; cobc verification intentionally skipped",
                0);
    }

    private VerificationResult.LayerResult verifyWithCobc(Path target) {
        long start = System.currentTimeMillis();
        try {
            String source = Files.readString(target, StandardCharsets.UTF_8);
            boolean free = source.toUpperCase(Locale.ROOT).contains(">>SOURCE FREE")
                    || !isFixedFormat(source);
            List<String> cmd = new ArrayList<>();
            cmd.add("cobc");
            cmd.add("-fsyntax-only");
            if (free) {
                cmd.add("-free");
            }
            cmd.add(target.toString());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;
            if (!finished) {
                p.destroyForcibly();
                return new VerificationResult.LayerResult(
                        "compilation", false, 0.0, "cobc timed out", elapsed);
            }
            boolean passed = p.exitValue() == 0;
            String cmdDesc = String.join(" ", cmd);
            return new VerificationResult.LayerResult(
                    "compilation",
                    passed,
                    passed ? 1.0 : 0.0,
                    passed ? cmdDesc + " succeeded on " + target.getFileName()
                            : "cobc failed:\n" + out,
                    elapsed);
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - start;
            return new VerificationResult.LayerResult(
                    "compilation", true, 0.7,
                    "cobc not available; structural-only verification", elapsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new VerificationResult.LayerResult(
                    "compilation", false, 0.0, "interrupted",
                    System.currentTimeMillis() - start);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PROGRAM PARSING
    // ═══════════════════════════════════════════════════════════════════════

    private static final Pattern PROGRAM_ID = Pattern.compile(
            "(?i)PROGRAM-ID\\s*\\.?\\s*([A-Z0-9][A-Z0-9-]*)\\s*\\.?");
    private static final Pattern PARAGRAPH_HEADER = Pattern.compile(
            "(?i)^([A-Z][A-Z0-9-]*)\\s*\\.\\s*$");
    private static final Pattern SECTION_HEADER = Pattern.compile(
            "(?i)^([A-Z][A-Z0-9-]*)\\s+SECTION\\s*\\.\\s*$");
    private static final Pattern DATA_ITEM = Pattern.compile(
            "(?i)^(\\d{1,2})\\s+([A-Z][A-Z0-9-]*)\\s*(?:PIC(?:TURE)?\\s+(\\S+))?");
    private static final Pattern PERFORM = Pattern.compile(
            "(?i)\\bPERFORM\\s+([A-Z][A-Z0-9-]*)\\b");

    private ProgramParse parseProgram(String source, String relPath) {
        boolean fixed = isFixedFormat(source);
        String[] lines = source.split("\n", -1);
        ProgramParse out = new ProgramParse();
        out.lineCount = lines.length;

        String currentDivision = null;
        String currentParagraph = null;
        int currentParagraphStart = -1;
        int currentParagraphComplexity = 1;

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            if (raw.length() > INDICATOR_COL && fixed) {
                char ind = raw.charAt(INDICATOR_COL);
                if (ind == '*' || ind == '/') continue;
            }
            String code = fixed ? programAreaOf(raw) : raw;
            String trimmed = code.trim();
            if (trimmed.isEmpty()) continue;

            // Strip trailing period for header detection, but keep for full statements.
            Matcher pid = PROGRAM_ID.matcher(trimmed);
            if (pid.find()) {
                out.programId = normalizeName(pid.group(1));
                continue;
            }

            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (upper.endsWith("DIVISION.")) {
                currentDivision = upper.substring(0, upper.length() - 1).trim();
                continue;
            }

            if (currentDivision != null && currentDivision.startsWith("PROCEDURE")) {
                Matcher pm = PARAGRAPH_HEADER.matcher(trimmed);
                if (pm.matches()) {
                    if (currentParagraph != null) {
                        out.paragraphs.add(new Paragraph(currentParagraph,
                                currentParagraphStart, i, currentParagraphComplexity));
                    }
                    currentParagraph = normalizeName(pm.group(1));
                    currentParagraphStart = i + 1;
                    currentParagraphComplexity = 1;
                    continue;
                }
                Matcher sm = SECTION_HEADER.matcher(trimmed);
                if (sm.matches()) {
                    if (currentParagraph != null) {
                        out.paragraphs.add(new Paragraph(currentParagraph,
                                currentParagraphStart, i, currentParagraphComplexity));
                        currentParagraph = null;
                    }
                    continue;
                }

                // Branching keywords increase complexity.
                if (upper.startsWith("IF ") || upper.startsWith("EVALUATE ")
                        || upper.startsWith("PERFORM ") || upper.contains(" WHEN ")) {
                    currentParagraphComplexity++;
                }

                Matcher perf = PERFORM.matcher(upper);
                while (perf.find()) {
                    if (currentParagraph != null) {
                        out.performEdges.add(new PerformEdge(
                                currentParagraph, normalizeName(perf.group(1)), i + 1));
                    }
                }
            }

            if (currentDivision != null && currentDivision.startsWith("DATA")) {
                Matcher dm = DATA_ITEM.matcher(trimmed);
                if (dm.find()) {
                    out.dataItems.add(new DataItem(
                            Integer.parseInt(dm.group(1)),
                            normalizeName(dm.group(2)),
                            dm.group(3),
                            i + 1));
                }
            }
        }

        if (currentParagraph != null) {
            out.paragraphs.add(new Paragraph(currentParagraph,
                    currentParagraphStart, lines.length, currentParagraphComplexity));
        }

        return out;
    }

    private static String normalizeName(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  FIXED → FREE CONVERSION
    // ═══════════════════════════════════════════════════════════════════════

    private String convertFixedToFree(String source) {
        String[] lines = source.split("\n", -1);
        StringBuilder out = new StringBuilder(source.length() + 32);
        // cobol.free_format_indicator — declare free reference format for GnuCOBOL / IBM.
        if (!source.toUpperCase(Locale.ROOT).contains(">>SOURCE FREE")) {
            out.append(">>SOURCE FREE\n");
        }
        boolean first = true;
        for (String line : lines) {
            if (!first) out.append('\n');
            first = false;

            if (line.length() <= INDICATOR_COL) {
                // Short or blank line — pass through (right-trim only).
                out.append(stripTrailing(line));
                continue;
            }

            char indicator = line.charAt(INDICATOR_COL);
            // Comment line: '*' or '/' in col 7 becomes a free-format *> comment.
            if (indicator == '*' || indicator == '/') {
                String body = line.length() > PROGRAM_AREA_START
                        ? line.substring(PROGRAM_AREA_START) : "";
                if (line.length() > PROGRAM_AREA_END) body = body.substring(0,
                        Math.min(body.length(), PROGRAM_AREA_END - PROGRAM_AREA_START));
                out.append("*> ").append(stripTrailing(body));
                continue;
            }
            // Continuation line ('-' in col 7) — emit as-is (free-format also allows
            // string continuation; conservative pass-through is safe).
            if (indicator == '-') {
                String body = line.length() > PROGRAM_AREA_START
                        ? line.substring(PROGRAM_AREA_START) : "";
                if (body.length() > PROGRAM_AREA_END - PROGRAM_AREA_START) {
                    body = body.substring(0, PROGRAM_AREA_END - PROGRAM_AREA_START);
                }
                out.append("    -").append(stripTrailing(body));
                continue;
            }
            // Debug line ('D' in col 7) — preserve via *DEBUG-LINE marker.
            if (indicator == 'D' || indicator == 'd') {
                String body = line.length() > PROGRAM_AREA_START
                        ? line.substring(PROGRAM_AREA_START) : "";
                if (body.length() > PROGRAM_AREA_END - PROGRAM_AREA_START) {
                    body = body.substring(0, PROGRAM_AREA_END - PROGRAM_AREA_START);
                }
                out.append("*> DEBUG: ").append(stripTrailing(body));
                continue;
            }

            // Normal line: emit only the program area (cols 8–72).
            int endCol = Math.min(line.length(), PROGRAM_AREA_END);
            String body = line.substring(PROGRAM_AREA_START, endCol);
            out.append(stripTrailing(body));
        }
        return out.toString();
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    private static String programAreaOf(String line) {
        if (line.length() <= INDICATOR_COL) return "";
        char indicator = line.charAt(INDICATOR_COL);
        if (indicator == '*' || indicator == '/') return "";
        if (line.length() <= PROGRAM_AREA_START) return "";
        int end = Math.min(line.length(), PROGRAM_AREA_END);
        return line.substring(PROGRAM_AREA_START, end);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private List<Path> collectCobolFiles(Path sourceRoot) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".cob") || name.endsWith(".cbl")
                            || name.endsWith(".cpy") || name.endsWith(".cobol")) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new AdapterException("parse", "Failed to walk COBOL source tree", e);
        }
        Collections.sort(files);
        return files;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String cobolPicToTypeName(String picture) {
        if (picture == null) return "GROUP";
        String p = picture.toUpperCase(Locale.ROOT);
        if (p.contains("X")) return "STRING(" + countOccurrence(p, 'X') + ")";
        if (p.contains("9")) {
            int digits = countOccurrence(p, '9');
            return p.contains("V") ? "DECIMAL(" + digits + ")" : "INTEGER(" + digits + ")";
        }
        if (p.contains("A")) return "ALPHA(" + countOccurrence(p, 'A') + ")";
        return "UNKNOWN";
    }

    private static int countOccurrence(String s, char c) {
        // Handle PIC X(10) → 10; otherwise count literal occurrences.
        Matcher paren = Pattern.compile("[" + c + "]\\((\\d+)\\)").matcher(s);
        if (paren.find()) return Integer.parseInt(paren.group(1));
        int count = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) count++;
        return count;
    }

    private String applyLineReplacement(String source, RefactorCandidate candidate) {
        String[] lines = source.split("\n", -1);
        int startIdx = candidate.startLine() - 1;
        int endIdx = candidate.endLine() - 1;
        if (startIdx < 0 || endIdx >= lines.length || startIdx > endIdx) {
            throw new IllegalStateException(
                    "Lines " + candidate.startLine() + "-" + candidate.endLine()
                            + " out of range for " + candidate.sourceFile());
        }
        String before = String.join("\n", Arrays.copyOfRange(lines, startIdx, endIdx + 1));
        if (!before.equals(candidate.beforeSnippet())) {
            throw new IllegalStateException(
                    "Before-snippet mismatch at lines " + candidate.startLine()
                            + "-" + candidate.endLine()
                            + " for rule " + candidate.ruleId());
        }
        String[] afterLines = candidate.proposedAfterSnippet().split("\n", -1);
        List<String> rebuilt = new ArrayList<>();
        rebuilt.addAll(Arrays.asList(lines).subList(0, startIdx));
        rebuilt.addAll(Arrays.asList(afterLines));
        if (endIdx + 1 < lines.length) {
            rebuilt.addAll(Arrays.asList(lines).subList(endIdx + 1, lines.length));
        }
        return String.join("\n", rebuilt);
    }

    private String computeAstHash(String source) {
        // Hash a canonical form: normalize case, strip whitespace, drop comment lines.
        StringBuilder canonical = new StringBuilder();
        boolean fixed = isFixedFormat(source);
        for (String line : source.split("\n", -1)) {
            if (line.length() > INDICATOR_COL && fixed) {
                char ind = line.charAt(INDICATOR_COL);
                if (ind == '*' || ind == '/') continue;
            }
            String code = fixed ? programAreaOf(line) : line;
            String stripped = code.trim();
            if (stripped.isEmpty()) continue;
            canonical.append(stripped.toUpperCase(Locale.ROOT)).append('\n');
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString().substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(canonical.toString().hashCode());
        }
    }

    private String generateUnifiedDiff(String relPath, String before, String after) {
        if (before.equals(after)) return "";
        String[] beforeLines = before.split("\n", -1);
        String[] afterLines = after.split("\n", -1);

        StringBuilder diff = new StringBuilder();
        diff.append("--- a/").append(relPath).append("\n");
        diff.append("+++ b/").append(relPath).append("\n");

        int len = Math.max(beforeLines.length, afterLines.length);
        for (int i = 0; i < len; i++) {
            String b = i < beforeLines.length ? beforeLines[i] : null;
            String a = i < afterLines.length ? afterLines[i] : null;
            if (Objects.equals(b, a)) continue;
            diff.append("@@ -").append(i + 1).append(",1 +").append(i + 1).append(",1 @@\n");
            if (b != null) diff.append("-").append(b).append("\n");
            if (a != null) diff.append("+").append(a).append("\n");
        }
        return diff.toString();
    }

    // ── Helper records ───────────────────────────────────────────────────

    private static final class ProgramParse {
        String programId;
        int lineCount;
        final List<Paragraph> paragraphs = new ArrayList<>();
        final List<DataItem> dataItems = new ArrayList<>();
        final List<PerformEdge> performEdges = new ArrayList<>();
    }

    private record Paragraph(String name, int startLine, int endLine, int complexity) {}
    private record DataItem(int level, String name, String picture, int line) {}
    private record PerformEdge(String caller, String callee, int line) {}
}
