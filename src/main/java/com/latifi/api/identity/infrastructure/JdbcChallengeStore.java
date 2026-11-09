package com.latifi.api.identity.infrastructure;

import com.latifi.api.identity.domain.Challenge;
import com.latifi.api.identity.domain.ChallengeStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stores challenges in the {@code auth_challenge} table. */
@Repository
public class JdbcChallengeStore implements ChallengeStore {

  private final JdbcClient jdbc;

  public JdbcChallengeStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void save(Challenge challenge) {
    jdbc.sql(
            """
            insert into auth_challenge (id, address, nonce, message, issued_at, expires_at)
            values (:id, :address, :nonce, :message, :issuedAt, :expiresAt)
            """)
        .param("id", challenge.id())
        .param("address", challenge.address())
        .param("nonce", challenge.nonce())
        .param("message", challenge.message())
        .param("issuedAt", utc(challenge.issuedAt()))
        .param("expiresAt", utc(challenge.expiresAt()))
        .update();
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
