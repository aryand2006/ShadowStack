package com.shadowstack.api.service;

import com.shadowstack.api.persistence.OrganizationRepository;
import com.shadowstack.api.persistence.PersistedOrganization;
import com.shadowstack.api.persistence.PersistedUser;
import com.shadowstack.api.persistence.UserRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Organization and tenant-user administration backed by {@code ss_organizations} / {@code ss_users}.
 */
@Service
@Profile("!demo")
public class OrganizationService {

    private static final Set<String> ALLOWED_ROLES = Set.of("ADMIN", "REVIEWER", "ANALYST", "VIEWER");

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public OrganizationService(
            OrganizationRepository organizationRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listOrganizations() {
        return organizationRepository.findAll().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(this::toOrgMap)
                .toList();
    }

    @Transactional
    public Map<String, Object> createOrganization(String name) {
        String trimmed = requireName(name);
        if (organizationRepository.existsByNameIgnoreCase(trimmed)) {
            throw new OrganizationConflictException("Organization already exists: " + trimmed);
        }
        Instant now = Instant.now();
        PersistedOrganization org = new PersistedOrganization();
        org.setId(UUID.randomUUID());
        org.setName(trimmed);
        org.setStatus("ACTIVE");
        org.setCreatedAt(now);
        org.setUpdatedAt(now);
        return toOrgMap(organizationRepository.save(org));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getOrganization(UUID id) {
        return toOrgMap(requireOrg(id));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listUsers(UUID orgId) {
        requireOrg(orgId);
        return userRepository.findByOrgIdOrderByUsernameAsc(orgId).stream()
                .map(this::toUserMap)
                .toList();
    }

    @Transactional
    public Map<String, Object> createUser(UUID orgId, String username, String password, List<String> roles) {
        requireOrg(orgId);
        String trimmedUser = requireUsername(username);
        if (!StringUtils.hasText(password)) {
            throw new IllegalArgumentException("password is required");
        }
        if (userRepository.existsByUsernameIgnoreCase(trimmedUser)) {
            throw new OrganizationConflictException("Username already exists: " + trimmedUser);
        }
        String rolesCsv = normalizeRoles(roles);
        Instant now = Instant.now();
        PersistedUser user = new PersistedUser();
        user.setId(UUID.randomUUID());
        user.setOrgId(orgId);
        user.setUsername(trimmedUser);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRoles(rolesCsv);
        user.setEnabled(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return toUserMap(userRepository.save(user));
    }

    private PersistedOrganization requireOrg(UUID id) {
        return organizationRepository.findById(id)
                .orElseThrow(() -> new OrganizationNotFoundException(id));
    }

    private Map<String, Object> toOrgMap(PersistedOrganization org) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", org.getId());
        body.put("name", org.getName());
        body.put("status", org.getStatus());
        body.put("createdAt", org.getCreatedAt());
        body.put("updatedAt", org.getUpdatedAt());
        return body;
    }

    private Map<String, Object> toUserMap(PersistedUser user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.getId());
        body.put("orgId", user.getOrgId());
        body.put("username", user.getUsername());
        body.put("roles", ArraysSafe.split(user.getRoles()));
        body.put("enabled", user.isEnabled());
        body.put("createdAt", user.getCreatedAt());
        body.put("updatedAt", user.getUpdatedAt());
        return body;
    }

    private static String requireName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("name is required");
        }
        return name.trim();
    }

    private static String requireUsername(String username) {
        if (!StringUtils.hasText(username)) {
            throw new IllegalArgumentException("username is required");
        }
        return username.trim();
    }

    private static String normalizeRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("roles must not be empty");
        }
        List<String> normalized = roles.stream()
                .filter(StringUtils::hasText)
                .map(r -> r.trim().toUpperCase(Locale.ROOT).replace("ROLE_", ""))
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("roles must not be empty");
        }
        for (String role : normalized) {
            if (!ALLOWED_ROLES.contains(role)) {
                throw new IllegalArgumentException(
                        "Unsupported role '" + role + "'; allowed: " + ALLOWED_ROLES);
            }
        }
        return String.join(",", normalized);
    }

    private static final class ArraysSafe {
        private static List<String> split(String roles) {
            if (!StringUtils.hasText(roles)) {
                return List.of();
            }
            return java.util.Arrays.stream(roles.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
    }

    public static class OrganizationNotFoundException extends RuntimeException {
        private final UUID organizationId;

        public OrganizationNotFoundException(UUID organizationId) {
            super("Organization not found: " + organizationId);
            this.organizationId = organizationId;
        }

        public UUID getOrganizationId() {
            return organizationId;
        }
    }

    public static class OrganizationConflictException extends RuntimeException {
        public OrganizationConflictException(String message) {
            super(message);
        }
    }
}
