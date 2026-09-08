package com.shadowstack.api.controllers;

import com.shadowstack.api.crypto.EncryptionService;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetaControllerCobolRehostTest {

    @Test
    void cobolRehostExposesPhaseMatrixAndGaps() {
        EncryptionService encryption = mock(EncryptionService.class);
        Environment env = mock(Environment.class);
        when(encryption.isEnabled()).thenReturn(false);
        when(env.getActiveProfiles()).thenReturn(new String[]{"demo"});

        MetaController controller = new MetaController(encryption, env);
        ResponseEntity<Map<String, Object>> response = controller.cobolRehost();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("capability")).isEqualTo("cobol-to-java-semantic-rehost");
        assertThat(response.getBody().get("claim").toString()).contains("Blu Age");
        assertThat(response.getBody().get("parityClass")).isEqualTo("blu-age-class-supported-surfaces");
        assertThat(response.getBody().get("roadmapDoc")).isEqualTo("docs/blu-age-cobol-roadmap.md");

        @SuppressWarnings("unchecked")
        Map<String, String> phases = (Map<String, String>) response.getBody().get("phases");
        assertThat(phases).containsEntry("6_call_goldens", "done_plus_by_reference");
        assertThat(phases).containsEntry("7_productization", "done");
        assertThat(phases).containsEntry("3_cics", "embedded_mvp_plus_bms");
        assertThat(phases).containsEntry("5_jcl", "mvp_plus_cond_include_proc");

        @SuppressWarnings("unchecked")
        List<String> gaps = (List<String>) response.getBody().get("knownGaps");
        assertThat(gaps).isNotEmpty();
        assertThat(gaps).anyMatch(g -> g.contains("bit-identical") || g.contains("Bit-identical")
                || g.contains("licensed Blu Age") || g.contains("Licensed Blu Age"));
        assertThat(gaps).noneMatch(g -> g.contains("Nested PROGRAM-ID"));
        assertThat(gaps).noneMatch(g -> g.equals("BMS / 3270 screens"));

        @SuppressWarnings("unchecked")
        List<String> keys = (List<String>) response.getBody().get("patchMetadataKeys");
        assertThat(keys).contains("translateGaps", "resolvedCalls");
    }
}
