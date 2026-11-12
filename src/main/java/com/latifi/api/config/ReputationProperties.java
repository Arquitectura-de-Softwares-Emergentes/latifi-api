package com.latifi.api.config;

import java.math.BigInteger;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Reputation parameters bound to {@code latifi.reputation}. The defaults match {@code
 * docs/reputation-ddd.md}.
 */
@ConfigurationProperties(prefix = "latifi.reputation")
public record ReputationProperties(
    @DefaultValue("500") int startScore,
    @DefaultValue("20") int onTimeDelta,
    @DefaultValue("5") int latePerDay,
    @DefaultValue("100") int lateCap,
    @DefaultValue("200") int defaultDelta,
    @DefaultValue("50") int profileBonus,
    @DefaultValue("1000000") BigInteger minScoredPrincipal) {}
