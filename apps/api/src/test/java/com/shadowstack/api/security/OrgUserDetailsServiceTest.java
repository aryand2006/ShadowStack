package com.shadowstack.api.security;

import com.shadowstack.api.persistence.PersistedUser;
import com.shadowstack.api.persistence.UserRepository;
import com.shadowstack.api.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    private OrgUserDetailsService service;
    private ProdUsersConfig.EnvCredentialUsers envUsers;

    @BeforeEach
    void setUp() {
        envUsers = new ProdUsersConfig.EnvCredentialUsers(List.of(
                OrgUserDetails.fromEnv("envadmin", "{bcrypt}x", "ADMIN")));
        service = new OrgUserDetailsService(userRepository, envUsers);
    }

    @Test
    void loadsFromDatabaseWhenPresent() {
        UUID orgId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        PersistedUser row = new PersistedUser();
        row.setId(UUID.randomUUID());
        row.setOrgId(orgId);
        row.setUsername("dbuser");
        row.setPasswordHash("{bcrypt}hash");
        row.setRoles("ADMIN");
        row.setEnabled(true);
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());

        when(userRepository.findByUsernameIgnoreCase("dbuser")).thenReturn(Optional.of(row));

        UserDetails details = service.loadUserByUsername("dbuser");

        assertThat(details).isInstanceOf(OrgUserDetails.class);
        assertThat(((OrgUserDetails) details).getOrgId()).isEqualTo(orgId);
        assertThat(details.getUsername()).isEqualTo("dbuser");
    }

    @Test
    void fallsBackToEnvUsers() {
        when(userRepository.findByUsernameIgnoreCase("envadmin")).thenReturn(Optional.empty());

        UserDetails details = service.loadUserByUsername("envadmin");

        assertThat(details.getUsername()).isEqualTo("envadmin");
        assertThat(((OrgUserDetails) details).getOrgId()).isEqualTo(TenantContext.DEFAULT_ORG_ID);
    }

    @Test
    void unknownUserThrows() {
        when(userRepository.findByUsernameIgnoreCase("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("missing"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
