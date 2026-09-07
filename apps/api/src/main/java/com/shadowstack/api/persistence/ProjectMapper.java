package com.shadowstack.api.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowstack.api.crypto.EncryptionService;
import com.shadowstack.api.dto.PatchDetailResponse;
import com.shadowstack.api.dto.PatchDetailResponse.PatchStatus;
import com.shadowstack.api.dto.PatchDetailResponse.ReviewInfo;
import com.shadowstack.api.dto.PatchDetailResponse.RiskAssessment;
import com.shadowstack.api.dto.PatchDetailResponse.VerificationEvidence;
import com.shadowstack.api.dto.ProjectResponse;
import com.shadowstack.api.dto.ProjectResponse.ProjectStatus;
import com.shadowstack.api.tenant.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Maps between API DTOs and JPA persistence entities.
 * {@code verification_json} / {@code review_json} are serialized with Jackson.
 * Sensitive patch fields are encrypted at rest when {@link EncryptionService} is enabled.
 */
@Component
@Profile("!demo")
public class ProjectMapper {

    private final ObjectMapper objectMapper;
    private final EncryptionService encryptionService;

    public ProjectMapper(ObjectMapper objectMapper, EncryptionService encryptionService) {
        this.objectMapper = objectMapper;
        this.encryptionService = encryptionService;
    }

    public PersistedProject toEntity(ProjectResponse project, Path root) {
        PersistedProject entity = new PersistedProject();
        apply(entity, project, root);
        return entity;
    }

    public void apply(PersistedProject entity, ProjectResponse project, Path root) {
        entity.setId(project.id());
        entity.setName(project.name());
        entity.setDescription(project.description());
        entity.setRepositoryUrl(project.repositoryUrl());
        entity.setBranch(project.branch());
        entity.setSourceLanguage(project.sourceLanguage());
        entity.setTargetLanguageVersion(project.targetLanguageVersion());
        entity.setStatus(project.status() != null ? project.status().name() : ProjectStatus.CREATED.name());
        entity.setRootPath(root.toAbsolutePath().normalize().toString());
        entity.setCreatedAt(project.createdAt());
        entity.setUpdatedAt(project.updatedAt());
        if (entity.getOrgId() == null) {
            entity.setOrgId(TenantContext.requireOrgIdOrDefault());
        }
    }

    public void apply(PersistedProject entity, ProjectResponse project) {
        Path root = Path.of(entity.getRootPath() != null
                ? entity.getRootPath()
                : project.repositoryUrl());
        apply(entity, project, root);
    }

    public ProjectResponse toProjectResponse(PersistedProject entity) {
        return new ProjectResponse(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getRepositoryUrl(),
                entity.getBranch(),
                entity.getSourceLanguage(),
                entity.getTargetLanguageVersion(),
                parseProjectStatus(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                null,
                null
        );
    }

    public PersistedPatch toEntity(PatchDetailResponse patch) {
        PersistedPatch entity = new PersistedPatch();
        apply(entity, patch);
        return entity;
    }

    public void apply(PersistedPatch entity, PatchDetailResponse patch) {
        entity.setId(patch.patchId());
        entity.setProjectId(patch.projectId());
        entity.setCandidateId(patch.candidateId());
        entity.setRuleName(patch.ruleName());
        entity.setRuleCategory(patch.ruleCategory());
        entity.setStatus(patch.status() != null ? patch.status().name() : PatchStatus.GENERATED.name());
        entity.setFilePath(patch.filePath());
        entity.setStartLine(patch.startLine());
        entity.setEndLine(patch.endLine());
        entity.setUnifiedDiff(encryptionService.encrypt(patch.unifiedDiff()));
        entity.setRationale(patch.rationale());
        if (patch.risk() != null) {
            entity.setRiskScore(patch.risk().score());
            entity.setRiskTier(patch.risk().tier() != null ? patch.risk().tier().name() : null);
            entity.setConfidence(patch.risk().confidenceScore());
        } else {
            entity.setRiskScore(null);
            entity.setRiskTier(null);
            entity.setConfidence(null);
        }
        entity.setVerificationJson(encryptionService.encrypt(writeJson(patch.verificationEvidence())));
        entity.setReviewJson(encryptionService.encrypt(writeJson(patch.review())));
        entity.setCreatedBy(patch.createdBy());
        entity.setCreatedAt(patch.createdAt());
        entity.setUpdatedAt(patch.updatedAt());
        if (entity.getOrgId() == null) {
            entity.setOrgId(TenantContext.requireOrgIdOrDefault());
        }
    }

    public PatchDetailResponse toPatchDetailResponse(PersistedPatch entity) {
        RiskAssessment risk = null;
        if (entity.getRiskScore() != null || entity.getRiskTier() != null || entity.getConfidence() != null) {
            RiskAssessment.RiskTier tier = parseRiskTier(entity.getRiskTier());
            risk = new RiskAssessment(
                    entity.getRiskScore() != null ? entity.getRiskScore() : 0.0,
                    tier,
                    List.of(),
                    entity.getConfidence() != null ? entity.getConfidence() : 0.0
            );
        }
        return new PatchDetailResponse(
                entity.getId(),
                entity.getProjectId(),
                entity.getCandidateId(),
                entity.getRuleName(),
                entity.getRuleCategory(),
                parsePatchStatus(entity.getStatus()),
                entity.getFilePath(),
                entity.getStartLine() != null ? entity.getStartLine() : 0,
                entity.getEndLine() != null ? entity.getEndLine() : 0,
                encryptionService.decrypt(entity.getUnifiedDiff()),
                entity.getRationale(),
                List.of(),
                risk,
                readJson(encryptionService.decrypt(entity.getVerificationJson()), VerificationEvidence.class),
                readJson(encryptionService.decrypt(entity.getReviewJson()), ReviewInfo.class),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize persistence JSON", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize persistence JSON", e);
        }
    }

    private static ProjectStatus parseProjectStatus(String status) {
        if (status == null || status.isBlank()) {
            return ProjectStatus.CREATED;
        }
        return ProjectStatus.valueOf(status);
    }

    private static PatchStatus parsePatchStatus(String status) {
        if (status == null || status.isBlank()) {
            return PatchStatus.GENERATED;
        }
        return PatchStatus.valueOf(status);
    }

    private static RiskAssessment.RiskTier parseRiskTier(String tier) {
        if (tier == null || tier.isBlank()) {
            return RiskAssessment.RiskTier.MEDIUM;
        }
        return RiskAssessment.RiskTier.valueOf(tier);
    }
}
