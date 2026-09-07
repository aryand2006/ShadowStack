package com.shadowstack.api.service;

import com.shadowstack.api.dto.AnalyticsDashboardResponse;
import com.shadowstack.api.dto.AnalyticsDashboardResponse.AcceptanceMetrics;
import com.shadowstack.api.dto.AnalyticsDashboardResponse.AcceptanceMetrics.CategoryAcceptanceRate;
import com.shadowstack.api.dto.AnalyticsDashboardResponse.AcceptanceMetrics.RuleAcceptanceRate;
import com.shadowstack.api.dto.AnalyticsDashboardResponse.ConfidenceCalibration;
import com.shadowstack.api.dto.AnalyticsDashboardResponse.ConfidenceCalibration.CalibrationBucket;
import com.shadowstack.api.dto.AnalyticsDashboardResponse.CorpusMetrics;
import com.shadowstack.api.dto.AnalyticsDashboardResponse.PipelineHealth;
import com.shadowstack.api.dto.AnalyticsDashboardResponse.RiskDistribution;
import com.shadowstack.api.dto.PatchDetailResponse;
import com.shadowstack.api.dto.PatchDetailResponse.PatchStatus;
import com.shadowstack.api.dto.PatchDetailResponse.RiskAssessment.RiskTier;
import com.shadowstack.api.dto.ProjectResponse;
import com.shadowstack.api.persistence.PatchStore;
import com.shadowstack.api.persistence.ProjectStore;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Aggregates analytics from live {@link PatchStore} / {@link ProjectStore} data
 * (in-memory under {@code demo}, JPA otherwise).
 */
@Service
public class AnalyticsService {

    private final PatchStore patchStore;
    private final ProjectStore projectStore;

    public AnalyticsService(PatchStore patchStore, ProjectStore projectStore) {
        this.patchStore = patchStore;
        this.projectStore = projectStore;
    }

    public CorpusMetrics corpusMetrics() {
        List<PatchDetailResponse> patches = patchStore.findAll();
        Map<UUID, String> languageByProject = projectLanguages();

        Map<String, Long> byLanguage = new HashMap<>();
        Map<String, Long> byCategory = new HashMap<>();
        for (PatchDetailResponse p : patches) {
            String lang = languageByProject.getOrDefault(p.projectId(), "unknown");
            byLanguage.merge(lang, 1L, Long::sum);
            String cat = p.ruleCategory() != null ? p.ruleCategory() : "uncategorized";
            byCategory.merge(cat, 1L, Long::sum);
        }

        long accepted = countStatus(patches, PatchStatus.ACCEPTED);
        long reviewed = accepted + countStatus(patches, PatchStatus.REJECTED);
        double successRate = reviewed == 0 ? 0.0 : (double) accepted / reviewed;

        long distinctRules = patches.stream()
                .map(PatchDetailResponse::ruleName)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        return new CorpusMetrics(
                distinctRules,
                patches.size(),
                accepted,
                successRate,
                byLanguage,
                byCategory
        );
    }

    public AcceptanceMetrics acceptanceMetrics() {
        List<PatchDetailResponse> reviewed = patchStore.findAll().stream()
                .filter(p -> p.status() == PatchStatus.ACCEPTED || p.status() == PatchStatus.REJECTED)
                .toList();

        long accepted = reviewed.stream().filter(p -> p.status() == PatchStatus.ACCEPTED).count();
        double overall = reviewed.isEmpty() ? 0.0 : (double) accepted / reviewed.size();

        Map<String, List<PatchDetailResponse>> byRule = reviewed.stream()
                .collect(Collectors.groupingBy(p -> p.ruleName() != null ? p.ruleName() : "unknown"));
        List<RuleAcceptanceRate> ruleRates = byRule.entrySet().stream()
                .map(e -> {
                    long total = e.getValue().size();
                    long acc = e.getValue().stream().filter(p -> p.status() == PatchStatus.ACCEPTED).count();
                    return new RuleAcceptanceRate(e.getKey(), total, acc, total == 0 ? 0.0 : (double) acc / total);
                })
                .sorted(Comparator.comparing(RuleAcceptanceRate::ruleName))
                .toList();

        Map<String, List<PatchDetailResponse>> byCat = reviewed.stream()
                .collect(Collectors.groupingBy(p -> p.ruleCategory() != null ? p.ruleCategory() : "uncategorized"));
        List<CategoryAcceptanceRate> catRates = byCat.entrySet().stream()
                .map(e -> {
                    long total = e.getValue().size();
                    long acc = e.getValue().stream().filter(p -> p.status() == PatchStatus.ACCEPTED).count();
                    return new CategoryAcceptanceRate(e.getKey(), total, acc, total == 0 ? 0.0 : (double) acc / total);
                })
                .sorted(Comparator.comparing(CategoryAcceptanceRate::category))
                .toList();

        return new AcceptanceMetrics(overall, ruleRates, catRates);
    }

