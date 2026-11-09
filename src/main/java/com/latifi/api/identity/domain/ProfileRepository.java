package com.latifi.api.identity.domain;

import java.util.Optional;

/** Port for profile storage. Addresses are lowercase. */
public interface ProfileRepository {

  Optional<Profile> find(String address);

  /** Inserts the profile or replaces its values, and keeps the original creation time. */
  UpsertResult upsert(Profile profile);

  /** The stored profile and whether this call created it. */
  record UpsertResult(Profile profile, boolean created) {}
}
