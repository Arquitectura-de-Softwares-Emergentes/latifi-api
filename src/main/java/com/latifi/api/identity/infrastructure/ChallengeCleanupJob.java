package com.latifi.api.identity.infrastructure;

import com.latifi.api.config.AuthProperties;
import com.latifi.api.identity.domain.ChallengeStore;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes challenges that expired long ago. It logs only how many rows it removed. */
@Component
public class ChallengeCleanupJob {

  private static final Logger LOG = LoggerFactory.getLogger(ChallengeCleanupJob.class);

  private final ChallengeStore store;
  private final Clock clock;
  private final AuthProperties auth;

  public ChallengeCleanupJob(ChallengeStore store, Clock clock, AuthProperties auth) {
    this.store = store;
    this.clock = clock;
    this.auth = auth;
  }

  @Scheduled(fixedDelayString = "${latifi.auth.cleanup-interval}")
  public void run() {
    int removed = store.deleteExpiredBefore(clock.instant().minus(auth.cleanupRetention()));
    LOG.info("Removed {} expired sign-in challenges", removed);
  }
}
