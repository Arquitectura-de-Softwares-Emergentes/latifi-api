package com.latifi.api.identity.domain;

import java.time.Instant;

/**
 * Published inside the transaction of every profile write. The reputation context listens to it for
 * the off-chain signal. {@code createdAt} is the creation time of the profile, which an update
 * never changes.
 */
public record ProfileSaved(String address, Instant createdAt) {}
