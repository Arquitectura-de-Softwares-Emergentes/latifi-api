package com.latifi.api.identity.infrastructure;

import com.latifi.api.identity.domain.Challenge;
import com.latifi.api.identity.domain.ChallengeStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
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

  @Override
  public Optional<Challenge> find(UUID id) {
    return jdbc.sql(
            """
            select id, address, nonce, message, issued_at, expires_at, used_at
            from auth_challenge where id = :id
            """)
        .param("id", id)
        .query(
            (rs, rowNum) ->
                new Challenge(
                    rs.getObject("id", UUID.class),
                    rs.getString("address").trim(),
                    rs.getString("nonce"),
                    rs.getString("message"),
                    rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                    Optional.ofNullable(rs.getObject("used_at", OffsetDateTime.class))
                        .map(OffsetDateTime::toInstant)
                        .orElse(null)))
        .optional();
  }

  @Override
  public boolean consume(UUID id, Instant now) {
    int changed =
        jdbc.sql(
                """
                update auth_challenge set used_at = :now
                where id = :id and used_at IS NULL and expires_at > :now
                """)
            .param("now", utc(now))
            .param("id", id)
            .update();
    return changed == 1;
  }

  @Override
  public int deleteExpiredBefore(Instant cutoff) {
    return jdbc.sql("delete from auth_challenge where expires_at < :cutoff")
        .param("cutoff", utc(cutoff))
        .update();
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
