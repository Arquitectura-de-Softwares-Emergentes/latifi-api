package com.latifi.api.ledger.domain;

import com.latifi.api.shared.ApiErrorCode;
import com.latifi.api.shared.ApiException;

/**
 * The borrower levels as the feed filter sees them. The reputation context owns the score ranges,
 * and this enum only knows the position of each level so that {@code minLevel} can compare ranks.
 */
public enum BorrowerLevel {
  NEW(0),
  BUILDING(1),
  TRUSTED(2),
  EXCELLENT(3);

  private final int rank;

  BorrowerLevel(int rank) {
    this.rank = rank;
  }

  public int rank() {
    return rank;
  }

  /** The level with this exact name, or 400 VALIDATION_FAILED. */
  public static BorrowerLevel parse(String name) {
    for (BorrowerLevel level : values()) {
      if (level.name().equals(name)) {
        return level;
      }
    }
    throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
  }
}
