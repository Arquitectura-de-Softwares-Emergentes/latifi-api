package com.latifi.api.bdd.steps;

import static com.latifi.api.support.ApiErrorAssertions.assertApiError;
import static com.latifi.api.support.ApiErrorAssertions.assertMessage;

import com.latifi.api.bdd.support.ApiClient;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class ErrorSteps {

  private final ApiClient api;

  public ErrorSteps(ApiClient api) {
    this.api = api;
  }

  @When("the client sends the Accept-Language header {string}")
  public void theClientSendsTheAcceptLanguageHeader(String value) {
    api.withHeader("Accept-Language", value);
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
