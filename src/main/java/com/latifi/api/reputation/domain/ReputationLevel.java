package com.latifi.api.reputation.domain;

/** A label for a range of the score. */
public enum ReputationLevel {
  NEW(0),
  BUILDING(1),
  TRUSTED(2),
  EXCELLENT(3);

  private final int rank;

  ReputationLevel(int rank) {
    this.rank = rank;
  }

  public int rank() {
    return rank;
  }

  public static ReputationLevel ofScore(int score) {
    throw new UnsupportedOperationException();
  }

  public static ReputationLevel fromRank(int rank) {
    throw new UnsupportedOperationException();
  }
}
