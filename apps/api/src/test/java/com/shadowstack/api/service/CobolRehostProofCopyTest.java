package com.shadowstack.api.service;

import com.shadowstack.adapters.model.RefactorCandidate;
import com.shadowstack.adapters.model.RiskTier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CobolRehostProofCopyTest {

    @Test
    void copiesGapsAndCallsFromAppliedMetadata() {
        Map<String, Object> proof = new LinkedHashMap<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("translateGaps", "CICS verb,EXEC SQL");
        meta.put("resolvedCallsJoined", "WORKER,HELPER");
        meta.put("translateGapsList", List.of("CICS verb", "EXEC SQL"));

        RefactorOrchestrationService.copyCobolRehostProof(proof, meta, null);

        assertThat(proof.get("translateGaps")).isEqualTo("CICS verb,EXEC SQL");
        assertThat(proof.get("resolvedCalls")).isEqualTo("WORKER,HELPER");
        assertThat(proof.get("translateGapsList")).isEqualTo(List.of("CICS verb", "EXEC SQL"));
    }

    @Test
    void fallsBackToCandidateAstContext() {
        Map<String, Object> proof = new LinkedHashMap<>();
        RefactorCandidate candidate = RefactorCandidate.builder()
                .sourceFile("DRIVER.cob")
                .startLine(1)
                .endLine(10)
                .ruleId("cobol.semantic_rehost")
                .ruleName("rehost")
                .ruleCategory("MODERNIZATION")
                .beforeSnippet("x")
                .proposedAfterSnippet("y")
                .confidenceScore(0.8)
                .riskTier(RiskTier.MODERATE)
                .putAstContext("translateGaps", "nested program")
                .putAstContext("resolvedCallsJoined", "WORKER")
                .build();

        RefactorOrchestrationService.copyCobolRehostProof(proof, Map.of("applyFallback", "snippet"), candidate);

        assertThat(proof.get("translateGaps")).isEqualTo("nested program");
        assertThat(proof.get("resolvedCalls")).isEqualTo("WORKER");
    }
}
