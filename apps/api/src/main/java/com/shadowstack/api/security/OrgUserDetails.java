package com.shadowstack.api.security;

import com.shadowstack.api.persistence.PersistedUser;
import com.shadowstack.api.tenant.TenantContext;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link UserDetails} that carries the user's organization for JWT {@code org_id} claims.
 */
public final class OrgUserDetails implements UserDetails {

    private final UUID userId;
    private final UUID orgId;
    private final String username;
    private final String password;
    private final boolean enabled;
    private final Collection<? extends GrantedAuthority> authorities;

    public OrgUserDetails(
            UUID userId,
            UUID orgId,
            String username,
            String password,
            boolean enabled,
            Collection<? extends GrantedAuthority> authorities) {
        this.userId = userId;
        this.orgId = orgId != null ? orgId : TenantContext.DEFAULT_ORG_ID;
        this.username = username;
        this.password = password;
        this.enabled = enabled;
        this.authorities = List.copyOf(authorities);
    }

    public static OrgUserDetails fromPersisted(PersistedUser user) {
        return new OrgUserDetails(
                user.getId(),
                user.getOrgId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.isEnabled(),
                parseRoles(user.getRoles()));
    }

    public static OrgUserDetails fromEnv(String username, String encodedPassword, String... roles) {
        return new OrgUserDetails(
                null,
                TenantContext.DEFAULT_ORG_ID,
                username,
                encodedPassword,
                true,
                Arrays.stream(roles)
                        .map(OrgUserDetails::toAuthority)
                        .collect(Collectors.toList()));
    }

    public static List<GrantedAuthority> parseRoles(String rolesCsv) {
        if (rolesCsv == null || rolesCsv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rolesCsv.split(","))
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .map(OrgUserDetails::toAuthority)
                .collect(Collectors.toList());
    }

    private static GrantedAuthority toAuthority(String role) {
        String normalized = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return new SimpleGrantedAuthority(normalized);
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
