package com.latifi.api.bdd.steps;

import static com.latifi.api.support.ApiErrorAssertions.assertApiError;
import static com.latifi.api.support.ApiErrorAssertions.assertMessage;

import com.latifi.api.bdd.support.ApiClient;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class ErrorSteps {

  private static final String CHALLENGE_PATH = "/api/v1/auth/challenge";

  private final ApiClient api;

  public ErrorSteps(ApiClient api) {
    this.api = api;
  }

  @When("the client sends the Accept-Language header {string}")
  public void theClientSendsTheAcceptLanguageHeader(String value) {
    api.withHeader("Accept-Language", value);
  }

  @When("the client posts the body {string} to the challenge endpoint")
  public void theClientPostsTheBody(String body) {
    api.post(CHALLENGE_PATH, body);
  }

  @When("the client posts the body {string} as {string} to the challenge endpoint")
  public void theClientPostsTheBodyAs(String body, String contentType) {
    api.send("POST", CHALLENGE_PATH, body, contentType);
  }

  @When("the client requests the protected route {string} without a token")
  public void theClientRequestsTheProtectedRoute(String path) {
    api.get(path);
  }

  @When("the client calls {word} on the public route {string}")
  public void theClientCallsOnThePublicRoute(String method, String path) {
    api.sendEmpty(method, path);
  }

  @Then("the response is an ApiError with code {string}")
  public void theResponseIsAnApiErrorWithCode(String code) {
    assertApiError(api.status(), api.body(), code);
  }

  @Then("the error message is {string}")
  public void theErrorMessageIs(String text) {
    assertMessage(api.body(), text);
  }
}
