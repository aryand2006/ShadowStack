package com.shadowstack.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;

import com.shadowstack.api.tenant.TenantContext;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JWT token provider for creating and validating bearer tokens.
 * Supports role-based claims for the ShadowStack RBAC model.
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);
    private static final String ROLES_CLAIM = "roles";
    public static final String ORG_ID_CLAIM = "org_id";

    @Value("${shadowstack.security.jwt-secret}")
    private String jwtSecret;

    @Value("${shadowstack.security.jwt-expiration-ms}")
    private long jwtExpirationMs;

    private SecretKey signingKey;

    @PostConstruct
    void init() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Generate a JWT token for the given authentication.
     * Includes {@code org_id} from {@link OrgUserDetails} when present, otherwise the default org.
     */
    public String generateToken(Authentication authentication) {
        String username = authentication.getName();
        String roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));

        UUID orgId = resolveOrgId(authentication);

        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtExpirationMs);

        return Jwts.builder()
                .subject(username)
                .claim(ROLES_CLAIM, roles)
                .claim(ORG_ID_CLAIM, orgId.toString())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    private static UUID resolveOrgId(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof OrgUserDetails orgUser && orgUser.getOrgId() != null) {
            return orgUser.getOrgId();
        }
        return TenantContext.DEFAULT_ORG_ID;
    }

    /**
     * Extract the {@code org_id} claim from a JWT, or {@code null} if absent/invalid.
     */
    public UUID getOrgId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String orgId = claims.get(ORG_ID_CLAIM, String.class);
            if (orgId == null || orgId.isBlank()) {
                return null;
            }
            return UUID.fromString(orgId);
        } catch (Exception e) {
            log.debug("Unable to read org_id claim: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse a JWT token and return the Spring Security Authentication.
     */
    public Authentication getAuthentication(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String rolesStr = claims.get(ROLES_CLAIM, String.class);
        Collection<? extends GrantedAuthority> authorities = Arrays.stream(rolesStr.split(","))
                .filter(role -> !role.isBlank())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());

        User principal = new User(claims.getSubject(), "", authorities);
        return new UsernamePasswordAuthenticationToken(principal, token, authorities);
    }

    /**
     * Validate the JWT token and return true if valid.
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT token expired: {}", e.getMessage());
        } catch (JwtException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("JWT token is empty or null: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Extract the username (subject) from a JWT token.
     */
    public String getUsername(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }
}
