package com.shadowstack.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI / Swagger documentation configuration for the ShadowStack API.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI shadowStackOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ShadowStack API")
                        .version("1.0.0")
                        .description("""
                                Enterprise verified language modernization engine REST API.

                                ShadowStack automates the refactoring of legacy codebases with formal
                                behavioral equivalence verification. Every transformation is backed by
                                proof artifacts, risk scoring, and human-in-the-loop review.

                                ## Workflow
                                1. **Ingest** — Create a project from a Git repository
                                2. **Baseline** — Capture behavioral baseline (tests, invariants)
                                3. **Analyze** — Run static analysis to identify refactor candidates
                                4. **Patch** — Generate verified refactoring patches
                                5. **Verify** — Run verification pipeline (tests + invariant checks)
                                6. **Review** — Human review with full evidence context
                                7. **Apply** — Apply accepted transformations
                                """)
                        .contact(new Contact()
                                .name("ShadowStack Team")
                                .email("team@shadowstack.dev"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://shadowstack.dev/license")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local development"),
                        new Server().url("https://api.shadowstack.dev").description("Production")
                ))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("JWT token obtained via authentication"))
                        .addSecuritySchemes("basicAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("basic")
                                        .description("HTTP Basic authentication (development only)")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"));
    }
}
