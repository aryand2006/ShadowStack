package com.shadowstack.api.security;

import com.shadowstack.api.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderOrgClaimTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(
                provider,
                "jwtSecret",
                "unit-test-shadowstack-jwt-secret-key-min-32-chars");
        ReflectionTestUtils.setField(provider, "jwtExpirationMs", 3_600_000L);
        provider.init();
    }

    @Test
    void generateToken_includesOrgIdFromOrgUserDetails() {
        UUID orgId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        OrgUserDetails user = new OrgUserDetails(
                UUID.randomUUID(),
                orgId,
                "alice",
                "{noop}x",
                true,
                OrgUserDetails.parseRoles("ADMIN"));
        Authentication auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());

        String token = provider.generateToken(auth);

        assertThat(provider.getOrgId(token)).isEqualTo(orgId);
        assertThat(provider.getUsername(token)).isEqualTo("alice");
    }

    @Test
    void generateToken_defaultsOrgIdWhenPrincipalLacksOrg() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "admin",
                null,
                OrgUserDetails.parseRoles("ADMIN"));

        String token = provider.generateToken(auth);

        assertThat(provider.getOrgId(token)).isEqualTo(TenantContext.DEFAULT_ORG_ID);
    }
}
