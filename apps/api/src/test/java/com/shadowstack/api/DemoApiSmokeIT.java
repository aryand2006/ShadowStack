package com.shadowstack.api;

import com.shadowstack.api.dto.LoginRequest;
import com.shadowstack.api.dto.LoginResponse;
import com.shadowstack.api.dto.PatchDetailResponse;
import com.shadowstack.api.dto.ProjectResponse;
import com.shadowstack.api.dto.ReviewQueueItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke IT for the demo profile: health → login → projects → review queue → detail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@TestPropertySource(properties = {
        "shadowstack.demo.seed-all-languages=false"
})
class DemoApiSmokeIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void healthLoginProjectsReviews() {
        ResponseEntity<Map> health = rest.getForEntity(url("/actuator/health"), Map.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).isNotNull();
        assertThat(health.getBody().get("status")).isEqualTo("UP");

        ResponseEntity<LoginResponse> login = rest.postForEntity(
                url("/api/v1/auth/login"),
                new LoginRequest("admin", "admin"),
                LoginResponse.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).isNotNull();
        assertThat(login.getBody().accessToken()).isNotBlank();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.getBody().accessToken());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<List<ProjectResponse>> projects = rest.exchange(
                url("/api/v1/projects"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});
        assertThat(projects.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(projects.getBody()).isNotNull();
        // DemoBootstrap seeds legacy-sample on startup.
        assertThat(projects.getBody()).isNotEmpty();

        ResponseEntity<List<ReviewQueueItem>> queue = rest.exchange(
                url("/api/v1/reviews/queue"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});
        assertThat(queue.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queue.getBody()).isNotNull();

        if (!queue.getBody().isEmpty()) {
            ReviewQueueItem first = queue.getBody().get(0);
            ResponseEntity<PatchDetailResponse> detail = rest.exchange(
                    url("/api/v1/reviews/" + first.patchId()),
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    PatchDetailResponse.class);
            assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(detail.getBody()).isNotNull();
            assertThat(detail.getBody().unifiedDiff()).isNotBlank();
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
