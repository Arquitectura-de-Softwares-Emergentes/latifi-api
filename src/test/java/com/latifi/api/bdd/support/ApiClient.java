package com.latifi.api.bdd.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.spring.ScenarioScope;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Talks to the running application over HTTP and remembers the last response. */
@Component
@ScenarioScope
public class ApiClient {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpClient http = HttpClient.newHttpClient();
  private final int port;

  private final Map<String, String> headers = new HashMap<>();
  private String bearerToken;
  private int status;
  private Map<String, List<String>> responseHeaders = Map.of();
  private String body;

  public ApiClient(@Value("${local.server.port}") int port) {
    this.port = port;
  }

  public ApiClient withHeader(String name, String value) {
    headers.put(name, value);
    return this;
  }

  public ApiClient withBearer(String token) {
    this.bearerToken = token;
    return this;
  }

  public void get(String path) {
    send("GET", path, null);
  }

  public void post(String path, String json) {
    send("POST", path, json);
  }

  public void put(String path, String json) {
    send("PUT", path, json);
  }

  private void send(String method, String path, String json) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    headers.forEach(request::header);
    if (bearerToken != null) {
      request.header("Authorization", "Bearer " + bearerToken);
    }
    if (json != null) {
      request.header("Content-Type", "application/json");
    }
    request.method(
        method,
        json == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json));
    try {
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      status = response.statusCode();
      responseHeaders = response.headers().map();
      body = response.body();
    } catch (IOException e) {
      throw new IllegalStateException("The request to " + path + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("The request to " + path + " was interrupted", e);
    }
  }

  public int status() {
    return status;
  }

  public String body() {
    return body;
  }

  public String header(String name) {
    return responseHeaders.entrySet().stream()
        .filter(e -> e.getKey().equalsIgnoreCase(name))
        .flatMap(e -> e.getValue().stream())
        .findFirst()
        .orElse(null);
  }

  public JsonNode json() {
    try {
      return JSON.readTree(body);
    } catch (IOException e) {
      throw new AssertionError("The response body is not JSON: " + body, e);
    }
  }
}
