package com.latifi.api.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.model.Request;
import com.fasterxml.jackson.databind.JsonNode;
import com.latifi.api.bdd.support.ApiClient;
import com.latifi.api.bdd.support.Wallets;
import com.latifi.api.support.ContractAssertions;
import com.latifi.api.support.TestExchangeRateProvider;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Steps of the exchange rate feature. The scenarios run against {@link TestExchangeRateProvider},
 * so no step reaches the network. Sessions come from {@link ProfileSteps}, the clock moves through
 * {@link AuthSteps}. A token expires after 15 minutes of the test clock, so a scenario that moves
 * the clock further logs the wallet in again.
 */
public class FxSteps {

  private static final String CONVERT = "/api/v1/exchange-rate/convert";
  private static final String RATES = "/api/v1/exchange-rate/rates";

  private final ApiClient api;
  private final TestExchangeRateProvider provider;
  private final Wallets wallets;
  private final JdbcTemplate jdbc;

  private String lastPath = CONVERT;

  public FxSteps(
      ApiClient api, TestExchangeRateProvider provider, Wallets wallets, JdbcTemplate jdbc) {
    this.api = api;
    this.provider = provider;
    this.wallets = wallets;
    this.jdbc = jdbc;
  }

  // ---------------------------------------------------------------- the provider

  @Given("the rate provider holds the standard rates published at {string}")
  public void standardRates(String publishedAt) {
    provider.rates(TestExchangeRateProvider.STANDARD_RATES, Instant.parse(publishedAt));
  }

  @Given("the rate provider now holds the rate {string} for {word}, published at {string}")
  public void rateChanges(String rate, String currency, String publishedAt) {
    Map<String, BigDecimal> rates = new HashMap<>(TestExchangeRateProvider.STANDARD_RATES);
    rates.put(currency, new BigDecimal(rate));
    provider.rates(rates, Instant.parse(publishedAt));
  }

  @Given("the rate provider is down")
  public void providerDown() {
    provider.fail(true);
  }

  @Given("the rate provider works again")
  public void providerWorks() {
    provider.fail(false);
  }

  @Then("the rate provider was called {int} time(s)")
  public void providerCalls(int expected) {
    assertThat(provider.calls()).isEqualTo(expected);
  }

  @Then("the cache holds one row for each of the currencies {string}")
  public void cacheRows(String currencies) {
    List<String> stored =
        jdbc.queryForList(
            "select trim(currency) from exchange_rate_cache order by 1", String.class);
    assertThat(stored).containsExactlyInAnyOrder(currencies.split(","));
  }

  // ---------------------------------------------------------------- reads

  @When("the wallet {word} converts {string} base units to {word}")
  public void converts(String reader, String amount, String currency) {
    get(wallets.tokens().get(reader), CONVERT, "amount=" + amount + "&currency=" + currency);
  }

  @When("the wallet {word} converts {string} base units to {word} {int} times")
  public void convertsSeveralTimes(String reader, String amount, String currency, int times) {
    for (int i = 0; i < times; i++) {
      converts(reader, amount, currency);
    }
  }

  @When("the wallet {word} converts with the query {string}")
  public void convertsWithQuery(String reader, String query) {
    get(wallets.tokens().get(reader), CONVERT, query);
  }

  @When("the wallet {word} reads the exchange rates")
  public void readsRates(String reader) {
    get(wallets.tokens().get(reader), RATES, "");
  }

  @When("an anonymous client converts {string} base units to {word}")
  public void anonymousConverts(String amount, String currency) {
    get(null, CONVERT, "amount=" + amount + "&currency=" + currency);
  }

  @When("an anonymous client reads the exchange rates")
  public void anonymousReadsRates() {
    get(null, RATES, "");
  }

  // ---------------------------------------------------------------- checks

  @Then("the exchange response conforms to the API contract")
  public void conforms() {
    ContractAssertions.assertResponseConforms(
        Request.Method.GET, lastPath, api.status(), api.header("content-type"), api.body());
  }

  @Then("the conversion is {string} {word} at the rate {string}")
  public void conversionIs(String value, String currency, String rate) {
    JsonNode body = api.json();
    assertThat(body.get("currency").asText()).as("body: %s", api.body()).isEqualTo(currency);
    assertThat(body.get("value").asText()).isEqualTo(value);
    assertThat(body.get("rate").asText()).isEqualTo(rate);
  }

  @Then("the conversion reports the amount {string}")
  public void conversionAmount(String amount) {
    assertThat(api.json().get("amount").asText()).isEqualTo(amount);
  }

  @Then("the conversion is published at {string} and stale is {word}")
  public void conversionTime(String publishedAt, String stale) {
    JsonNode body = api.json();
    assertThat(Instant.parse(body.get("asOf").asText())).isEqualTo(Instant.parse(publishedAt));
    assertThat(body.get("stale").asBoolean()).as("body: %s", api.body()).isEqualTo(parse(stale));
  }

  @Then("the exchange rates list shows these quotes:")
  public void ratesList(DataTable table) {
    JsonNode body = api.json();
    assertThat(body.isArray()).as("body: %s", api.body()).isTrue();
    List<Map<String, String>> expected = table.asMaps();
    assertThat(body).hasSize(expected.size());
    List<String> order = new ArrayList<>();
    for (int i = 0; i < expected.size(); i++) {
      JsonNode quote = body.get(i);
      Map<String, String> row = expected.get(i);
      order.add(quote.get("quote").asText());
      assertThat(quote.get("base").asText()).isEqualTo("USD");
      assertThat(quote.get("quote").asText()).isEqualTo(row.get("quote"));
      assertThat(quote.get("rate").asText()).isEqualTo(row.get("rate"));
      assertThat(quote.get("stale").asBoolean()).isEqualTo(parse(row.get("stale")));
      assertThat(Instant.parse(quote.get("asOf").asText()))
          .isEqualTo(Instant.parse(row.get("asOf")));
    }
    assertThat(order).hasSize(expected.size());
  }

  @Then("every exchange quote is stale")
  public void everyQuoteStale() {
    JsonNode body = api.json();
    assertThat(body).hasSize(6);
    body.forEach(quote -> assertThat(quote.get("stale").asBoolean()).isTrue());
  }

  // ---------------------------------------------------------------- helpers

  private void get(String token, String path, String query) {
    lastPath = path;
    api.withBearer(token);
    api.get(query.isEmpty() ? path : path + "?" + query);
  }

  private static boolean parse(String flag) {
    assertThat(flag).isIn("true", "false");
    return Boolean.parseBoolean(flag);
  }
}
