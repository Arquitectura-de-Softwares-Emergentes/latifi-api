package com.latifi.api.reputation.domain;

import java.time.Instant;
import java.util.Optional;

/** Port to the off-chain signal: the creation time of the profile of an address. */
public interface ProfileSignalSource {

  /** The time the profile was created, or empty when the address has no profile. */
  Optional<Instant> profileCreatedAt(String address);
}
