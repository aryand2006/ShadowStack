package com.shadowstack.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;

/**
 * Intercepts all API calls and logs audit information.
 * <p>
 * Records: action (HTTP method + path), actor (authenticated principal),
 * timestamp, response status, and request duration.
 * <p>
 * In production, writes audit entries to the audit_log database table.
 * Currently logs via SLF4J structured logging for observability.
 */
@Component
public class AuditInterceptor implements HandlerInterceptor {

    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");
    private static final String START_TIME_ATTR = "audit.startTime";

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
        String method = request.getMethod();
        String path = request.getRequestURI();
        String query = request.getQueryString();
        int status = response.getStatus();

        Instant startTime = (Instant) request.getAttribute(START_TIME_ATTR);
        long durationMs = startTime != null
                ? Instant.now().toEpochMilli() - startTime.toEpochMilli()
                : -1;

        String fullPath = query != null ? path + "?" + query : path;

        auditLog.info("action={} {} actor={} status={} duration_ms={} path={}",
                method, fullPath, actor, status, durationMs, fullPath);

        if (ex != null) {
            auditLog.warn("action={} {} actor={} error={}",
                    method, path, actor, ex.getMessage());
        }
    }

    private String resolveActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return "anonymous";
    }
}
