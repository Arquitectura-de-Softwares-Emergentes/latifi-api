package com.latifi.api.identity.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for challenges. */
public interface ChallengeStore {

  void save(Challenge challenge);

  Optional<Challenge> find(UUID id);

  /**
   * Marks the challenge as used in one atomic step. Returns true only for the single caller that
   * finds it unused and not yet expired at {@code now}.
   */
  boolean consume(UUID id, Instant now);
}
