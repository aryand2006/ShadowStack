package com.shadowstack.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Shared RBAC infrastructure (password encoder). User stores are profile-specific:
 * {@link DemoUsersConfig} and {@link ProdUsersConfig}.
 * <p>
 * Roles:
 * <ul>
 *   <li><b>ADMIN</b>    — Full access: manage projects, patches, reviews, audit, analytics</li>
 *   <li><b>REVIEWER</b> — Accept/reject patches, view projects, view analytics</li>
 *   <li><b>ANALYST</b>  — Read-only access to analytics and project data</li>
 *   <li><b>VIEWER</b>   — Read-only access to projects and patches</li>
 * </ul>
 */
@Configuration
public class RBACConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
