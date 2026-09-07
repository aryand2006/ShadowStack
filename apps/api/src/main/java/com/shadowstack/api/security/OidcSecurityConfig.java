package com.shadowstack.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.shadowstack.api.tenant.TenantFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OIDC / OAuth2 resource-server security for enterprise SSO.
 * <p>
 * Active when the {@code oidc} Spring profile is enabled. Accepts:
 * <ul>
 *   <li>ShadowStack HMAC JWTs via {@link JwtAuthenticationFilter}</li>
 *   <li>IdP JWTs validated against the configured issuer-uri</li>
 *   <li>HTTP Basic + {@code POST /api/v1/auth/login} for local bootstrap users</li>
 * </ul>
 * IdP roles are mapped from claim {@code roles} or Keycloak-style {@code realm_access.roles}.
 */
@Configuration
@Profile("oidc")
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class OidcSecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;

    @Value("${shadowstack.security.cors-allowed-origins}")
    private String corsAllowedOrigins;

    public OidcSecurityConfig(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Bean
    public SecurityFilterChain oidcSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/audit/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/compliance/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/reviews/*/accept", "/api/v1/reviews/*/reject")
                                .hasAnyRole("REVIEWER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/analytics/**")
                                .hasAnyRole("ANALYST", "REVIEWER", "ADMIN")
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .httpBasic(Customizer.withDefaults())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(shadowStackJwtAuthenticationConverter()))
                )
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider),
                        BearerTokenAuthenticationFilter.class
                )
                .addFilterAfter(
                        new TenantFilter(jwtTokenProvider),
                        JwtAuthenticationFilter.class
                );

        return http.build();
    }

    /**
     * ShadowStack HMAC JWTs are handled by {@link JwtAuthenticationFilter};
     * remaining Bearer tokens are validated against the IdP issuer.
     */
    @Bean
    public BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return request -> {
            String token = delegate.resolve(request);
            if (token != null && jwtTokenProvider.validateToken(token)) {
                return null;
            }
            return token;
        };
    }

    @Bean
    public Converter<Jwt, ? extends AbstractAuthenticationToken> shadowStackJwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(this::extractAuthorities);
        return converter;
    }

    private Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        List<String> roles = new ArrayList<>();

        Object rolesClaim = jwt.getClaim("roles");
        if (rolesClaim instanceof String s && !s.isBlank()) {
            roles.addAll(Arrays.asList(s.split(",")));
        } else if (rolesClaim instanceof Collection<?> c) {
            c.forEach(v -> {
                if (v != null) {
                    roles.add(v.toString());
                }
            });
        }

        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> map) {
            Object realmRoles = map.get("roles");
            if (realmRoles instanceof Collection<?> c) {
                c.forEach(v -> {
                    if (v != null) {
                        roles.add(v.toString());
                    }
                });
            }
        }

        return roles.stream()
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(corsAllowedOrigins.split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "X-Requested-With", TenantFilter.ORG_HEADER));
        configuration.setExposedHeaders(List.of("X-Total-Count", "X-Page-Number", "X-Page-Size"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }
}
