package com.latifi.api.identity.domain;

/** Persistence port for challenges. */
public interface ChallengeStore {

  void save(Challenge challenge);
}
