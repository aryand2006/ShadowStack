package com.shadowstack.refactor;

import com.shadowstack.refactor.model.PatchUnit;
import com.shadowstack.refactor.model.RefactorCandidate;
import com.shadowstack.refactor.model.RiskTier;
import com.shadowstack.refactor.model.SemanticContext;
import org.eclipse.jdt.core.dom.ASTNode;

/**
 * Defines a single refactoring rule that can detect and transform specific code patterns.
 *
 * <p>Each rule in the ShadowStack engine is a self-contained, stateless transformation unit
 * that follows a detect → analyze → apply pipeline:</p>
 *
 * <ol>
 *   <li><strong>Detection</strong> ({@link #appliesTo}) — Quickly determines if a given AST node
 *       is a candidate for this rule's transformation.</li>
 *   <li><strong>Analysis</strong> ({@link #analyze}) — Performs deep semantic analysis on the candidate,
 *       evaluating safety invariants and computing a confidence score.</li>
 *   <li><strong>Application</strong> ({@link #apply}) — Generates an atomic {@link PatchUnit} containing
 *       the transformed code, unified diff, and full provenance metadata.</li>
 * </ol>
 *
 * <p>Rules must be thread-safe and stateless — all context is passed via method parameters.
 * Side effects (logging excluded) are prohibited during analysis; transformations are deferred
 * to the apply phase.</p>
 *
 * @see RefactorCandidate
 * @see PatchUnit
 * @see SemanticContext
 */
public interface RefactorRule {

    /**
     * Returns the unique identifier for this rule (e.g., "ANON_TO_LAMBDA").
     *
     * <p>Rule IDs must be stable across versions for audit trail consistency.</p>
     */
    String ruleId();

    /**
     * Returns the human-readable name of this rule.
     */
    String ruleName();

    /**
     * Returns a detailed description of what this rule does and when it applies.
     */
    String description();

    /**
     * Returns the default risk tier for transformations produced by this rule.
     *
     * <p>Individual candidates may have their risk tier adjusted based on context
     * (e.g., concurrent usage elevates risk).</p>
     */
    RiskTier defaultRiskTier();

    /**
     * Quickly determines if a given AST node is a candidate for this rule.
     *
     * <p>This method should be fast — it is called for every node during AST traversal.
     * Perform only structural checks here (node type, immediate children). Deep semantic
     * analysis belongs in {@link #analyze}.</p>
     *
     * @param node    the AST node to check
     * @param context the semantic context for the current compilation unit
     * @return true if this node is a potential candidate for transformation
     */
    boolean appliesTo(ASTNode node, SemanticContext context);

    /**
     * Performs deep semantic analysis on a candidate node.
     *
     * <p>This method evaluates all safety invariants, computes a confidence score,
     * and produces a {@link RefactorCandidate} with full analysis metadata. If any
     * invariant is violated, the candidate's invariants list will reflect this, but
     * a candidate is still returned (allowing the engine to report "considered but rejected"
     * candidates for audit purposes).</p>
     *
     * @param node    the AST node identified by {@link #appliesTo}
     * @param context the semantic context with type resolution and scope information
     * @return a RefactorCandidate with analysis results, invariants, and confidence score
     */
    RefactorCandidate analyze(ASTNode node, SemanticContext context);

    /**
     * Generates an atomic {@link PatchUnit} from a verified candidate.
     *
     * <p>This method produces the actual code transformation, including:</p>
     * <ul>
     *   <li>The transformed source code (afterSnippet)</li>
     *   <li>A unified diff between before and after</li>
     *   <li>A human-readable rationale explaining the transformation</li>
     *   <li>Full provenance metadata</li>
     * </ul>
     *
     * <p>Should only be called on candidates where {@link RefactorCandidate#allInvariantsVerified()}
     * returns true. Calling on violated candidates will throw {@link IllegalArgumentException}.</p>
     *
     * @param candidate the verified refactoring candidate
     * @return an atomic PatchUnit ready for verification
     * @throws IllegalArgumentException if the candidate has violated invariants
     */
    PatchUnit apply(RefactorCandidate candidate);
}
