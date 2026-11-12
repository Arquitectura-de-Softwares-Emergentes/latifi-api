package com.latifi.api.reputation.infrastructure;

import com.latifi.api.reputation.domain.ProfileSignalSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the creation time of a profile. It never reads the name or the contact. */
@Repository
public class JdbcProfileSignalSource implements ProfileSignalSource {

  private final JdbcClient jdbc;

  public JdbcProfileSignalSource(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Instant> profileCreatedAt(String address) {
    return jdbc.sql("select created_at from profile where address = :address")
        .param("address", address)
        .query((rs, row) -> rs.getObject("created_at", OffsetDateTime.class).toInstant())
        .optional();
  }
}
