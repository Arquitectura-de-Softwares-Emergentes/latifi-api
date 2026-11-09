package com.latifi.api.exchangerate.infrastructure;

import com.latifi.api.exchangerate.domain.CachedRate;
import com.latifi.api.exchangerate.domain.ProviderRates;
import com.latifi.api.exchangerate.domain.RateCache;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Keeps the rates in the {@code exchange_rate_cache} table. */
@Repository
public class JdbcRateCache implements RateCache {

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  public JdbcRateCache(JdbcClient jdbc, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  @Override
  public List<CachedRate> readAll() {
    return jdbc.sql(
            "select currency, rate, as_of, fetched_at from exchange_rate_cache order by currency")
        .query(
            (rs, rowNum) ->
                new CachedRate(
                    rs.getString("currency").trim(),
                    rs.getBigDecimal("rate"),
                    rs.getObject("as_of", OffsetDateTime.class).toInstant(),
                    rs.getObject("fetched_at", OffsetDateTime.class).toInstant()))
        .list();
  }

  @Override
  public void replaceAll(ProviderRates rates, Instant fetchedAt) {
    transactions.executeWithoutResult(
        status -> {
          for (Map.Entry<String, BigDecimal> entry : rates.rates().entrySet()) {
            jdbc.sql(
                    """
                    insert into exchange_rate_cache (currency, rate, as_of, fetched_at)
                    values (:currency, :rate, :asOf, :fetchedAt)
                    on conflict (currency) do update
                      set rate = excluded.rate,
                          as_of = excluded.as_of,
                          fetched_at = excluded.fetched_at
                    """)
                .param("currency", entry.getKey())
                .param("rate", entry.getValue())
                .param("asOf", utc(rates.asOf()))
                .param("fetchedAt", utc(fetchedAt))
                .update();
          }
        });
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
