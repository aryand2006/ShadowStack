package com.shadowstack.api.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Production / docker users from environment variables.
 * Requires {@code SECURITY_USER} / {@code SECURITY_PASSWORD} (password length ≥ 12).
 * Optional reviewer: {@code SECURITY_REVIEWER_USER} / {@code SECURITY_REVIEWER_PASSWORD}.
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
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        List<UserDetails> users = new ArrayList<>();
        users.add(User.builder()
                .username(securityUser)
                .password(passwordEncoder.encode(securityPassword))
                .roles("ADMIN")
                .build());

        if (StringUtils.hasText(reviewerUser) && StringUtils.hasText(reviewerPassword)) {
            users.add(User.builder()
                    .username(reviewerUser)
                    .password(passwordEncoder.encode(reviewerPassword))
                    .roles("REVIEWER")
                    .build());
        }

        return new InMemoryUserDetailsManager(users);
    }
}
