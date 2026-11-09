package com.latifi.api.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.model.Request;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.latifi.api.bdd.support.ApiClient;
import com.latifi.api.bdd.support.TestWallet;
import com.latifi.api.support.ContractAssertions;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

public class ProfileSteps {

  private static final String PROFILES = "/api/v1/profiles/";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final ApiClient api;
  private final JdbcTemplate jdbc;

  private final Map<String, TestWallet> wallets = new HashMap<>();
  private final Map<String, String> tokens = new HashMap<>();

  private Request.Method lastMethod;
  private String lastPath;

  public ProfileSteps(ApiClient api, JdbcTemplate jdbc) {
    this.api = api;
    this.jdbc = jdbc;
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

  private void put(String caller, String pathOwner, String json) {
    lastMethod = Request.Method.PUT;
    lastPath = PROFILES + wallet(pathOwner).address();
    api.withBearer(tokens.get(caller));
    api.put(lastPath, json);
  }

  private void get(String token, String path) {
    lastMethod = Request.Method.GET;
    lastPath = path;
    api.withBearer(token);
    api.get(path);
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
