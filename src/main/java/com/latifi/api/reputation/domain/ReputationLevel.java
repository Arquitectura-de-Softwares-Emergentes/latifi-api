package com.latifi.api.reputation.domain;

/**
 * A label for a range of the score. The ranges are those of the report multiplied by ten: NEW 0 to
 * 399, BUILDING 400 to 599, TRUSTED 600 to 799 and EXCELLENT 800 to 1000.
 */
public enum ReputationLevel {
  NEW(0),
  BUILDING(1),
  TRUSTED(2),
  EXCELLENT(3);

  private static final int BUILDING_FROM = 400;
  private static final int TRUSTED_FROM = 600;
  private static final int EXCELLENT_FROM = 800;

  private final int rank;

  ReputationLevel(int rank) {
    this.rank = rank;
  }

  /** Position of the level from 0 (NEW) to 3 (EXCELLENT). */
  public int rank() {
    return rank;
  }

  /**
   * The level of a score between 0 and 1000, without the rule for addresses with no chain signal.
   */
  public static ReputationLevel ofScore(int score) {
    if (score < ReputationPolicy.MIN_SCORE || score > ReputationPolicy.MAX_SCORE) {
      throw new IllegalArgumentException("score out of range: " + score);
    }
    if (score >= EXCELLENT_FROM) {
      return EXCELLENT;
    }
    if (score >= TRUSTED_FROM) {
      return TRUSTED;
    }
    if (score >= BUILDING_FROM) {
      return BUILDING;
    }
    return NEW;
  }

  public static ReputationLevel fromRank(int rank) {
    for (ReputationLevel level : values()) {
      if (level.rank == rank) {
        return level;
      }
    }
    throw new IllegalArgumentException("unknown level rank: " + rank);
  }
}
