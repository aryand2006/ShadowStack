package com.shadowstack.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Configuration for the ShadowStack Worker service.
 *
 * <p>Manages thread pools for asynchronous task execution and a scheduler
 * used by {@link com.shadowstack.worker.jobs.JobClaimPoller} for {@code ss_jobs} VERIFY dequeue.</p>
 */
@Configuration
public class WorkerConfig implements AsyncConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerConfig.class);

    @Value("${shadowstack.worker.pool.core-size:4}")
    private int corePoolSize;

    @Value("${shadowstack.worker.pool.max-size:8}")
    private int maxPoolSize;

    @Value("${shadowstack.worker.pool.queue-capacity:50}")
    private int queueCapacity;

    @Value("${shadowstack.worker.pool.keep-alive-seconds:120}")
    private int keepAliveSeconds;

    @Value("${shadowstack.worker.poll-interval-ms:5000}")
    private long pollIntervalMs;

    @Value("${shadowstack.worker.shutdown-timeout-seconds:60}")
    private int shutdownTimeoutSeconds;

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * Primary thread pool for executing analysis, patch generation, and verification tasks.
     * Uses a caller-runs rejection policy to provide natural backpressure when saturated.
     */
    @Bean(name = "workerTaskExecutor")
    public ThreadPoolTaskExecutor workerTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix("ss-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(shutdownTimeoutSeconds);
        executor.initialize();

        LOG.info("Worker task executor initialized: core={}, max={}, queue={}, keepAlive={}s",
                corePoolSize, maxPoolSize, queueCapacity, keepAliveSeconds);

        return executor;
    }

    /**
     * Scheduler for job-queue polling ({@code JobClaimPoller}) and heartbeat operations.
     */
    @Bean(name = "workerScheduler")
    public ThreadPoolTaskScheduler workerScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ss-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(shutdownTimeoutSeconds);
        scheduler.setErrorHandler(throwable ->
                LOG.error("Scheduled task failed: {}", throwable.getMessage(), throwable));
        scheduler.initialize();

        LOG.info("Worker scheduler initialized: poolSize=2, pollInterval={}ms", pollIntervalMs);

        return scheduler;
    }

    @Override
    public Executor getAsyncExecutor() {
        return workerTaskExecutor();
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }
}
