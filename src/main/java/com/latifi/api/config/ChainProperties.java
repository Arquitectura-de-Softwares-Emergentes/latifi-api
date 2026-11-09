package com.latifi.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Chain settings bound to {@code latifi.chain}. */
@ConfigurationProperties(prefix = "latifi.chain")
public record ChainProperties(long id) {}
