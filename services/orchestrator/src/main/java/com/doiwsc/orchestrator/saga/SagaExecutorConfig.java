package com.doiwsc.orchestrator.saga;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Bounded saga worker pool sized by ORCHESTRATOR_WORKERS. Kept small and
 * bounded so a flood of orders cannot exhaust the JVM.
 */
@Configuration
public class SagaExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public Executor sagaWorkerExecutor(
            @Value("${orchestrator.workers:4}") int workers,
            @Value("${orchestrator.queue-capacity:1000}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("saga-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
