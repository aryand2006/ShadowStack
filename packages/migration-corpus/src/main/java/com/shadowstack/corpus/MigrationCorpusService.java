package com.shadowstack.corpus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core business logic service for the migration intelligence corpus.
 * Handles recording transformations, similarity search, success probability estimation,
 * and aggregating analytics across the entire corpus.
 */
@Service
public class MigrationCorpusService {

    private static final Logger log = LoggerFactory.getLogger(MigrationCorpusService.class);

    private static final int DEFAULT_SIMILAR_LIMIT = 20;
    private static final int PROBABILITY_NEIGHBOR_COUNT = 50;
    private static final int TOP_FAILURE_PATTERNS_LIMIT = 20;

    private final MigrationCorpusRepository repository;
    private final AuditService auditService;

    public MigrationCorpusService(MigrationCorpusRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    /**
     * Record a transformation that was accepted by a developer.
     *
     * @param entry         the migration entry to record
     * @param timeToAcceptMs time in ms the developer took to accept
     * @param actorId       the ID of the developer who accepted
     * @return the persisted entry
     */
    @Transactional
    public MigrationEntry recordAcceptedTransformation(MigrationEntry entry, long timeToAcceptMs, String actorId) {
        Objects.requireNonNull(entry, "Migration entry must not be null");
        Objects.requireNonNull(actorId, "Actor ID must not be null");

        entry.setDeveloperAccepted(true);
        entry.setTimeToAcceptMs(timeToAcceptMs);
        entry.setRejectionReason(null);

        MigrationEntry saved = repository.save(entry);

        auditService.logAction(
                "TRANSFORMATION_ACCEPTED",
                "MigrationEntry",
                saved.getId().toString(),
                actorId,
                "DEVELOPER",
                Map.of(
                        "ruleId", saved.getRuleId(),
                        "riskTier", saved.getRiskTier(),
                        "confidenceScore", saved.getConfidenceScore(),
                        "timeToAcceptMs", timeToAcceptMs
                )
        );

        log.info("Recorded accepted transformation: ruleId={}, entryId={}, timeToAcceptMs={}",
                saved.getRuleId(), saved.getId(), timeToAcceptMs);

        return saved;
    }

    /**
     * Record a transformation that was rejected by a developer.
     *
     * @param entry           the migration entry to record
     * @param rejectionReason free-text reason the developer rejected the transformation
     * @param actorId         the ID of the developer who rejected
     * @return the persisted entry
     */
    @Transactional
    public MigrationEntry recordRejectedTransformation(MigrationEntry entry, String rejectionReason, String actorId) {
        Objects.requireNonNull(entry, "Migration entry must not be null");
        Objects.requireNonNull(rejectionReason, "Rejection reason must not be null");
        Objects.requireNonNull(actorId, "Actor ID must not be null");

        entry.setDeveloperAccepted(false);
        entry.setRejectionReason(rejectionReason);

        MigrationEntry saved = repository.save(entry);

        auditService.logAction(
                "TRANSFORMATION_REJECTED",
                "MigrationEntry",
                saved.getId().toString(),
                actorId,
                "DEVELOPER",
                Map.of(
                        "ruleId", saved.getRuleId(),
                        "riskTier", saved.getRiskTier(),
                        "confidenceScore", saved.getConfidenceScore(),
                        "rejectionReason", rejectionReason
                )
        );

        log.info("Recorded rejected transformation: ruleId={}, entryId={}, reason={}",
                saved.getRuleId(), saved.getId(), rejectionReason);

        return saved;
    }

    /**
     * Find the most similar migration entries to a given embedding vector.
     *
     * @param embedding the 768-dim embedding vector
     * @param limit     max number of results
     * @return list of similar entries ordered by cosine similarity
     */
    @Transactional(readOnly = true)
    public List<MigrationEntry> findSimilarMigrations(float[] embedding, int limit) {
        Objects.requireNonNull(embedding, "Embedding must not be null");
        if (embedding.length != 768) {
            throw new IllegalArgumentException("Embedding must be 768-dimensional, got " + embedding.length);
        }
        if (limit <= 0) {
            limit = DEFAULT_SIMILAR_LIMIT;
        }

        String embeddingStr = formatEmbeddingForPgvector(embedding);
        return repository.findSimilarByEmbedding(embeddingStr, limit);
    }

    /**
     * Find similar migrations scoped to a specific rule.
     */
    @Transactional(readOnly = true)
    public List<MigrationEntry> findSimilarMigrationsByRule(float[] embedding, String ruleId, int limit) {
        Objects.requireNonNull(embedding, "Embedding must not be null");
        Objects.requireNonNull(ruleId, "Rule ID must not be null");
        if (embedding.length != 768) {
            throw new IllegalArgumentException("Embedding must be 768-dimensional, got " + embedding.length);
        }

        String embeddingStr = formatEmbeddingForPgvector(embedding);
        return repository.findSimilarByEmbeddingAndRule(embeddingStr, ruleId, limit);
    }

    /**
     * Estimate the probability that a transformation with the given embedding will be accepted,
     * based on the historical acceptance rate of the K nearest neighbors in the corpus.
     * Uses inverse-distance weighting so closer neighbors have more influence.
     *
     * @param embedding the 768-dim embedding of the candidate transformation
     * @return estimated acceptance probability in [0.0, 1.0]
     */
    @Transactional(readOnly = true)
    public double estimateSuccessProbability(float[] embedding) {
        Objects.requireNonNull(embedding, "Embedding must not be null");
        if (embedding.length != 768) {
            throw new IllegalArgumentException("Embedding must be 768-dimensional, got " + embedding.length);
        }

        String embeddingStr = formatEmbeddingForPgvector(embedding);
        List<Object[]> neighbors = repository.findNearestWithAcceptance(embeddingStr, PROBABILITY_NEIGHBOR_COUNT);

        if (neighbors.isEmpty()) {
            log.warn("No neighbors found for success probability estimation, returning 0.5 as default");
            return 0.5;
        }

        double weightedAccepted = 0.0;
        double totalWeight = 0.0;

        for (Object[] row : neighbors) {
            boolean accepted = (Boolean) row[0];
            double distance = ((Number) row[1]).doubleValue();

            // Inverse-distance weight (add small epsilon to avoid division by zero)
            double weight = 1.0 / (distance + 1e-6);
            totalWeight += weight;

            if (accepted) {
                weightedAccepted += weight;
            }
        }

        double probability = totalWeight > 0 ? weightedAccepted / totalWeight : 0.5;

        log.debug("Estimated success probability: {} (based on {} neighbors)", probability, neighbors.size());
        return probability;
    }

    /**
     * Aggregate comprehensive analytics across the entire migration corpus.
     *
     * @return analytics DTO with acceptance rates, risk distribution, calibration, and failure patterns
     */
    @Transactional(readOnly = true)
    public CorpusAnalytics getAnalytics() {
        CorpusAnalytics analytics = new CorpusAnalytics();

        // Total counts
        analytics.setTotalEntries(repository.count());
        analytics.setAcceptedCount(repository.countAccepted());
        analytics.setRejectedCount(repository.countRejected());

        // Acceptance rate by rule
        Map<String, Double> acceptanceByRule = new LinkedHashMap<>();
        for (Object[] row : repository.getAcceptanceRateByRule()) {
            String ruleId = (String) row[0];
            double rate = row[1] != null ? ((Number) row[1]).doubleValue() : 0.0;
            acceptanceByRule.put(ruleId, rate);
        }
        analytics.setAcceptanceRateByRule(acceptanceByRule);

        // Risk distribution
        Map<String, Long> riskDist = new LinkedHashMap<>();
        for (Object[] row : repository.getRiskDistribution()) {
            String tier = (String) row[0];
            long count = ((Number) row[1]).longValue();
            riskDist.put(tier, count);
        }
        analytics.setRiskDistribution(riskDist);

        // Average time to accept
        Double avgMs = repository.getAverageTimeToAcceptMs();
        if (avgMs != null) {
            analytics.setAvgTimeToAccept(Duration.ofMillis(avgMs.longValue()));
        } else {
            analytics.setAvgTimeToAccept(Duration.ZERO);
        }

        // Confidence calibration
        List<CorpusAnalytics.CalibrationPoint> calibration = new ArrayList<>();
        for (Object[] row : repository.getConfidenceCalibration()) {
            double bucket = ((Number) row[0]).doubleValue();
            double actualRate = row[1] != null ? ((Number) row[1]).doubleValue() : 0.0;
            long sampleCount = ((Number) row[2]).longValue();
            calibration.add(new CorpusAnalytics.CalibrationPoint(bucket, actualRate, sampleCount));
        }
        analytics.setConfidenceCalibration(calibration);

        // Top failure patterns
        List<CorpusAnalytics.FailurePattern> patterns = new ArrayList<>();
        for (Object[] row : repository.getTopRejectionReasons(TOP_FAILURE_PATTERNS_LIMIT)) {
            String reason = (String) row[0];
            long occurrences = ((Number) row[1]).longValue();
            patterns.add(new CorpusAnalytics.FailurePattern(reason, occurrences));
        }
        analytics.setTopFailurePatterns(patterns);

        log.info("Generated analytics: total={}, accepted={}, rejected={}",
                analytics.getTotalEntries(), analytics.getAcceptedCount(), analytics.getRejectedCount());

        return analytics;
    }

    /**
     * Formats a float[] embedding into the pgvector string representation: [x1,x2,...,xN]
     */
    private String formatEmbeddingForPgvector(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
