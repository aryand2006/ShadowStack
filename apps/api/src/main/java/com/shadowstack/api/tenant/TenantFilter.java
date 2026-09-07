package com.shadowstack.api.tenant;

import com.shadowstack.api.security.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Resolves the tenant organization for authenticated requests.
 * <p>
 * Precedence: {@code X-Org-Id} header → JWT {@code org_id} claim → default org.
 * Always clears {@link TenantContext} in {@code finally}.
 */
public class TenantFilter extends OncePerRequestFilter {

    public static final String ORG_HEADER = "X-Org-Id";

    private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    public TenantFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (isAuthenticated(auth)) {
                UUID orgId = resolveOrgId(request);
                TenantContext.setOrgId(orgId);
                log.debug("TenantContext orgId={}", orgId);
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private UUID resolveOrgId(HttpServletRequest request) {
        String header = request.getHeader(ORG_HEADER);
        if (StringUtils.hasText(header)) {
            try {
                return UUID.fromString(header.trim());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid {} header value: {}", ORG_HEADER, header);
            }
        }

        String token = extractToken(request);
        if (StringUtils.hasText(token)) {
            UUID fromJwt = jwtTokenProvider.getOrgId(token);
            if (fromJwt != null) {
                return fromJwt;
            }
        }

        return TenantContext.DEFAULT_ORG_ID;
    }

    private static boolean isAuthenticated(Authentication auth) {
        return auth != null
                && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());
    }

    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
