package com.latifi.api.support;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Empties every table of the public schema except the Flyway history. */
@Component
public class DatabaseReset {

  private final JdbcTemplate jdbc;

  public DatabaseReset(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void run() {
    List<String> tables =
        jdbc.queryForList(
            """
            select table_name from information_schema.tables
            where table_schema = 'public'
              and table_type = 'BASE TABLE'
              and table_name <> 'flyway_schema_history'
            """,
            String.class);
    if (tables.isEmpty()) {
      return;
    }
    String quoted = String.join(", ", tables.stream().map(t -> "\"" + t + "\"").toList());
    jdbc.execute("truncate table " + quoted + " restart identity cascade");
  }
}
