package com.shadowstack.verify;

import com.shadowstack.refactor.model.PatchUnit;
import com.shadowstack.verify.model.VerificationContext;
import com.shadowstack.verify.model.VerificationLayerResult;

/**
 * Interface for a single verification layer in the ShadowStack verification pipeline.
 *
 * <p>Each layer performs a specific type of verification (compilation, testing, AST comparison,
 * bytecode analysis, etc.) and produces a result containing a verdict, risk contribution score,
 * and diagnostic information.</p>
 *
 * <p>Layers are executed sequentially by the {@link VerificationPipeline} and their results
 * are aggregated to determine the overall verification verdict.</p>
 *
 * <p>Implementations must be:</p>
 * <ul>
 *   <li><strong>Deterministic</strong> — Same inputs must produce same outputs</li>
 *   <li><strong>Isolated</strong> — No shared mutable state between invocations</li>
 *   <li><strong>Self-contained</strong> — All dependencies provided via VerificationContext</li>
 * </ul>
 */
public interface VerificationLayer {

    /**
     * Returns the unique identifier for this verification layer.
     */
    String layerId();

    /**
     * Verifies a patch unit and returns a result with verdict and diagnostics.
     *
     * @param patch   the patch unit to verify
     * @param context the verification context with project paths, classpath, etc.
     * @return the verification result
     */
    VerificationLayerResult verify(PatchUnit patch, VerificationContext context);
}
