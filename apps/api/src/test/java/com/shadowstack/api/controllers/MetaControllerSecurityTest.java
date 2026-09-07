package com.shadowstack.api.controllers;

import com.shadowstack.api.crypto.EncryptionService;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetaControllerSecurityTest {

    @Test
    void securityReportsEncryptionDisabledByDefault() {
        EncryptionService encryption = mock(EncryptionService.class);
        Environment env = mock(Environment.class);
        when(encryption.isEnabled()).thenReturn(false);
        when(env.matchesProfiles("vault")).thenReturn(false);
        when(env.getActiveProfiles()).thenReturn(new String[]{"demo"});

        MetaController controller = new MetaController(encryption, env);
        ResponseEntity<Map<String, Object>> response = controller.security();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("encryptionEnabled")).isEqualTo(false);
        assertThat(response.getBody().get("encryptionAlgorithm")).isEqualTo("none");
        assertThat(response.getBody().get("vaultProfileActive")).isEqualTo(false);
        assertThat(response.getBody().get("secretsSource")).isEqualTo("env-or-k8s-secret");
    }

    @Test
    void securityReportsEncryptionAndVaultWhenEnabled() {
        EncryptionService encryption = mock(EncryptionService.class);
        Environment env = mock(Environment.class);
        when(encryption.isEnabled()).thenReturn(true);
        when(env.matchesProfiles("vault")).thenReturn(true);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod", "vault"});

        MetaController controller = new MetaController(encryption, env);
        ResponseEntity<Map<String, Object>> response = controller.security();

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("encryptionEnabled")).isEqualTo(true);
        assertThat(response.getBody().get("encryptionAlgorithm")).isEqualTo("AES-256-GCM");
        assertThat(response.getBody().get("vaultProfileActive")).isEqualTo(true);
        assertThat(response.getBody().get("secretsSource")).isEqualTo("env-from-vault-agent-or-eso");
    }
}
