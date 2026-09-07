package com.shadowstack.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Evidence-weighted residual risk after verification.
 *
 * <p>Replaces one-way {@code max(prior, verify)} with a posterior that can fall
 * below the rule prior when verification evidence is strong and clean.</p>
 *
 * <pre>
 *   adjustedPrior = rulePrior*(1-cw) + contextPrior*cw
 *   w = evidenceStrength * evidenceWeightMax
 *   residual = adjustedPrior*(1-w) + verifyRisk*w
 *   residual += blastWeight * blastRadius * (1 - evidenceStrength)
 *   FAIL → floor at failFloor; WARN → floor at warnFloor; PASS → no floor
 * </pre>
 */
public final class RiskPosterior {

    private RiskPosterior() {}

    public enum VerifyOutcome {
        PASS, WARN, FAIL;

        public static VerifyOutcome fromName(String name) {
            if (name == null || name.isBlank()) {
                return FAIL;
            }
            return switch (name.trim().toUpperCase(Locale.ROOT)) {
                case "PASS" -> PASS;
                case "WARN" -> WARN;
                default -> FAIL;
            };
        }

        public static VerifyOutcome fromPassedFlag(boolean passed) {
            return passed ? PASS : FAIL;
        }
    }

    public record BlendConfig(
            double contextPriorWeight,
            double blastRadiusWeight,
            double evidenceWeightMax,
            double warnFloor,
            double failFloor,
            double lowThreshold,
            double mediumThreshold,
            double highThreshold,
            double maxAutoApplyRisk,
            double minAutoApplyEvidence,
            boolean autoApplyEnabled
    ) {
        public static final BlendConfig DEFAULTS = new BlendConfig(
                0.25, 0.15, 0.85, 0.35, 0.85, 0.30, 0.60, 0.85, 0.20, 0.70, false);

        public String tierFor(double score) {
            if (score <= lowThreshold) return "LOW";
            if (score <= mediumThreshold) return "MEDIUM";
            if (score <= highThreshold) return "HIGH";
            return "CRITICAL";
        }

        public boolean canAutoApply(double residualRisk, double evidenceStrength) {
            return autoApplyEnabled
                    && residualRisk <= maxAutoApplyRisk
                    && evidenceStrength >= minAutoApplyEvidence;
        }
    }

    public record Factor(String name, String description, double weight, double contribution) {}

    public record Result(
            double residualRisk,
            String tier,
            double evidenceStrength,
            List<Factor> factors,
            boolean autoApplyEligible
    ) {}

    public record LayerSignal(String layerId, double riskContribution, boolean failed) {}

    /**
     * Evidence strength from verify outcome + layer coverage + measured risk.
     */
    public static double evidenceStrength(
            VerifyOutcome outcome, double verifyRisk, int passedLayers, int totalLayers) {
        double coverage = totalLayers <= 0 ? 0.5 : (double) passedLayers / totalLayers;
        double clean = 1.0 - clamp(verifyRisk);
        return switch (Objects.requireNonNullElse(outcome, VerifyOutcome.FAIL)) {
            case PASS -> clamp(0.55 + 0.45 * coverage * clean);
            case WARN -> clamp(0.25 + 0.35 * coverage);
            case FAIL -> clamp(0.05 + 0.20 * coverage);
        };
    }

    /**
     * Blast radius heuristic from API / bytecode / AST layer contributions.
     */
    public static double blastRadius(List<LayerSignal> layers) {
        if (layers == null || layers.isEmpty()) {
            return 0.0;
        }
        double blast = 0.0;
        for (LayerSignal layer : layers) {
            if (layer == null || layer.layerId() == null) {
                continue;
            }
            String id = layer.layerId().toLowerCase(Locale.ROOT);
            double c = Math.max(0.0, layer.riskContribution());
            if (id.contains("api")) {
                blast = Math.max(blast, Math.min(1.0, c * 2.5));
            } else if (id.contains("bytecode")) {
                blast = Math.max(blast, Math.min(1.0, c * 2.0));
            } else if (id.contains("ast")) {
                blast = Math.max(blast, Math.min(1.0, c * 1.5));
            } else if (layer.failed() && (id.contains("compile") || id.contains("test"))) {
                blast = Math.max(blast, Math.min(1.0, 0.35 + c));
            }
        }
        return clamp(blast);
    }

    public static Result blend(
            double rulePrior,
            double contextPrior,
            double verifyRisk,
            double blast,
            double evidence,
            VerifyOutcome outcome,
            BlendConfig config,
            List<Factor> extraFactors) {
        BlendConfig cfg = config != null ? config : BlendConfig.DEFAULTS;
        double rp = clamp(rulePrior);
        double cp = clamp(contextPrior);
        double vr = clamp(verifyRisk);
        double br = clamp(blast);
        double ev = clamp(evidence);

        double cw = clamp(cfg.contextPriorWeight());
        double bw = clamp(cfg.blastRadiusWeight());
        double wMax = clamp(cfg.evidenceWeightMax());

        double adjustedPrior = clamp(rp * (1.0 - cw) + cp * cw);
        double w = ev * wMax;
        double residual = adjustedPrior * (1.0 - w) + vr * w;
        // Blast matters more when evidence is weak (unknown impact).
        residual = clamp(residual + bw * br * (1.0 - ev));

        residual = switch (Objects.requireNonNullElse(outcome, VerifyOutcome.FAIL)) {
            case FAIL -> Math.max(residual, cfg.failFloor());
            case WARN -> Math.max(residual, cfg.warnFloor());
            case PASS -> residual;
        };
        residual = clamp(residual);

        String tier = cfg.tierFor(residual);
        List<Factor> factors = new ArrayList<>();
        factors.add(new Factor("rule_prior", "Static risk prior from modernization rule", 1.0, rp));
        factors.add(new Factor("context_prior", "Module/class context (complexity & related signals)", cw, cp));
        factors.add(new Factor("adjusted_prior", "Rule prior blended with context", 1.0, adjustedPrior));
        factors.add(new Factor("verify_pipeline", "Measured risk from verification layers", w, vr));
        factors.add(new Factor("blast_radius", "Estimated impact radius (API/bytecode/AST)", bw, br));
        factors.add(new Factor("evidence_strength", "How much verify evidence we trust", 1.0, ev));
        factors.add(new Factor("residual_risk", "Posterior residual risk after blend", 1.0, residual));
        if (extraFactors != null) {
            factors.addAll(extraFactors);
        }

        boolean auto = cfg.canAutoApply(residual, ev) && outcome == VerifyOutcome.PASS;
        return new Result(residual, tier, ev, List.copyOf(factors), auto);
    }

    public static double clamp(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }
}
