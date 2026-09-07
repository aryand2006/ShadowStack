package com.shadowstack.api.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA wiring for non-demo profiles. Demo excludes Hibernate auto-config and
 * uses in-memory stores instead.
 * <p>
 * Also scans {@code com.shadowstack.corpus} so durable {@code AuditLog} /
 * {@code AuditService} are available when Postgres is configured.
 */
@Configuration
@Profile("!demo")
@EntityScan(basePackages = {
        "com.shadowstack.api.persistence",
        "com.shadowstack.corpus"
})
@EnableJpaRepositories(basePackages = {
        "com.shadowstack.api.persistence",
        "com.shadowstack.corpus"
})
@ComponentScan(basePackages = "com.shadowstack.corpus")
public class JpaConfig {
}
