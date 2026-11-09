package com.latifi.api.config;

import com.latifi.api.reputation.domain.ReputationPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the validated reputation policy from the configuration properties. A value outside its
 * range makes the policy constructor throw, which stops the application context.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReputationProperties.class)
public class ReputationConfig {

  @Bean
  ReputationPolicy reputationPolicy(ReputationProperties properties) {
    return new ReputationPolicy(
        properties.startScore(),
        properties.onTimeDelta(),
        properties.latePerDay(),
        properties.lateCap(),
        properties.defaultDelta(),
        properties.profileBonus(),
        properties.minScoredPrincipal());
  }
}
