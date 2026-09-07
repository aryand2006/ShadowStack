package com.shadowstack.api.security;

import com.shadowstack.api.config.ShadowStackConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Fails fast in prod/docker when JWT secret is still a demo/placeholder value.
 */
@Configuration
@Profile("prod | docker")
public class SecurityPropertiesValidator {

    private final ShadowStackConfig config;

    public SecurityPropertiesValidator(ShadowStackConfig config) {
        this.config = config;
    }

    @PostConstruct
    void validateJwtSecret() {
        String secret = config.security() != null ? config.security().jwtSecret() : null;
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("shadowstack.security.jwt-secret must be set for prod/docker");
        }
        String lower = secret.toLowerCase();
        if (lower.contains("changeme") || lower.contains("demo-only")) {
            throw new IllegalStateException(
                    "shadowstack.security.jwt-secret must not contain placeholder values "
                            + "('changeme' or 'demo-only'). Set JWT_SECRET to a strong secret.");
        }
    }
}
