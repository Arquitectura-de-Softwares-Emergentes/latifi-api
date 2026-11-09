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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.latifi.api.bdd.support.ApiClient;
import com.latifi.api.bdd.support.TestWallet;
import com.latifi.api.identity.domain.ProfileSaved;
import com.latifi.api.support.ContractAssertions;
import com.latifi.api.support.TestBeansConfig.ProfileEventCollector;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

public class ProfileSteps {

  private static final String PROFILES = "/api/v1/profiles/";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final ApiClient api;
  private final JdbcTemplate jdbc;
  private final ProfileEventCollector collector;

  private final Map<String, TestWallet> wallets = new HashMap<>();
  private final Map<String, String> tokens = new HashMap<>();

  private Request.Method lastMethod;
  private String lastPath;
  private boolean checksumPath;
  private final List<JsonNode> remembered = new ArrayList<>();

  private ListAppender<ILoggingEvent> logCapture;
  private Level rootLevelBefore;
  private String capturedName;
  private String capturedContact;

  public ProfileSteps(ApiClient api, JdbcTemplate jdbc, ProfileEventCollector collector) {
    this.api = api;
    this.jdbc = jdbc;
    this.collector = collector;
  }

  @Given("the wallet {word} is generated at run time")
  public void walletIsGenerated(String name) {
    wallets.put(name, TestWallet.create());
  }

  @Given("the wallet {word} is logged in")
  public void walletIsLoggedIn(String name) {
    api.withBearer(null);
    tokens.put(name, wallet(name).login(api));
  }

  @When("the wallet {word} saves its own profile with name {string} and {word} contact {string}")
  public void walletSavesOwnProfile(String name, String displayName, String type, String value) {
    put(name, name, body(displayName, type, value));
  }

  @When("an anonymous client reads the profile of {word}")
  public void anonymousReads(String target) {
    get(null, PROFILES + wallet(target).address());
  }

  @When("the wallet {word} reads the profile of {word}")
  public void walletReads(String reader, String target) {
    get(tokens.get(reader), PROFILES + wallet(target).address());
  }

  @Then("the profile response conforms to the API contract")
  public void responseConforms() {
    ContractAssertions.assertResponseConforms(
        lastMethod, lastPath, api.status(), api.header("content-type"), api.body());
  }

  @Then("the profile body has the display name {string}")
  public void bodyHasName(String expected) {
    assertThat(api.json().get("displayName").asText()).isEqualTo(expected);
  }

  @Then("the profile body has no contact key")
  public void bodyHasNoContact() {
    assertThat(api.json().has("contact")).as("body: %s", api.body()).isFalse();
  }

  @Then("the profile body has the contact {word} {string}")
  public void bodyHasContact(String type, String value) {
    JsonNode contact = api.json().get("contact");
    assertThat(contact).as("body: %s", api.body()).isNotNull();
    assertThat(contact.get("type").asText()).isEqualTo(type);
    assertThat(contact.get("value").asText()).isEqualTo(value);
  }

  @Then("the profile row of {word} has the lowercase address")
  public void rowHasLowercaseAddress(String name) {
    String stored = jdbc.queryForObject("select address from profile", String.class);
    assertThat(stored).isEqualTo(wallet(name).address()).isEqualTo(stored.toLowerCase());
  }

  @When(
      "the wallet {word} saves the profile of {word} with name {string} and {word} contact {string}")
  public void walletSavesProfileOfOther(
      String caller, String owner, String displayName, String type, String value) {
    put(caller, owner, body(displayName, type, value));
  }

  @When(
      "an anonymous client saves the profile of {word} with name {string} and {word} contact {string}")
  public void anonymousSaves(String owner, String displayName, String type, String value) {
    put(null, owner, body(displayName, type, value));
  }

  @When(
      "the wallet {word} saves its profile under the checksummed address with name {string} and {word} contact {string}")
  public void walletSavesUnderChecksum(String name, String displayName, String type, String value) {
    checksumPath = true;
    put(name, name, body(displayName, type, value));
    checksumPath = false;
  }

  @When("the wallet {word} saves a profile where the {word} is {string}")
  public void walletSavesInvalidProfile(String name, String field, String rawValue) {
    String value = expand(rawValue);
    ObjectNode root = JSON.createObjectNode();
    root.put("displayName", "Valid Name");
    ObjectNode contact = root.putObject("contact");
    contact.put("type", "EMAIL");
    contact.put("value", "valid@example.org");
    switch (field) {
      case "name" -> root.put("displayName", value);
      case "email" -> contact.put("value", value);
      case "phone" -> {
        contact.put("type", "PHONE");
        contact.put("value", value);
      }
      case "type" -> contact.put("type", value);
      case "contact" -> root.remove("contact");
      default -> throw new IllegalArgumentException("Unknown field " + field);
    }
    put(name, name, root.toString());
  }

