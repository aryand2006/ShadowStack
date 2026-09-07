package com.shadowstack.api.tenant;

import java.util.UUID;

/**
 * Request-scoped tenant (organization) identity held in a {@link ThreadLocal}.
 * Cleared by {@link TenantFilter} after each request.
 */
public final class TenantContext {

    /** Fixed default organization seeded by Flyway V5. */
    public static final UUID DEFAULT_ORG_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000001");

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setOrgId(UUID orgId) {
        CURRENT.set(orgId);
    }

    public static UUID getOrgId() {
        return CURRENT.get();
    }

    /**
     * Returns the current org id, or {@link #DEFAULT_ORG_ID} when unset.
     */
    public static UUID requireOrgIdOrDefault() {
        UUID orgId = CURRENT.get();
        return orgId != null ? orgId : DEFAULT_ORG_ID;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
