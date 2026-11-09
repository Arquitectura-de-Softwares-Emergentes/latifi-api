package com.latifi.api.bdd;

import com.latifi.api.support.DatabaseReset;
import com.latifi.api.support.MutableClock;
import com.latifi.api.support.TestBeansConfig;
import io.cucumber.java.Before;

/** Every scenario starts from empty tables and the same fixed time. */
public class Hooks {

  private final DatabaseReset databaseReset;
  private final MutableClock clock;

  public Hooks(DatabaseReset databaseReset, MutableClock clock) {
    this.databaseReset = databaseReset;
    this.clock = clock;
  }

  @Before
  public void resetState() {
    databaseReset.run();
    clock.set(TestBeansConfig.START);
  }
}
