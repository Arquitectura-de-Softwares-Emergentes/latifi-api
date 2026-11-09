package com.latifi.api.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.model.Request;
import com.fasterxml.jackson.databind.JsonNode;
import com.latifi.api.bdd.support.ApiClient;
import com.latifi.api.support.ContractAssertions;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.web3j.crypto.Keys;

public class AuthSteps {

  private static final String CHALLENGE_PATH = "/api/v1/auth/challenge";

  private final ApiClient api;
  private final JdbcTemplate jdbc;

  private String address;
  private final List<JsonNode> challenges = new ArrayList<>();

  public AuthSteps(ApiClient api, JdbcTemplate jdbc) {
    this.api = api;
    this.jdbc = jdbc;
  }

  @Given("a wallet address generated at run time")
  public void aWalletAddressGeneratedAtRunTime() throws Exception {
    address = "0x" + Keys.getAddress(Keys.createEcKeyPair());
  }

  @When("the client requests a sign-in challenge for that address")
  public void theClientRequestsAChallenge() {
    requestChallenge(address);
  }

  @When("the client requests a sign-in challenge for the address {string}")
  public void theClientRequestsAChallengeFor(String value) {
    requestChallenge(value);
  }

  @When("the client requests two sign-in challenges for that address")
  public void theClientRequestsTwoChallenges() {
    requestChallenge(address);
    requestChallenge(address);
  }

  private void requestChallenge(String value) {
    api.post(CHALLENGE_PATH, "{\"address\":\"" + value + "\"}");
    if (api.status() == 200) {
      challenges.add(api.json());
    }
  }

  @Then("the response status is {int}")
  public void theResponseStatusIs(int expected) {
    assertThat(api.status()).as("body: %s", api.body()).isEqualTo(expected);
  }

  @Then("the challenge response conforms to the API contract")
  public void theChallengeResponseConforms() {
    ContractAssertions.assertResponseConforms(
        Request.Method.POST, CHALLENGE_PATH, api.status(), api.header("content-type"), api.body());
  }

  @Then("the challenge message is a well formed sign-in text for that address")
  public void theMessageIsWellFormed() {
    JsonNode challenge = challenges.get(0);
    String[] lines = challenge.get("message").asText().split("\n", -1);
    assertThat(lines[0]).isEqualTo("localhost wants you to sign in with your Ethereum account:");
    assertThat(lines[1]).isEqualTo(Keys.toChecksumAddress(address));
    assertThat(lines)
        .contains("Version: 1", "Chain ID: 80002", "Nonce: " + challenge.get("nonce").asText());
    Instant issuedAt = Instant.parse(field(lines, "Issued At: "));
    Instant expiresAt = Instant.parse(field(lines, "Expiration Time: "));
    assertThat(Duration.between(issuedAt, expiresAt)).isEqualTo(Duration.ofMinutes(5));
    assertThat(issuedAt).isEqualTo(Instant.parse(challenge.get("issuedAt").asText()));
  }

  @Then("the stored challenge matches the response")
  public void theStoredChallengeMatches() {
    JsonNode challenge = challenges.get(0);
    Map<String, Object> row =
        jdbc.queryForMap(
            "select address, message, used_at from auth_challenge where id = ?::uuid",
            challenge.get("challengeId").asText());
    assertThat(row.get("message")).isEqualTo(challenge.get("message").asText());
    assertThat(row.get("address")).isEqualTo(address.toLowerCase());
    assertThat(row.get("used_at")).isNull();
  }

  @Then("the two nonces differ and have 32 hex characters")
  public void theNoncesDiffer() {
    assertThat(challenges).hasSize(2);
    String first = challenges.get(0).get("nonce").asText();
    String second = challenges.get(1).get("nonce").asText();
    assertThat(first).isNotEqualTo(second);
    assertThat(first).matches("[0-9a-f]{32}");
    assertThat(second).matches("[0-9a-f]{32}");
  }

  @Then("the error code is {string} with a message")
  public void theErrorCodeIs(String code) {
    JsonNode body = api.json();
    assertThat(body.get("status").asInt()).isEqualTo(api.status());
    assertThat(body.get("code").asText()).isEqualTo(code);
    assertThat(body.get("message").asText()).isNotBlank();
  }

  @Then("the error response conforms to the API contract")
  public void theErrorResponseConforms() {
    ContractAssertions.assertResponseConforms(
        Request.Method.POST, CHALLENGE_PATH, api.status(), api.header("content-type"), api.body());
  }

  private static String field(String[] lines, String prefix) {
    for (String line : lines) {
      if (line.startsWith(prefix)) {
        return line.substring(prefix.length());
      }
    }
    throw new AssertionError("Missing line " + prefix);
  }
}
