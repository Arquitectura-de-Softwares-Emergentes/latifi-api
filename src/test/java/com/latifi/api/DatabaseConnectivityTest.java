package com.latifi.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Connects to a real PostgreSQL; the ci service container exercises it. */
@SpringBootTest
class DatabaseConnectivityTest {

  @Autowired private DataSource dataSource;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void connectsToRealPostgres() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
    }
  }

  @Test
  void selectOneReturnsOne() {
    Integer result = jdbcTemplate.queryForObject("select 1", Integer.class);
    assertThat(result).isEqualTo(1);
  }
}
