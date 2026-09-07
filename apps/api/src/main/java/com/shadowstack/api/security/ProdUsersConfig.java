package com.shadowstack.api.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Production / docker credential validation and env-user fallback.
 * Requires {@code SECURITY_USER} / {@code SECURITY_PASSWORD} (password length ≥ 12).
 * Optional reviewer: {@code SECURITY_REVIEWER_USER} / {@code SECURITY_REVIEWER_PASSWORD}.
 * <p>
 * Primary authentication for {@code !demo} is {@link OrgUserDetailsService} (DB + this fallback).
 * {@link com.shadowstack.api.config.OrgBootstrap} upserts {@code SECURITY_USER} into {@code ss_users}.
 */
@Configuration
@Profile("!demo")
public class ProdUsersConfig {

    @Value("${SECURITY_USER:}")
    private String securityUser;

    @Value("${SECURITY_PASSWORD:}")
    private String securityPassword;

    @Value("${SECURITY_REVIEWER_USER:}")
    private String reviewerUser;

    @Value("${SECURITY_REVIEWER_PASSWORD:}")
    private String reviewerPassword;

    @PostConstruct
    void validateRequiredCredentials() {
        if (!StringUtils.hasText(securityUser) || !StringUtils.hasText(securityPassword)) {
            throw new IllegalStateException(
                    "SECURITY_USER and SECURITY_PASSWORD are required when the demo profile is not active");
        }
        if (securityPassword.length() < 12) {
            throw new IllegalStateException(
                    "SECURITY_PASSWORD must be at least 12 characters when the demo profile is not active");
        }
        if (StringUtils.hasText(reviewerUser) || StringUtils.hasText(reviewerPassword)) {
            if (!StringUtils.hasText(reviewerUser) || !StringUtils.hasText(reviewerPassword)) {
                throw new IllegalStateException(
                        "SECURITY_REVIEWER_USER and SECURITY_REVIEWER_PASSWORD must both be set when configuring a reviewer");
            }
            if (reviewerPassword.length() < 12) {
                throw new IllegalStateException(
                        "SECURITY_REVIEWER_PASSWORD must be at least 12 characters");
            }
        }
    }

    @Bean
    public EnvCredentialUsers envCredentialUsers(PasswordEncoder passwordEncoder) {
        List<OrgUserDetails> users = new ArrayList<>();
        users.add(OrgUserDetails.fromEnv(
                securityUser,
                passwordEncoder.encode(securityPassword),
                "ADMIN"));

        if (StringUtils.hasText(reviewerUser) && StringUtils.hasText(reviewerPassword)) {
            users.add(OrgUserDetails.fromEnv(
                    reviewerUser,
                    passwordEncoder.encode(reviewerPassword),
                    "REVIEWER"));
        }
        return new EnvCredentialUsers(users);
    }

    /**
     * In-memory fallback users from environment variables (used when {@code ss_users} has no match).
     */
    public static final class EnvCredentialUsers {
        private final List<OrgUserDetails> users;

        public EnvCredentialUsers(List<OrgUserDetails> users) {
            this.users = List.copyOf(users);
        }

        public Optional<OrgUserDetails> findByUsername(String username) {
            if (!StringUtils.hasText(username)) {
                return Optional.empty();
            }
            String needle = username.trim().toLowerCase(Locale.ROOT);
            return users.stream()
                    .filter(u -> u.getUsername().toLowerCase(Locale.ROOT).equals(needle))
                    .findFirst();
        }
    }
}
