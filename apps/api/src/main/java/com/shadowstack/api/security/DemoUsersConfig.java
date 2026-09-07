package com.shadowstack.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * Demo / local credentials (admin/admin, etc.). Never use outside the demo profile.
 */
@Configuration
@Profile("demo")
public class DemoUsersConfig {

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
