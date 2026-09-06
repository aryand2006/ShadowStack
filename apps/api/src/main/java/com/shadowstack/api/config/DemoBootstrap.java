package com.shadowstack.api.config;

import com.shadowstack.api.dto.ProjectCreateRequest;
import com.shadowstack.api.dto.ProjectResponse;
import com.shadowstack.api.service.ProjectService;
import com.shadowstack.api.service.RefactorOrchestrationService;
import com.shadowstack.api.service.RefactorOrchestrationService.CandidateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Seeds the in-memory store with examples/legacy-sample and runs the real
 * analyze → generate → verify pipeline so a company demo has live patches
 * in the review queue on boot.
 */
@Component
@ConditionalOnProperty(name = "shadowstack.demo.enabled", havingValue = "true")
public class DemoBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoBootstrap.class);

    private final ProjectService projectService;
    private final RefactorOrchestrationService orchestrationService;
    private final String samplePath;

    public DemoBootstrap(
            ProjectService projectService,
            RefactorOrchestrationService orchestrationService,
            @Value("${shadowstack.demo.sample-path:examples/legacy-sample}") String samplePath) {
        this.projectService = projectService;
        this.orchestrationService = orchestrationService;
        this.samplePath = samplePath;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path root = resolveSampleRoot(samplePath);
        if (!Files.isDirectory(root)) {
            log.warn("Demo sample not found at {} — skipping seed", root);
            return;
        }

        log.info("Seeding demo project from {}", root);
        ProjectResponse project = projectService.createProject(new ProjectCreateRequest(
                "legacy-sample",
                "Bundled Java 8 → modern Java conversion sample for live demos",
                root.toString(),
                "main",
                "java",
                "21"
        ));
        projectService.triggerBaseline(project.id());

        List<?> patches = orchestrationService.runFullPipeline(project.id());
        List<CandidateInfo> found = orchestrationService.getCandidates(project.id());
        long pendingCount = orchestrationService.getPendingReviewPatches().stream()
                .filter(p -> p.projectId().equals(project.id()))
                .count();

        log.info(
                "Demo ready: projectId={} candidates={} patches={} pendingReview={}",
                project.id(), found.size(), patches.size(), pendingCount
        );
    }

    static Path resolveSampleRoot(String configured) {
        Path direct = Path.of(configured);
        if (!direct.isAbsolute()) {
            Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            Path candidate = cwd.resolve(configured).normalize();
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            Path cursor = cwd;
            for (int i = 0; i < 6; i++) {
                Path probe = cursor.resolve(configured).normalize();
                if (Files.isDirectory(probe)) {
                    return probe;
                }
                Path parent = cursor.getParent();
                if (parent == null) {
                    break;
                }
                cursor = parent;
            }
            return candidate;
        }
        return direct.toAbsolutePath().normalize();
    }
}
