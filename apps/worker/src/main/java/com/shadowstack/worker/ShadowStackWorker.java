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
 * <p>Owns durable {@code ss_jobs} VERIFY dequeue in prod/docker via
 * {@link com.shadowstack.worker.jobs.JobClaimPoller} ({@code FOR UPDATE SKIP LOCKED}).
 * The API remains enqueue-only when {@code shadowstack.jobs.poller-enabled=false}.
 * Also hosts baseline, analysis, patch generation, and verification task beans.</p>
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

        LOG.info("ShadowStack Worker is ready (owns ss_jobs VERIFY dequeue; 7-layer VerificationTask)");
    }
}
