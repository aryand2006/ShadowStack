package com.shadowstack.api.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Mirrors {@code shadowstack.cobol.fail-on-gaps} into the system property read by
 * {@code CobolAdapter} during translate verify.
 */
@Configuration
public class CobolFailOnGapsConfig {

    private static final Logger LOG = LoggerFactory.getLogger(CobolFailOnGapsConfig.class);

    @Value("${shadowstack.cobol.fail-on-gaps:false}")
    private boolean failOnGaps;

    @PostConstruct
    void apply() {
        System.setProperty("shadowstack.cobol.fail-on-gaps", Boolean.toString(failOnGaps));
        LOG.info("shadowstack.cobol.fail-on-gaps={}", failOnGaps);
    }
}
