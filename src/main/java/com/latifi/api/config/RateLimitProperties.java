package com.latifi.api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Abuse limits of the auth endpoints, bound to {@code latifi.ratelimit}. The forwarded header is
 * trusted only behind a proxy that sets it, so the default is false.
 */
@ConfigurationProperties(prefix = "latifi.ratelimit")
public record RateLimitProperties(
    long authPerIpCapacity,
    Duration authPerIpPeriod,
    long challengePerAddressCapacity,
    Duration challengePerAddressPeriod,
    boolean trustForwardedFor) {}
