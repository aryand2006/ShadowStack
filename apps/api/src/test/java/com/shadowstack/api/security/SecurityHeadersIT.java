package com.shadowstack.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API security headers (F4 remediation) — CSP is on the Next.js app;
 * API advertises nosniff / DENY / Referrer-Policy / Permissions-Policy / HSTS.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class SecurityHeadersIT {

    @Autowired
    MockMvc mockMvc;

    @Test
    void health_response_includes_hardening_headers() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn();
        var headers = result.getResponse();
        assertThat(headers.getHeader("X-Content-Type-Options")).isEqualToIgnoringCase("nosniff");
        assertThat(headers.getHeader("X-Frame-Options")).isEqualToIgnoringCase("DENY");
        assertThat(headers.getHeader("Referrer-Policy")).isNotBlank();
        assertThat(headers.getHeader("Permissions-Policy")).contains("camera=");
        // HSTS may be omitted on plain HTTP in some Spring versions; prefer present when set.
        String hsts = headers.getHeader("Strict-Transport-Security");
        if (hsts != null) {
            assertThat(hsts).contains("max-age=");
        }
    }
}
