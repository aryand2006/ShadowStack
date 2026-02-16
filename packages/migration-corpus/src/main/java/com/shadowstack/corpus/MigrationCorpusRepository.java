package com.shadowstack.corpus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for migration corpus entries.
 * Provides custom queries for rule-based filtering, acceptance tracking,
 * vector similarity search, and analytics aggregations.
 */
@Repository
public interface MigrationCorpusRepository extends JpaRepository<MigrationEntry, UUID> {

    /**
     * Find all migration entries matching a specific rule ID.
     */
    List<MigrationEntry> findByRuleId(String ruleId);

    /**
     * Find all accepted transformations.
     */
    @Query("SELECT m FROM MigrationEntry m WHERE m.developerAccepted = true")
    List<MigrationEntry> findAccepted();

    /**
     * Find all rejected transformations.
     */
    @Query("SELECT m FROM MigrationEntry m WHERE m.developerAccepted = false")
    List<MigrationEntry> findRejected();

    /**
     * Find entries by source and target language pair.
     */
    List<MigrationEntry> findBySourceLanguageAndTargetLanguage(String sourceLanguage, String targetLanguage);

    /**
     * Find entries by risk tier.
     */
    List<MigrationEntry> findByRiskTier(String riskTier);

    /**
     * Find entries by project ID.
     */
    List<MigrationEntry> findByProjectId(java.util.UUID projectId);

    /**
     * Vector similarity search using pgvector cosine distance.
     * Returns the top-K most similar migration entries to the given embedding.
     */
    @Query(value = """
            SELECT * FROM migration_entries
            WHERE embedding IS NOT NULL
            ORDER BY embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<MigrationEntry> findSimilarByEmbedding(
            @Param("embedding") String embedding,
            @Param("limit") int limit
    );

    /**
     * Vector similarity search scoped to a specific rule.
     */
    @Query(value = """
            SELECT * FROM migration_entries
            WHERE embedding IS NOT NULL
              AND rule_id = :ruleId
            ORDER BY embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<MigrationEntry> findSimilarByEmbeddingAndRule(
            @Param("embedding") String embedding,
            @Param("ruleId") String ruleId,
            @Param("limit") int limit
    );

    /**
     * Acceptance rate per rule — returns rule_id and the ratio of accepted entries.
     */
    @Query(value = """
            SELECT rule_id,
                   COUNT(*) FILTER (WHERE developer_accepted = true)::double precision / NULLIF(COUNT(*), 0) AS acceptance_rate
            FROM migration_entries
            WHERE developer_accepted IS NOT NULL
            GROUP BY rule_id
            """, nativeQuery = true)
    List<Object[]> getAcceptanceRateByRule();

    /**
     * Risk distribution — count of entries grouped by risk tier.
     */
    @Query(value = """
            SELECT risk_tier, COUNT(*) AS entry_count
            FROM migration_entries
            GROUP BY risk_tier
            """, nativeQuery = true)
    List<Object[]> getRiskDistribution();

    /**
     * Average time to accept (in milliseconds) for accepted entries.
     */
    @Query(value = """
            SELECT AVG(time_to_accept_ms)
            FROM migration_entries
            WHERE developer_accepted = true AND time_to_accept_ms IS NOT NULL
            """, nativeQuery = true)
    Double getAverageTimeToAcceptMs();

    /**
     * Total count of accepted entries.
     */
    @Query("SELECT COUNT(m) FROM MigrationEntry m WHERE m.developerAccepted = true")
    long countAccepted();

    /**
     * Total count of rejected entries.
     */
    @Query("SELECT COUNT(m) FROM MigrationEntry m WHERE m.developerAccepted = false")
    long countRejected();

    /**
     * Confidence calibration data — bucketed predicted confidence vs actual acceptance rate.
     * Buckets confidence into 10 bins (0.0-0.1, 0.1-0.2, ... 0.9-1.0).
     */
    @Query(value = """
            SELECT
                FLOOR(confidence_score * 10) / 10 AS predicted_bucket,
                COUNT(*) FILTER (WHERE developer_accepted = true)::double precision / NULLIF(COUNT(*), 0) AS actual_acceptance_rate,
                COUNT(*) AS sample_count
            FROM migration_entries
            WHERE developer_accepted IS NOT NULL
            GROUP BY predicted_bucket
            ORDER BY predicted_bucket
            """, nativeQuery = true)
    List<Object[]> getConfidenceCalibration();

    /**
     * Top rejection reasons with counts for failure pattern analysis.
     */
    @Query(value = """
            SELECT rejection_reason, COUNT(*) AS occurrences
            FROM migration_entries
            WHERE developer_accepted = false AND rejection_reason IS NOT NULL
            GROUP BY rejection_reason
            ORDER BY occurrences DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> getTopRejectionReasons(@Param("limit") int limit);

    /**
     * Find similar accepted entries for success probability estimation.
     */
    @Query(value = """
            SELECT developer_accepted, (embedding <=> CAST(:embedding AS vector)) AS distance
            FROM migration_entries
            WHERE embedding IS NOT NULL AND developer_accepted IS NOT NULL
            ORDER BY embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findNearestWithAcceptance(
            @Param("embedding") String embedding,
            @Param("limit") int limit
    );
}
