package com.shadowstack.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ShadowStack Worker — asynchronous task executor for the verified language
 * modernization workbench.
 *
 * <p>Hosts task components (baseline, analysis, patch generation, verification).
 * Durable {@code ss_jobs} claiming currently runs in the API
 * ({@code JobPoller} / {@code JobEnqueueService} on {@code !demo}) until job
 * payloads carry enough context for {@link com.shadowstack.worker.tasks.VerificationTask}
 * to execute here.</p>
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@ComponentScan(basePackages = {
    "com.shadowstack.worker",
    "com.shadowstack.corpus"
})
@EntityScan(basePackages = {
    "com.shadowstack.corpus"
})
@EnableJpaRepositories(basePackages = {
    "com.shadowstack.corpus"
})
public class ShadowStackWorker {

    private static final Logger LOG = LoggerFactory.getLogger(ShadowStackWorker.class);

    public static void main(String[] args) {
        LOG.info("╔══════════════════════════════════════════════════════════╗");
        LOG.info("║          ShadowStack Worker Service Starting            ║");
        LOG.info("║  Verified Language Modernization — Task Executor        ║");
        LOG.info("╚══════════════════════════════════════════════════════════╝");

        SpringApplication app = new SpringApplication(ShadowStackWorker.class);
        app.setAdditionalProfiles("worker");
        app.run(args);

        LOG.info("ShadowStack Worker is ready (task beans loaded; ss_jobs poller hosted by API !demo profile)");
    }
}
