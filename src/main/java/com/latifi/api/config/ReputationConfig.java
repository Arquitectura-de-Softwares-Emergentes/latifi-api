package com.latifi.api.config;

import com.latifi.api.reputation.domain.ReputationPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the validated reputation policy from the configuration properties. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReputationProperties.class)
public class ReputationConfig {

  @Bean
  ReputationPolicy reputationPolicy(ReputationProperties properties) {
    throw new UnsupportedOperationException();
  }
}
