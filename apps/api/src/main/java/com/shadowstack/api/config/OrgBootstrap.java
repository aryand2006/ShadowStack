package com.shadowstack.api.config;

import com.shadowstack.api.persistence.OrganizationRepository;
import com.shadowstack.api.persistence.PersistedOrganization;
import com.shadowstack.api.persistence.PersistedUser;
import com.shadowstack.api.persistence.UserRepository;
import com.shadowstack.api.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.UUID;

/**
 * Ensures the default organization and {@code SECURITY_USER} (plus optional reviewer)
 * exist in {@code ss_users} on every non-demo startup.
 */
@Component
@Profile("!demo")
public class OrgBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OrgBootstrap.class);

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${SECURITY_USER:}")
    private String securityUser;

    @Value("${SECURITY_PASSWORD:}")
    private String securityPassword;

    @Value("${SECURITY_REVIEWER_USER:}")
    private String reviewerUser;

    @Value("${SECURITY_REVIEWER_PASSWORD:}")
    private String reviewerPassword;

    public OrgBootstrap(
            OrganizationRepository organizationRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ensureDefaultOrganization();
        if (StringUtils.hasText(securityUser) && StringUtils.hasText(securityPassword)) {
            upsertUser(securityUser, securityPassword, "ADMIN");
        }
        if (StringUtils.hasText(reviewerUser) && StringUtils.hasText(reviewerPassword)) {
            upsertUser(reviewerUser, reviewerPassword, "REVIEWER");
        }
    }

    private void ensureDefaultOrganization() {
        if (organizationRepository.existsById(TenantContext.DEFAULT_ORG_ID)) {
            return;
        }
        Instant now = Instant.now();
        PersistedOrganization org = new PersistedOrganization();
        org.setId(TenantContext.DEFAULT_ORG_ID);
        org.setName("default");
        org.setStatus("ACTIVE");
        org.setCreatedAt(now);
        org.setUpdatedAt(now);
        organizationRepository.save(org);
        log.info("Seeded default organization {}", TenantContext.DEFAULT_ORG_ID);
    }

    private void upsertUser(String username, String rawPassword, String roles) {
        Instant now = Instant.now();
        PersistedUser user = userRepository.findByUsernameIgnoreCase(username.trim())
                .orElseGet(PersistedUser::new);
        boolean created = user.getId() == null;
        if (created) {
            user.setId(UUID.randomUUID());
            user.setCreatedAt(now);
        }
        user.setOrgId(TenantContext.DEFAULT_ORG_ID);
        user.setUsername(username.trim());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRoles(roles);
        user.setEnabled(true);
        user.setUpdatedAt(now);
        userRepository.save(user);
        log.info("{} SECURITY user '{}' in org {}", created ? "Created" : "Updated", username, TenantContext.DEFAULT_ORG_ID);
    }
}
