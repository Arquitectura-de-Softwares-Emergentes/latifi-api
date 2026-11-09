package com.latifi.api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Sign-in settings bound to {@code latifi.auth}. */
@ConfigurationProperties(prefix = "latifi.auth")
public record AuthProperties(String domain, String uri, Duration challengeTtl) {}
