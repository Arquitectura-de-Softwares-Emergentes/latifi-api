package com.latifi.api.identity.infrastructure;

import com.latifi.api.identity.domain.ContactType;
import com.latifi.api.identity.domain.Profile;
import com.latifi.api.identity.domain.ProfileRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stores profiles in the {@code profile} table. */
@Repository
public class JdbcProfileRepository implements ProfileRepository {

  private final JdbcClient jdbc;

  public JdbcProfileRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Profile> find(String address) {
    return jdbc.sql(
            """
            select address, display_name, contact_type, contact_value, created_at, updated_at
            from profile where address = :address
            """)
        .param("address", address)
        .query(
            (rs, rowNum) ->
                new Profile(
                    rs.getString("address").trim(),
                    rs.getString("display_name"),
                    ContactType.valueOf(rs.getString("contact_type")),
                    rs.getString("contact_value"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
        .optional();
  }

  /**
   * One statement does the insert and the update. The conflict branch never touches created_at, and
   * {@code xmax = 0} is true only for a row that this statement inserted.
   */
  @Override
  public UpsertResult upsert(Profile profile) {
    UpsertRow row =
        jdbc.sql(
                """
                insert into profile
                  (address, display_name, contact_type, contact_value, created_at, updated_at)
                values (:address, :displayName, :contactType, :contactValue, :now, :now)
                ON CONFLICT (address) DO UPDATE SET
                  display_name = excluded.display_name,
                  contact_type = excluded.contact_type,
                  contact_value = excluded.contact_value,
                  updated_at = excluded.updated_at
                returning created_at, (xmax = 0) as inserted
                """)
            .param("address", profile.address())
            .param("displayName", profile.displayName())
            .param("contactType", profile.contactType().name())
            .param("contactValue", profile.contactValue())
            .param("now", profile.updatedAt().atOffset(ZoneOffset.UTC))
            .query(
                (rs, rowNum) ->
                    new UpsertRow(
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getBoolean("inserted")))
            .single();
    Profile stored =
        new Profile(
            profile.address(),
            profile.displayName(),
            profile.contactType(),
            profile.contactValue(),
            row.createdAt(),
            profile.updatedAt());
    return new UpsertResult(stored, row.inserted());
  }

  private record UpsertRow(Instant createdAt, boolean inserted) {}
}