    public RiskDistribution riskDistribution() {
        List<PatchDetailResponse> patches = patchStore.findAll();
        EnumMap<RiskTier, Long> counts = new EnumMap<>(RiskTier.class);
        for (RiskTier tier : RiskTier.values()) {
            counts.put(tier, 0L);
        }
        List<Double> scores = new ArrayList<>();
        for (PatchDetailResponse p : patches) {
            if (p.risk() == null) {
                continue;
            }
            scores.add(p.risk().score());
            RiskTier tier = p.risk().tier();
            if (tier != null) {
                counts.merge(tier, 1L, Long::sum);
            }
        }
        scores.sort(Double::compareTo);
        double mean = scores.isEmpty() ? 0.0 : scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double median = scores.isEmpty() ? 0.0 : (
                scores.size() % 2 == 1
                        ? scores.get(scores.size() / 2)
                        : (scores.get(scores.size() / 2 - 1) + scores.get(scores.size() / 2)) / 2.0
        );
        return new RiskDistribution(
                counts.get(RiskTier.LOW),
                counts.get(RiskTier.MEDIUM),
                counts.get(RiskTier.HIGH),
                counts.get(RiskTier.CRITICAL),
                mean,
                median
        );
    }

    public ConfidenceCalibration confidenceCalibration() {
        List<PatchDetailResponse> reviewed = patchStore.findAll().stream()
                .filter(p -> p.status() == PatchStatus.ACCEPTED || p.status() == PatchStatus.REJECTED)
                .filter(p -> p.risk() != null)
                .toList();

        double[] edges = {0.0, 0.2, 0.4, 0.6, 0.8, 1.01};
        List<CalibrationBucket> buckets = new ArrayList<>();
        double brierSum = 0.0;
        int brierN = 0;
        double eceSum = 0.0;
        int total = reviewed.size();

        for (int i = 0; i < edges.length - 1; i++) {
            double min = edges[i];
            double max = edges[i + 1];
            List<PatchDetailResponse> inBucket = reviewed.stream()
                    .filter(p -> {
                        double c = p.risk().confidenceScore();
                        return c >= min && c < max;
                    })
                    .toList();
            long accepted = inBucket.stream().filter(p -> p.status() == PatchStatus.ACCEPTED).count();
            double actual = inBucket.isEmpty() ? 0.0 : (double) accepted / inBucket.size();
            buckets.add(new CalibrationBucket(min, Math.min(max, 1.0), actual, inBucket.size()));
            if (!inBucket.isEmpty() && total > 0) {
                double predicted = (min + Math.min(max, 1.0)) / 2.0;
                eceSum += ((double) inBucket.size() / total) * Math.abs(predicted - actual);
            }
        }

        for (PatchDetailResponse p : reviewed) {
            double predicted = p.risk().confidenceScore();
            double outcome = p.status() == PatchStatus.ACCEPTED ? 1.0 : 0.0;
            brierSum += Math.pow(predicted - outcome, 2);
            brierN++;
        }

        return new ConfidenceCalibration(
                buckets,
                brierN == 0 ? 0.0 : brierSum / brierN,
                eceSum
        );
    }

    public PipelineHealth pipelineHealth() {
        List<PatchDetailResponse> patches = patchStore.findAll();
        long generated = patches.size();
        long verified = patches.stream()
                .filter(p -> p.status() == PatchStatus.PENDING_REVIEW
                        || p.status() == PatchStatus.ACCEPTED
                        || p.status() == PatchStatus.REJECTED
                        || p.status() == PatchStatus.VERIFIED
                        || p.status() == PatchStatus.APPLIED)
                .count();
        long accepted = countStatus(patches, PatchStatus.ACCEPTED);
        long rejected = countStatus(patches, PatchStatus.REJECTED);

        List<Double> verifySecs = new ArrayList<>();
        List<Double> reviewSecs = new ArrayList<>();
        for (PatchDetailResponse p : patches) {
            if (p.verificationEvidence() != null
                    && p.verificationEvidence().verifiedAt() != null
                    && p.createdAt() != null) {
                verifySecs.add(Duration.between(p.createdAt(), p.verificationEvidence().verifiedAt()).toMillis() / 1000.0);
            }
            if (p.review() != null
                    && p.review().reviewedAt() != null
                    && p.verificationEvidence() != null
                    && p.verificationEvidence().verifiedAt() != null) {
                reviewSecs.add(Duration.between(
                        p.verificationEvidence().verifiedAt(), p.review().reviewedAt()).toMillis() / 1000.0);
            }
        }
        double meanVerify = verifySecs.isEmpty() ? 0.0
                : verifySecs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double meanReview = reviewSecs.isEmpty() ? 0.0
                : reviewSecs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        return new PipelineHealth(generated, verified, accepted, rejected, meanVerify, meanReview);
    }

    public AnalyticsDashboardResponse dashboard() {
        return new AnalyticsDashboardResponse(
                corpusMetrics(),
                acceptanceMetrics(),
                riskDistribution(),
                confidenceCalibration(),
                pipelineHealth(),
                Instant.now()
        );
    }

    private Map<UUID, String> projectLanguages() {
        Map<UUID, String> map = new HashMap<>();
        for (ProjectResponse p : projectStore.findAll()) {
            map.put(p.id(), p.sourceLanguage() != null ? p.sourceLanguage() : "unknown");
        }
        return map;
    }

    private static long countStatus(List<PatchDetailResponse> patches, PatchStatus status) {
        return patches.stream().filter(p -> p.status() == status).count();
    }
}
