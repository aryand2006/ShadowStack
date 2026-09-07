package com.shadowstack.api.service;

import com.shadowstack.api.persistence.OrganizationRepository;
import com.shadowstack.api.persistence.PersistedOrganization;
import com.shadowstack.api.persistence.PersistedUser;
import com.shadowstack.api.persistence.UserRepository;
import com.shadowstack.api.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private UserRepository userRepository;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private OrganizationService service;

    @BeforeEach
    void setUp() {
        service = new OrganizationService(organizationRepository, userRepository, passwordEncoder);
    }

    @Test
    void createOrganization_persistsActiveOrg() {
        when(organizationRepository.existsByNameIgnoreCase("Acme")).thenReturn(false);
        when(organizationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> created = service.createOrganization("Acme");

        assertThat(created.get("name")).isEqualTo("Acme");
        assertThat(created.get("status")).isEqualTo("ACTIVE");
        assertThat(created.get("id")).isInstanceOf(UUID.class);
        verify(organizationRepository).save(any(PersistedOrganization.class));
    }

    @Test
    void createUser_storesBcryptHashAndRoles() {
        UUID orgId = TenantContext.DEFAULT_ORG_ID;
        PersistedOrganization org = new PersistedOrganization();
        org.setId(orgId);
        org.setName("default");
        org.setStatus("ACTIVE");
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());

        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(userRepository.existsByUsernameIgnoreCase("bob")).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> created = service.createUser(
                orgId, "bob", "super-secret", List.of("REVIEWER", "ANALYST"));

        ArgumentCaptor<PersistedUser> captor = ArgumentCaptor.forClass(PersistedUser.class);
        verify(userRepository).save(captor.capture());
        PersistedUser saved = captor.getValue();

        assertThat(saved.getUsername()).isEqualTo("bob");
        assertThat(saved.getOrgId()).isEqualTo(orgId);
        assertThat(saved.getRoles()).isEqualTo("REVIEWER,ANALYST");
        assertThat(passwordEncoder.matches("super-secret", saved.getPasswordHash())).isTrue();
        assertThat(created.get("username")).isEqualTo("bob");
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) created.get("roles");
        assertThat(roles).containsExactly("REVIEWER", "ANALYST");
    }

    @Test
    void getOrganization_missing_throws() {
        UUID id = UUID.randomUUID();
        when(organizationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrganization(id))
                .isInstanceOf(OrganizationService.OrganizationNotFoundException.class);
    }
}
