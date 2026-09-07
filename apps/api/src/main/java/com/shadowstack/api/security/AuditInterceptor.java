package com.shadowstack.api.security;

import com.shadowstack.api.tenant.TenantContext;
import com.shadowstack.corpus.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Intercepts all API calls and records audit information.
 * <p>
 * When {@link AuditService} is available ({@code !demo}), writes durable rows.
 * Otherwise falls back to SLF4J structured logging so the demo profile stays JPA-free.
 */
@Component
public class AuditInterceptor implements HandlerInterceptor {

    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");
    private static final String START_TIME_ATTR = "audit.startTime";

    private final ObjectProvider<AuditService> auditService;

    public AuditInterceptor(ObjectProvider<AuditService> auditService) {
        this.auditService = auditService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        request.setAttribute(START_TIME_ATTR, Instant.now());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        String actor = resolveActor();
        String actorRole = resolveActorRole();
        String method = request.getMethod();
        String path = request.getRequestURI();
        String query = request.getQueryString();
        int status = response.getStatus();

        Instant startTime = (Instant) request.getAttribute(START_TIME_ATTR);
        long durationMs = startTime != null
                ? Instant.now().toEpochMilli() - startTime.toEpochMilli()
                : -1;

        String fullPath = query != null ? path + "?" + query : path;
        var orgId = TenantContext.getOrgId();

        auditLog.info("action={} {} actor={} orgId={} status={} duration_ms={} path={}",
                method, fullPath, actor, orgId, status, durationMs, fullPath);

        if (ex != null) {
            auditLog.warn("action={} {} actor={} orgId={} error={}",
                    method, path, actor, orgId, ex.getMessage());
        }

        AuditService durable = auditService.getIfAvailable();
        if (durable == null) {
            return;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("method", method);
        details.put("path", fullPath);
        details.put("status", status);
        details.put("durationMs", durationMs);
        if (orgId != null) {
            details.put("orgId", orgId.toString());
        }
        if (ex != null) {
            details.put("error", ex.getMessage());
        }

        durable.logActionWithIp(
                method + " " + path,
                "HTTP_REQUEST",
                path,
                actor,
                actorRole,
                details,
                request.getRemoteAddr(),
                orgId
        );
    }

    private String resolveActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return "anonymous";
    }

    private String resolveActorRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null || auth.getAuthorities().isEmpty()) {
            return "ANONYMOUS";
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));
    }
}
