package com.shadowstack.api.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA wiring for non-demo profiles. Demo excludes Hibernate auto-config and
 * uses in-memory stores instead.
 */
@Configuration
@Profile("!demo")
@EntityScan("com.shadowstack.api.persistence")
@EnableJpaRepositories("com.shadowstack.api.persistence")
public class JpaConfig {
}
