package com.latifi.api.identity.domain;

import java.time.Instant;
import java.util.UUID;

/** A sign-in challenge. {@code message} is the exact text the wallet signs. */
public record Challenge(
    UUID id,
    String address,
    String nonce,
    String message,
    Instant issuedAt,
    Instant expiresAt,
    Instant usedAt) {}
