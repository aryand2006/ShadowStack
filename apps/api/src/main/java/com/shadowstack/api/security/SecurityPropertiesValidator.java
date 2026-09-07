package com.shadowstack.api.security;

import com.shadowstack.api.config.ShadowStackConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Base64;
import java.util.List;

/**
 * Fails fast in prod/docker when JWT secret is weak/placeholder, and when an
 * encryption key is set but not a valid 32-byte AES key.
 */
@Configuration
@Profile("prod | docker")
public class SecurityPropertiesValidator {

    private static final int MIN_JWT_SECRET_LENGTH = 32;

    private static final List<String> FORBIDDEN_JWT_SUBSTRINGS = List.of(
            "changeme",
            "demo-only",
            "local-dev-only",
            "shadowstack_dev",
            "password",
            "secret123"
    );

    private final ShadowStackConfig config;
    private final String envEncryptionKeyBase64;

    public SecurityPropertiesValidator(
            ShadowStackConfig config,
            @Value("${ENCRYPTION_KEY_BASE64:}") String envEncryptionKeyBase64) {
        this.config = config;
        this.envEncryptionKeyBase64 = envEncryptionKeyBase64;
    }

    @PostConstruct
    void validate() {
        validateJwtSecret();
        validateEncryptionKeyIfPresent();
    }

    private void validateJwtSecret() {
        String secret = config.security() != null ? config.security().jwtSecret() : null;
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("shadowstack.security.jwt-secret must be set for prod/docker");
        }
        if (secret.length() < MIN_JWT_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "shadowstack.security.jwt-secret must be at least " + MIN_JWT_SECRET_LENGTH
                            + " characters for prod/docker");
        }
        String lower = secret.toLowerCase();
        for (String forbidden : FORBIDDEN_JWT_SUBSTRINGS) {
            if (lower.contains(forbidden)) {
                throw new IllegalStateException(
                        "shadowstack.security.jwt-secret must not contain placeholder values "
                                + FORBIDDEN_JWT_SUBSTRINGS
                                + ". Set JWT_SECRET to a strong secret.");
            }
        }
    }

    private void validateEncryptionKeyIfPresent() {
        String fromConfig = config.security() != null ? config.security().encryptionKeyBase64() : null;
        String raw = firstNonBlank(envEncryptionKeyBase64, fromConfig);
        if (raw == null) {
            return;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "ENCRYPTION_KEY_BASE64 / shadowstack.security.encryption-key-base64 must be valid Base64", e);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    "Encryption key must decode to exactly 32 bytes (AES-256); got " + decoded.length);
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }
}
