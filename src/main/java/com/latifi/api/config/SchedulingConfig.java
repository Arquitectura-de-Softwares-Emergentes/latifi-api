package com.latifi.api.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Two schedulers, so a long indexer cycle never delays the small jobs. The indexer blocks on node
 * calls, and during the first catch-up one cycle can run for minutes. The limiter eviction and the
 * challenge cleanup run on {@link #DEFAULT_SCHEDULER}, which every {@code @Scheduled} method uses
 * unless it names another one. Declaring a scheduler bean turns off the one that Spring Boot would
 * create, so the default is declared here too and keeps the {@code spring.task.scheduling}
 * settings.
 */
@Configuration(proxyBeanMethods = false)
public class SchedulingConfig {

  /** The bean name that Spring looks for when more than one scheduler exists. */
  public static final String DEFAULT_SCHEDULER = "taskScheduler";

  /** The scheduler that only the indexer uses. */
  public static final String INDEXER_SCHEDULER = "indexerTaskScheduler";

  @Bean(name = DEFAULT_SCHEDULER)
  ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
    return builder.build();
  }

  @Bean(name = INDEXER_SCHEDULER)
  @ConditionalOnChainRpc
  @ConditionalOnProperty(
      name = "latifi.indexer.enabled",
      havingValue = "true",
      matchIfMissing = true)
  ThreadPoolTaskScheduler indexerTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("indexer-");
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    return scheduler;
  }
}
