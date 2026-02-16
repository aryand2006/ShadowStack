package com.shadowstack.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * RBAC configuration defining the role hierarchy for ShadowStack.
 * <p>
 * Roles:
 * <ul>
 *   <li><b>ADMIN</b>    — Full access: manage projects, patches, reviews, audit, analytics</li>
 *   <li><b>REVIEWER</b> — Accept/reject patches, view projects, view analytics</li>
 *   <li><b>ANALYST</b>  — Read-only access to analytics and project data</li>
 *   <li><b>VIEWER</b>   — Read-only access to projects and patches</li>
 * </ul>
 * <p>
 * In production, replace the in-memory user store with a persistent store
 * backed by JPA or an external identity provider (OAuth2/OIDC).
 */
@Configuration
public class RBACConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        UserDetails admin = User.builder()
                .username("admin")
                .password(passwordEncoder.encode("admin"))
                .roles("ADMIN")
                .build();

        UserDetails reviewer = User.builder()
                .username("reviewer")
                .password(passwordEncoder.encode("reviewer"))
                .roles("REVIEWER")
                .build();

        UserDetails analyst = User.builder()
                .username("analyst")
                .password(passwordEncoder.encode("analyst"))
                .roles("ANALYST")
                .build();

        UserDetails viewer = User.builder()
                .username("viewer")
                .password(passwordEncoder.encode("viewer"))
                .roles("VIEWER")
                .build();

        return new InMemoryUserDetailsManager(admin, reviewer, analyst, viewer);
    }
}
