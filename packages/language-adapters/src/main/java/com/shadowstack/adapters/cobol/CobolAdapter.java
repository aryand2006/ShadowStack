package com.shadowstack.adapters.cobol;

import com.shadowstack.adapters.LanguageAdapter;
import com.shadowstack.adapters.model.*;

import java.nio.file.Path;
import java.util.List;

/**
 * Language adapter for COBOL source code.
 *
 * <p><strong>Status:</strong> Planned for ShadowStack v2.0.</p>
 *
 * <p>This adapter will provide full support for COBOL-85 and COBOL-2002
 * dialects, including:</p>
 * <ul>
 *   <li>COBOL copybook resolution and COPY REPLACING</li>
 *   <li>Division/section/paragraph structure mapping to {@link SemanticModel}</li>
 *   <li>Data division to type system translation</li>
 *   <li>PERFORM graph → call graph edge extraction</li>
 *   <li>Working-storage / linkage section data flow analysis</li>
 *   <li>CICS/DB2 embedded statement recognition</li>
 *   <li>JCL proc step correlation for batch modernization</li>
 * </ul>
 *
 * <h3>Planned Implementation</h3>
 * <p>The COBOL adapter will use the Eclipse COBOL plugin's parser or a custom
 * ANTLR4 COBOL grammar to produce ASTs. Refactoring rules will target common
 * modernization patterns such as:</p>
 * <ul>
 *   <li>GO TO elimination (structured programming transformation)</li>
 *   <li>Paragraph-to-method extraction for Java/C# migration</li>
 *   <li>PICTURE clause to modern type mapping</li>
 *   <li>PERFORM VARYING to counted-loop normalization</li>
 *   <li>Embedded SQL extraction and parameterization</li>
 * </ul>
 *
 * @see LanguageAdapter
 * @since 2.0.0 (planned)
 */
public class CobolAdapter implements LanguageAdapter {

    private static final String NOT_IMPLEMENTED_MSG =
            "COBOL adapter not yet implemented — planned for v2.0";

    /**
     * {@inheritDoc}
     *
     * @return {@code "cobol"}
     */
    @Override
    public String languageId() {
        return "cobol";
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code "85"} (COBOL-85 target dialect)
     */
    @Override
    public String languageVersion() {
        return "85";
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always — COBOL parsing is not yet implemented
     */
    @Override
    public SemanticModel parse(Path sourceRoot, LanguageAdapterConfig config) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_MSG);
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always — COBOL semantic model building is not yet implemented
     */
    @Override
    public SemanticModel buildSemanticModel(Path sourceRoot) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_MSG);
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always — COBOL refactoring is not yet implemented
     */
    @Override
    public List<RefactorCandidate> listRefactorCandidates(SemanticModel model, RefactorRuleSet rules) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_MSG);
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always — COBOL refactoring is not yet implemented
     */
    @Override
    public PatchResult applyRefactor(RefactorCandidate candidate, Path sourceRoot) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_MSG);
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always — COBOL verification is not yet implemented
     */
    @Override
    public VerificationResult verifyPatch(PatchResult patch, Path sourceRoot, VerificationConfig config) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_MSG);
    }
}
