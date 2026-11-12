package com.latifi.api.identity.domain;

import java.time.Instant;

/** The profile of one wallet. The address is lowercase. */
public record Profile(
    String address,
    String displayName,
    ContactType contactType,
    String contactValue,
    Instant createdAt,
    Instant updatedAt) {

  /** Prints the address only, so a logged profile never shows the name or the contact. */
  @Override
  public String toString() {
    return "Profile[address=" + address + "]";
  }
}
