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
 * Seeds the in-memory store with every bundled legacy sample (Java, Python, COBOL,
 * JavaScript, C#) and runs the real analyze → generate → verify pipeline so a
 * company demo has live multi-language patches in the review queue on boot.
 */
@Component
@ConditionalOnProperty(name = "shadowstack.demo.enabled", havingValue = "true")
public class DemoBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoBootstrap.class);

    private record SampleSpec(
            String path,
            String name,
            String description,
            String language,
            String targetVersion
    ) {}

    private static final List<SampleSpec> SAMPLES = List.of(
            new SampleSpec(
                    "examples/legacy-sample",
                    "legacy-sample",
                    "Bundled Java 8 → modern Java conversion sample for live demos",
                    "java",
                    "21"),
            new SampleSpec(
                    "examples/legacy-python",
                    "legacy-python",
                    "Python 2 → 3 modernization sample (lib2to3 / modernize classics)",
                    "python",
                    "3.12"),
            new SampleSpec(
                    "examples/legacy-cobol",
                    "legacy-cobol",
                    "Enterprise COBOL-85 → modern control-flow / I/O sample",
                    "cobol",
                    "2002"),
            new SampleSpec(
                    "examples/legacy-javascript",
                    "legacy-javascript",
                    "CommonJS / ES5 → modern ESM JavaScript sample",
                    "javascript",
                    "ES2022"),
            new SampleSpec(
                    "examples/legacy-csharp",
                    "legacy-csharp",
                    ".NET Framework → modern C# sample",
                    "csharp",
                    "12")
    );

    private final ProjectService projectService;
    private final RefactorOrchestrationService orchestrationService;
    private final boolean seedAllLanguages;

    public DemoBootstrap(
            ProjectService projectService,
            RefactorOrchestrationService orchestrationService,
            @Value("${shadowstack.demo.seed-all-languages:true}") boolean seedAllLanguages) {
        this.projectService = projectService;
        this.orchestrationService = orchestrationService;
        this.seedAllLanguages = seedAllLanguages;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<SampleSpec> specs = seedAllLanguages ? SAMPLES : List.of(SAMPLES.get(0));
        for (SampleSpec spec : specs) {
            seed(spec);
        }
    }

    private void seed(SampleSpec spec) {
        Path root = resolveSampleRoot(spec.path());
        if (!Files.isDirectory(root)) {
            log.warn("Demo sample not found at {} — skipping {}", root, spec.name());
            return;
        }

        log.info("Seeding demo project '{}' ({}) from {}", spec.name(), spec.language(), root);
        ProjectResponse project = projectService.createProject(new ProjectCreateRequest(
                spec.name(),
                spec.description(),
                root.toString(),
                "main",
                spec.language(),
                spec.targetVersion()
        ));
        projectService.triggerBaseline(project.id());

        List<?> patches = orchestrationService.runFullPipeline(project.id());
        List<CandidateInfo> found = orchestrationService.getCandidates(project.id());
        long pendingCount = orchestrationService.getPatches(project.id()).stream()
                .filter(p -> p.status() != null && p.status().name().contains("PENDING"))
                .count();

        log.info(
                "Demo ready: name={} language={} projectId={} candidates={} patches={} pendingReview={}",
                spec.name(), spec.language(), project.id(), found.size(), patches.size(), pendingCount
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
