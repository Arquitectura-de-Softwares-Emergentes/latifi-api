package com.latifi.api.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.atlassian.oai.validator.model.Request;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.latifi.api.bdd.support.ApiClient;
import com.latifi.api.bdd.support.TestWallet;
import com.latifi.api.support.ContractAssertions;
import com.latifi.api.support.MutableClock;
import com.latifi.api.support.TestBeansConfig;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.web3j.crypto.Keys;

public class AuthSteps {

  private static final String CHALLENGE_PATH = "/api/v1/auth/challenge";

  private final ApiClient api;
  private final JdbcTemplate jdbc;
  private final MutableClock clock;

  private static final String VERIFY_PATH = "/api/v1/auth/verify";
  private static final String PROBE_PATH = "/api/v1/auth/session-probe";

  private String address;
  private TestWallet wallet;
  private String token;
  private String lastSignature;
  private final List<JsonNode> challenges = new ArrayList<>();
  private final List<Integer> statuses = new ArrayList<>();
  private final List<String> failureBodies = new ArrayList<>();

  private ListAppender<ILoggingEvent> logCapture;
  private Level rootLevelBefore;

  public AuthSteps(ApiClient api, JdbcTemplate jdbc, MutableClock clock) {
    this.api = api;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Given("a wallet address generated at run time")
  public void aWalletAddressGeneratedAtRunTime() throws Exception {
    address = "0x" + Keys.getAddress(Keys.createEcKeyPair());
  }

  @Given("a wallet generated at run time")
  public void aWalletGeneratedAtRunTime() {
    wallet = TestWallet.create();
    address = wallet.address();
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

  @When("the wallet signs the challenge message and verifies it")
  public void theWalletSignsAndVerifies() {
    JsonNode challenge = challenges.get(challenges.size() - 1);
    verify(
        challenge.get("challengeId").asText(),
        wallet.address(),
        wallet.sign(challenge.get("message").asText()));
  }

  private void verify(String challengeId, String declaredAddress, String signature) {
    lastSignature = signature;
    api.post(
        VERIFY_PATH,
        "{\"challengeId\":\"%s\",\"address\":\"%s\",\"signature\":\"%s\"}"
            .formatted(challengeId, declaredAddress, signature));
    if (api.status() == 200) {
      token = api.json().get("accessToken").asText();
    }
  }

  @Then("the verify response conforms to the API contract")
  public void theVerifyResponseConforms() {
    ContractAssertions.assertResponseConforms(
        Request.Method.POST, VERIFY_PATH, api.status(), api.header("content-type"), api.body());
  }

  @Then("the token response is a Bearer token of 900 seconds for the wallet address")
  public void theTokenResponseIsBearer() {
    JsonNode body = api.json();
    assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
    assertThat(body.get("expiresIn").asInt()).isEqualTo(900);
    assertThat(body.get("address").asText()).isEqualTo(wallet.address());
  }

  @Then("the token names the wallet as subject, latifi-api as issuer and lives 900 seconds")
  public void theTokenClaims() throws Exception {
    JsonNode claims = decodePayload(token);
    assertThat(claims.get("sub").asText()).isEqualTo(wallet.address());
    assertThat(claims.get("iss").asText()).isEqualTo("latifi-api");
    assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(900);
  }

  @Then("the challenge is marked as used")
  public void theChallengeIsMarkedAsUsed() {
    String id = challenges.get(challenges.size() - 1).get("challengeId").asText();
    Object usedAt =
        jdbc.queryForObject(
            "select used_at from auth_challenge where id = ?::uuid", Object.class, id);
    assertThat(usedAt).isNotNull();
  }

  @When("the client calls the session probe without a token")
  public void probeWithoutToken() {
    api.withBearer(null).get(PROBE_PATH);
  }

  @When("the client calls the session probe with the session token")
  public void probeWithToken() {
    api.withBearer(token);
    api.get(PROBE_PATH);
  }

  private static JsonNode decodePayload(String jwt) throws Exception {
    String payload = jwt.split("\\.")[1];
    byte[] json = Base64.getUrlDecoder().decode(payload);
    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
  }

  @When("another wallet signs the challenge message and verifies it")
  public void anotherWalletSignsAndVerifies() {
    JsonNode challenge = lastChallenge();
    verify(
        challenge.get("challengeId").asText(),
        wallet.address(),
        TestWallet.create().sign(challenge.get("message").asText()));
  }

  @When("another wallet verifies that challenge with its own address")
  public void anotherWalletVerifiesWithItsOwnAddress() {
    JsonNode challenge = lastChallenge();
    TestWallet other = TestWallet.create();
    verify(
        challenge.get("challengeId").asText(),
        other.address(),
        other.sign(challenge.get("message").asText()));
  }

  @When("the client verifies a challenge that does not exist")
  public void verifyUnknownChallenge() {
    verify(UUID.randomUUID().toString(), wallet.address(), wallet.sign("any text"));
  }

  @When("the wallet verifies with a recovery id of 0 or 1")
  public void verifyWithShortRecoveryId() {
    JsonNode challenge = lastChallenge();
    String signature = wallet.sign(challenge.get("message").asText());
    int v = Integer.parseInt(signature.substring(signature.length() - 2), 16) - 27;
    String shortened = signature.substring(0, signature.length() - 2) + "%02x".formatted(v);
    assertThat(v).isIn(0, 1);
    verify(challenge.get("challengeId").asText(), wallet.address(), shortened);
  }

  @When("the wallet verifies the challenge with the signature {string}")
  public void verifyWithSignature(String signature) {
    verify(lastChallenge().get("challengeId").asText(), wallet.address(), signature);
  }

  @When("the clock advances {int} seconds")
  public void theClockAdvances(int seconds) {
    clock.advance(Duration.ofSeconds(seconds));
  }

  @When("the wallet logs in")
  public void theWalletLogsIn() {
    token = wallet.login(api);
  }

  @Then("the challenge is not marked as used")
  public void theChallengeIsNotMarkedAsUsed() {
    String id = lastChallenge().get("challengeId").asText();
    Object usedAt =
        jdbc.queryForObject(
            "select used_at from auth_challenge where id = ?::uuid", Object.class, id);
    assertThat(usedAt).isNull();
  }

  @Then("the response body is remembered as a verify failure")
  public void rememberFailure() {
    failureBodies.add(api.body());
  }

  @Then("the {int} remembered verify failures are byte for byte equal")
  public void rememberedFailuresAreEqual(int count) {
    assertThat(failureBodies).hasSize(count);
    assertThat(failureBodies).as("bodies").containsOnly(failureBodies.get(0));
  }

  @When("the client calls the session probe with a token whose payload was changed")
  public void probeWithChangedPayload() throws Exception {
    String[] parts = token.split("\\.");
    ObjectMapper mapper = new ObjectMapper();
    var claims = (com.fasterxml.jackson.databind.node.ObjectNode) decodePayload(token);
    claims.put("sub", TestWallet.create().address());
    String payload =
        Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(claims));
    probeWith(parts[0] + "." + payload + "." + parts[2]);
  }

  @When("the client calls the session probe with an unsigned token whose algorithm is none")
  public void probeWithUnsignedToken() throws Exception {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
    String payload =
        encoder.encodeToString(new ObjectMapper().writeValueAsBytes(decodePayload(token)));
    probeWith(header + "." + payload + ".");
  }

  @When("the client calls the session probe with a token signed with another secret")
  public void probeWithForeignSecret() {
    var key =
        new SecretKeySpec(
            TestBeansConfig.randomSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    Instant issuedAt = clock.instant();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(wallet.address())
            .issuer("latifi-api")
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(900))
            .build();
    String forged =
        new NimbusJwtEncoder(new ImmutableSecret<>(key))
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    probeWith(forged);
  }

  private void probeWith(String bearer) {
    api.withBearer(bearer).get(PROBE_PATH);
  }

  @When("the client requests {int} sign-in challenges for different random addresses")
  public void requestManyForDifferentAddresses(int count) {
    for (int i = 0; i < count; i++) {
      requestChallenge(TestWallet.create().address());
      statuses.add(api.status());
    }
  }

  @When("the client requests {int} sign-in challenges for that address from different clients")
  public void requestManyFromDifferentClients(int count) {
    for (int i = 0; i < count; i++) {
      api.withHeader("X-Forwarded-For", "203.0.113." + (i + 1));
      requestChallenge(address);
      statuses.add(api.status());
    }
  }

  @Then("the first {int} responses were 200")
  public void theFirstResponsesWere200(int count) {
    assertThat(statuses).hasSizeGreaterThan(count);
    assertThat(statuses.subList(0, count)).containsOnly(200);
  }

  @Then("the response has a Retry-After header of at least 1 second")
  public void retryAfterIsPresent() {
    String value = api.header("retry-after");
    assertThat(value).isNotNull();
    assertThat(Long.parseLong(value)).isGreaterThanOrEqualTo(1);
  }

  @Given("the log output is being captured")
  public void captureLog() {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    rootLevelBefore = root.getLevel();
    root.setLevel(Level.DEBUG);
    logCapture = new ListAppender<>();
    logCapture.start();
    root.addAppender(logCapture);
  }

  @Then("no log line contains the signature, the access token or the token secret")
  public void noLogLineLeaks() {
    List<String> lines = new ArrayList<>();
    for (ILoggingEvent event : logCapture.list) {
      lines.add(event.getFormattedMessage());
      if (event.getThrowableProxy() != null) {
        lines.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
      }
    }
    assertThat(lines).isNotEmpty();
    assertThat(lastSignature).isNotBlank();
    assertThat(token).isNotBlank();
    for (String line : lines) {
      assertThat(line).doesNotContain(lastSignature);
      assertThat(line).doesNotContain(token);
      assertThat(line).doesNotContain(TestBeansConfig.JWT_SECRET);
    }
  }

  @After
  public void releaseLogCapture() {
    if (logCapture != null) {
      Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      root.detachAppender(logCapture);
      root.setLevel(rootLevelBefore);
      logCapture.stop();
      logCapture = null;
    }
  }

  private JsonNode lastChallenge() {
    return challenges.get(challenges.size() - 1);
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
