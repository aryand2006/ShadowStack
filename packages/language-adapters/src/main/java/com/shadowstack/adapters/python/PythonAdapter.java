package com.shadowstack.adapters.python;

import com.shadowstack.adapters.LanguageAdapter;
import com.shadowstack.adapters.model.*;

import java.nio.file.Path;
import java.util.List;

/**
 * Language adapter for Python source code.
 *
 * <p><strong>Status:</strong> Planned for ShadowStack v2.0.</p>
 *
 * <p>This adapter will provide support for Python 3.10+ source analysis
 * and modernization, leveraging <a href="https://tree-sitter.github.io/tree-sitter/">
 * tree-sitter</a> for incremental parsing via the {@code tree-sitter-python}
 * grammar and JNI bindings.</p>
 *
 * <h3>Planned Capabilities</h3>
 * <ul>
 *   <li>Full AST parsing via tree-sitter with incremental re-parse support</li>
 *   <li>Module/class/function hierarchy extraction</li>
 *   <li>Type stub (`.pyi`) and inline type annotation resolution</li>
 *   <li>Import graph and call graph construction</li>
 *   <li>Decorator and metaclass metadata extraction</li>
 *   <li>Complexity metrics (cyclomatic, cognitive) per function</li>
 *   <li>Async/await pattern recognition</li>
 * </ul>
 *
 * <h3>Planned Refactoring Rules</h3>
 * <ul>
 *   <li>Python 2 → 3 migration patterns (print statement, unicode, dict methods)</li>
 *   <li>Type annotation insertion from runtime type inference</li>
 *   <li>f-string modernization from format() and % formatting</li>
 *   <li>Dataclass conversion from manual {@code __init__} patterns</li>
 *   <li>Pattern matching (match/case) introduction for isinstance chains</li>
 *   <li>Context manager (with statement) extraction for resource patterns</li>
 *   <li>Walrus operator (:=) introduction where beneficial</li>
 * </ul>
 *
 * <h3>Integration Architecture</h3>
 * <p>The Python adapter will use tree-sitter via JNI for parsing, with an
 * optional fallback to spawning a Python subprocess running {@code ast.parse()}
 * and communicating via JSON over stdin/stdout. Type resolution will integrate
 * with pyright or mypy in language-server mode.</p>
 *
 * @see LanguageAdapter
 * @since 2.0.0 (planned)
 */
public class PythonAdapter implements LanguageAdapter {

    private static final String NOT_IMPLEMENTED_MSG =
            "Python adapter not yet implemented — planned for v2.0 with tree-sitter integration";

    /**
     * {@inheritDoc}
     *
     * @return {@code "python"}
     */
    @Override
    public String languageId() {
        return "python";
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code "3.12"} (target Python version)
     */
    @Override
    public String languageVersion() {
        return "3.12";
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always — Python parsing is not yet implemented;
     *         future versions will use tree-sitter-python for incremental AST construction
     */
    @Override
    public SemanticModel parse(Path sourceRoot, LanguageAdapterConfig config) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_MSG);
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always — Python semantic model building is not yet
     *         implemented; future versions will integrate with pyright for type resolution
     */
    @Override
    public SemanticModel buildSemanticModel(Path sourceRoot) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_MSG);
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always — Python refactoring is not yet implemented;
     *         future versions will support Python 2→3 migration, type annotation insertion,
     *         and modern idiom adoption
     */
    @Override
    public List<RefactorCandidate> listRefactorCandidates(SemanticModel model, RefactorRuleSet rules) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_MSG);
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always — Python refactoring is not yet implemented;
     *         future versions will use tree-sitter for AST rewriting with concrete syntax tree
     *         preservation (whitespace, comments)
     */
    @Override
    public PatchResult applyRefactor(RefactorCandidate candidate, Path sourceRoot) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_MSG);
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always — Python verification is not yet implemented;
     *         future versions will support pytest execution, mypy type checking, and AST
     *         structural comparison via tree-sitter
     */
    @Override
    public VerificationResult verifyPatch(PatchResult patch, Path sourceRoot, VerificationConfig config) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_MSG);
    }
}
