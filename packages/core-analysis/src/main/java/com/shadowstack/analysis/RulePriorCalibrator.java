package com.shadowstack.analysis;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shrinks rule priors toward observed human reject rates (Laplace-smoothed).
 *
 * <p>Calibrated prior = {@code base*(1-α) + rejectRate*α} where
 * {@code α = min(0.5, n/(n+10))} and rejectRate uses +1/+1 Laplace smoothing.</p>
 */
public final class RulePriorCalibrator {

    private final ConcurrentHashMap<String, Counters> byRule = new ConcurrentHashMap<>();

    public void recordAccept(String ruleName) {
        counters(ruleName).accepts.incrementAndGet();
    }

    public void recordReject(String ruleName) {
        counters(ruleName).rejects.incrementAndGet();
    }

    public void seed(String ruleName, long accepts, long rejects) {
        Counters c = counters(ruleName);
        c.accepts.set(Math.max(0, accepts));
        c.rejects.set(Math.max(0, rejects));
    }

    public double calibratedPrior(String ruleName, double basePrior) {
        double base = RiskPosterior.clamp(basePrior);
        Counters c = byRule.get(normalize(ruleName));
        if (c == null) {
            return base;
        }
        long accepts = c.accepts.get();
        long rejects = c.rejects.get();
        long n = accepts + rejects;
        if (n == 0) {
            return base;
        }
        double rejectRate = (rejects + 1.0) / (n + 2.0);
        double alpha = Math.min(0.5, n / (n + 10.0));
        return RiskPosterior.clamp(base * (1.0 - alpha) + rejectRate * alpha);
    }

    public Map<String, long[]> snapshot() {
        ConcurrentHashMap<String, long[]> out = new ConcurrentHashMap<>();
        byRule.forEach((k, v) -> out.put(k, new long[]{v.accepts.get(), v.rejects.get()}));
        return Map.copyOf(out);
    }

    private Counters counters(String ruleName) {
        return byRule.computeIfAbsent(normalize(ruleName), ignored -> new Counters());
    }

    private static String normalize(String ruleName) {
        return Objects.requireNonNullElse(ruleName, "unknown").trim().toLowerCase();
    }

    private static final class Counters {
        private final AtomicLong accepts = new AtomicLong();
        private final AtomicLong rejects = new AtomicLong();
    }
}