  @When("an anonymous client reads the profile path {string}")
  public void anonymousReadsPath(String path) {
    get(null, PROFILES + path);
  }

  @When("the client reads the profile of {word} with the bearer value {string}")
  public void readWithBearerValue(String target, String bearer) {
    get(bearer, PROFILES + wallet(target).address());
  }

  @Then("the response body does not contain {string}")
  public void bodyDoesNotContain(String text) {
    assertThat(api.body()).doesNotContain(text);
  }

  @Then("no profile row exists for {word}")
  public void noRowFor(String name) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from profile where address = ?",
            Integer.class,
            wallet(name).address());
    assertThat(count).isZero();
  }

  @Then("the profile response is remembered")
  public void rememberResponse() {
    remembered.add(api.json());
  }

  @Then("the remembered profile responses keep createdAt and move updatedAt by {int} seconds")
  public void rememberedResponsesKeepCreatedAt(int seconds) {
    assertThat(remembered).hasSize(2);
    JsonNode first = remembered.get(0);
    JsonNode second = remembered.get(1);
    assertThat(second.get("createdAt").asText()).isEqualTo(first.get("createdAt").asText());
    Instant before = Instant.parse(first.get("updatedAt").asText());
    Instant after = Instant.parse(second.get("updatedAt").asText());
    assertThat(Duration.between(before, after)).isEqualTo(Duration.ofSeconds(seconds));
  }

  @Given("no profile event has been collected")
  public void noEventCollected() {
    collector.clear();
  }

  @Then(
      "{int} profile events were collected for {word} with the creation time of the first response")
  public void eventsWereCollected(int count, String name) {
    List<ProfileSaved> events = collector.events();
    assertThat(events).hasSize(count);
    Instant created = Instant.parse(remembered.get(0).get("createdAt").asText());
    for (ProfileSaved event : events) {
      assertThat(event.address()).isEqualTo(wallet(name).address());
      assertThat(event.createdAt()).isEqualTo(created);
    }
  }

  @Given("the profile log output is being captured")
  public void captureLog() {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    rootLevelBefore = root.getLevel();
    root.setLevel(Level.DEBUG);
    logCapture = new ListAppender<>();
    logCapture.start();
    root.addAppender(logCapture);
  }

  @Then("no log line contains the contact value or the display name")
  public void noLogLineLeaks() {
    List<String> lines = new ArrayList<>();
    for (ILoggingEvent event : logCapture.list) {
      lines.add(event.getFormattedMessage());
      if (event.getThrowableProxy() != null) {
        lines.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
      }
    }
    assertThat(lines).isNotEmpty();
    assertThat(capturedContact).isNotBlank();
    assertThat(capturedName).isNotBlank();
    for (String line : lines) {
      assertThat(line).doesNotContain(capturedContact);
      assertThat(line).doesNotContain(capturedName);
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

  private static final Pattern REPEAT = Pattern.compile("^repeat (.) (\\d+) times$");

  /** Lets an examples table spell long or blank values without writing them out. */
  private static String expand(String raw) {
    if ("blank".equals(raw)) {
      return "   ";
    }
    Matcher matcher = REPEAT.matcher(raw);
    if (matcher.matches()) {
      return matcher.group(1).repeat(Integer.parseInt(matcher.group(2)));
    }
    return raw;
  }

  private void put(String caller, String pathOwner, String json) {
    lastMethod = Request.Method.PUT;
    TestWallet owner = wallet(pathOwner);
    lastPath = PROFILES + (checksumPath ? owner.checksumAddress() : owner.address());
    if (logCapture != null) {
      JsonNode sent = parse(json);
      capturedName = sent.get("displayName").asText();
      capturedContact = sent.get("contact").get("value").asText();
    }
    api.withBearer(caller == null ? null : tokens.get(caller));
    api.put(lastPath, json);
  }

  private void get(String token, String path) {
    lastMethod = Request.Method.GET;
    lastPath = path;
    api.withBearer(token);
    api.get(path);
  }

  private static JsonNode parse(String json) {
    try {
      return JSON.readTree(json);
    } catch (java.io.IOException e) {
      throw new AssertionError("Not JSON: " + json, e);
    }
  }

  private TestWallet wallet(String name) {
    TestWallet wallet = wallets.get(name);
    assertThat(wallet).as("wallet %s", name).isNotNull();
    return wallet;
  }

  private static String body(String displayName, String type, String value) {
    ObjectNode root = JSON.createObjectNode();
    root.put("displayName", displayName);
    ObjectNode contact = root.putObject("contact");
    contact.put("type", type);
    contact.put("value", value);
    return root.toString();
  }
}
