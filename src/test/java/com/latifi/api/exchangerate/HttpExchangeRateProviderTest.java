package com.latifi.api.exchangerate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.latifi.api.config.FxProperties;
import com.latifi.api.exchangerate.domain.ProviderRates;
import com.latifi.api.exchangerate.domain.RateProviderException;
import com.latifi.api.exchangerate.infrastructure.HttpExchangeRateProvider;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * The real adapter against a local HTTP server that serves the shape of open.er-api.com. No test
 * here touches the network.
 */
class HttpExchangeRateProviderTest {

  private static final Set<String> SIX = Set.of("PEN", "COP", "MXN", "ARS", "BRL", "CLP");
  private static final List<String> SIX_LIST = List.of("PEN", "COP", "MXN", "ARS", "BRL", "CLP");
  private static final long UPDATED = 1_767_225_600L;
  private static final String KEY = "SECRET-KEY-8731";

  /** What the server answers. */
  private record Answer(int status, String body, Duration pause) {

    Answer(int status, String body) {
      this(status, body, Duration.ZERO);
    }
  }

  private HttpServer server;
  private final AtomicReference<Answer> answer = new AtomicReference<>();
  private final AtomicReference<String> requestedPath = new AtomicReference<>();
  private ListAppender<ILoggingEvent> logs;
  private final List<Logger> watched = new ArrayList<>();
  private final List<Level> levelsBefore = new ArrayList<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/",
        exchange -> {
          requestedPath.set(exchange.getRequestURI().getPath());
          Answer current = answer.get();
          try {
            Thread.sleep(current.pause().toMillis());
            byte[] bytes = current.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(current.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
              exchange.getResponseBody().write(bytes);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (IOException e) {
            // The client gave up, which is what the timeout test expects.
          } finally {
            exchange.close();
          }
        });
    server.start();
    answer.set(new Answer(200, body(UPDATED, "USD", "success", rates("3.438038"))));

    // Watch the application and the Spring HTTP client at DEBUG, the loudest level in practice.
    // The test server logs its own request lines elsewhere and does not count.
    logs = new ListAppender<>();
    logs.start();
    for (String name : List.of("com.latifi.api", "org.springframework.web.client")) {
      Logger logger = (Logger) LoggerFactory.getLogger(name);
      watched.add(logger);
      levelsBefore.add(logger.getLevel());
      logger.addAppender(logs);
      logger.setLevel(Level.DEBUG);
    }
  }

  @AfterEach
  void stopServer() {
    for (int i = 0; i < watched.size(); i++) {
      watched.get(i).detachAppender(logs);
      watched.get(i).setLevel(levelsBefore.get(i));
    }
    watched.clear();
    levelsBefore.clear();
    logs.stop();
    server.stop(0);
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private HttpExchangeRateProvider provider(String url, String key, Duration readTimeout) {
    return new HttpExchangeRateProvider(
        new FxProperties(
            url,
            key,
            SIX_LIST,
            Duration.ofHours(1),
            Duration.ofSeconds(2),
            readTimeout,
            Duration.ofMinutes(1)));
  }

  private HttpExchangeRateProvider provider() {
    return provider(baseUrl() + "/v6/latest/USD", "", Duration.ofSeconds(3));
  }

  /** A rates object with more currencies than the six, as the real provider sends. */
  private static String rates(String pen) {
    return rates(pen, "974.378765");
  }

  private static String rates(String pen, String clp) {
    return """
        {"USD":1,"AED":3.6725,"EUR":0.86,"GBP":0.76,"PEN":%s,"COP":3239.854719,
         "MXN":18.652374,"ARS":1545.3974,"BRL":5.502723,"CLP":%s,"JPY":154.2}
        """
        .formatted(pen, clp);
  }

  private static String body(long unix, String base, String result, String rates) {
    return """
        {"result":"%s","provider":"https://www.exchangerate-api.com",
         "documentation":"https://www.exchangerate-api.com/docs/free",
         "terms_of_use":"https://www.exchangerate-api.com/terms",
         "time_last_update_unix":%d,"time_last_update_utc":"Thu, 01 Jan 2026 00:00:00 +0000",
         "time_next_update_unix":%d,"time_next_update_utc":"Fri, 02 Jan 2026 00:00:00 +0000",
         "time_eol_unix":0,"base_code":"%s","rates":%s}
        """
        .formatted(result, unix, unix + 86_400, base, rates);
  }

  @Test
  void parsesAGoodAnswerIntoTheSixRatesAndTheUpdateTime() {
    ProviderRates parsed = provider().fetch(SIX);

    assertThat(parsed.asOf()).isEqualTo(Instant.ofEpochSecond(UPDATED));
    assertThat(parsed.rates()).containsOnlyKeys(SIX);
    assertThat(parsed.rates().get("PEN")).isEqualByComparingTo("3.438038");
    assertThat(parsed.rates().get("COP")).isEqualByComparingTo("3239.854719");
    assertThat(parsed.rates().get("MXN")).isEqualByComparingTo("18.652374");
    assertThat(parsed.rates().get("ARS")).isEqualByComparingTo("1545.3974");
    assertThat(parsed.rates().get("BRL")).isEqualByComparingTo("5.502723");
    assertThat(parsed.rates().get("CLP")).isEqualByComparingTo("974.378765");
  }

  static Stream<Arguments> badAnswers() {
    String good = rates("3.438038");
    String withoutClp =
        """
        {"PEN":3.4,"COP":3239.8,"MXN":18.6,"ARS":1545.3,"BRL":5.5}
        """;
    return Stream.of(
        Arguments.of("result is error", new Answer(200, body(UPDATED, "USD", "error", good))),
        Arguments.of("HTTP 429", new Answer(429, "{\"result\":\"error\"}")),
        Arguments.of("HTTP 500", new Answer(500, "")),
        Arguments.of("a redirect", new Answer(302, "")),
        Arguments.of("a body that is not JSON", new Answer(200, "<html>maintenance</html>")),
        Arguments.of("an empty body", new Answer(200, "")),
        Arguments.of("a JSON array", new Answer(200, "[1,2,3]")),
        Arguments.of(
            "a missing currency", new Answer(200, body(UPDATED, "USD", "success", withoutClp))),
        Arguments.of(
            "a rate of zero", new Answer(200, body(UPDATED, "USD", "success", rates("0")))),
        Arguments.of(
            "a negative rate", new Answer(200, body(UPDATED, "USD", "success", rates("-3.4")))),
        Arguments.of(
            "a rate written as text that is not a number",
            new Answer(200, body(UPDATED, "USD", "success", rates("\"abc\"")))),
        Arguments.of(
            "a rate written as a text number",
            new Answer(200, body(UPDATED, "USD", "success", rates("\"3.4\"")))),
        Arguments.of(
            "a null rate", new Answer(200, body(UPDATED, "USD", "success", rates("null")))),
        Arguments.of(
            "a rate too small to store",
            new Answer(200, body(UPDATED, "USD", "success", rates("0.00000000001")))),
        Arguments.of(
            "a rate too large to store",
            new Answer(200, body(UPDATED, "USD", "success", rates("1e30")))),
        Arguments.of("the base code EUR", new Answer(200, body(UPDATED, "EUR", "success", good))),
        Arguments.of(
            "a missing update time",
            new Answer(
                200, "{\"result\":\"success\",\"base_code\":\"USD\",\"rates\":" + good + "}")),
        Arguments.of(
            "an update time written as text",
            new Answer(
                200,
                "{\"result\":\"success\",\"base_code\":\"USD\",\"time_last_update_unix\":\"x\","
                    + "\"rates\":"
                    + good
                    + "}")),
        Arguments.of(
            "a missing rates object",
            new Answer(
                200,
                "{\"result\":\"success\",\"base_code\":\"USD\",\"time_last_update_unix\":1}")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("badAnswers")
  void rejectsTheWholeAnswerWhenOneThingIsWrong(String name, Answer bad) {
    answer.set(bad);

    assertThatThrownBy(() -> provider().fetch(SIX)).isInstanceOf(RateProviderException.class);
  }

  @Test
  void givesUpWhenTheServerIsSlowerThanTheReadTimeout() {
    answer.set(
        new Answer(200, body(UPDATED, "USD", "success", rates("3.4")), Duration.ofSeconds(3)));
    HttpExchangeRateProvider slow =
        provider(baseUrl() + "/v6/latest/USD", "", Duration.ofMillis(300));

    long start = System.nanoTime();
    assertThatThrownBy(() -> slow.fetch(SIX)).isInstanceOf(RateProviderException.class);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

    assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void turnsAConnectionFailureIntoAProviderException() {
    String url = baseUrl() + "/v6/" + "hidden-path" + "/latest/USD";
    server.stop(0);

    assertThatThrownBy(() -> provider(url, "", Duration.ofSeconds(1)).fetch(SIX))
        .isInstanceOf(RateProviderException.class)
        .hasMessageNotContaining("hidden-path")
        .hasMessageNotContaining("127.0.0.1");
    assertThat(allLogText()).doesNotContain("hidden-path");
  }

  @Test
  void putsTheKeyInThePathAndKeepsKeyAndPathOutOfTheLogs() {
    String template = baseUrl() + "/v6/{apiKey}/latest/USD";

    ProviderRates parsed = provider(template, KEY, Duration.ofSeconds(3)).fetch(SIX);
    assertThat(parsed.rates()).hasSize(6);
    assertThat(requestedPath.get()).isEqualTo("/v6/" + KEY + "/latest/USD");

    // Failures log too, so check the failure paths for the key as well.
    answer.set(new Answer(500, ""));
    assertThatThrownBy(() -> provider(template, KEY, Duration.ofSeconds(3)).fetch(SIX))
        .isInstanceOf(RateProviderException.class)
        .hasMessageNotContaining(KEY);
    answer.set(new Answer(200, "not json"));
    assertThatThrownBy(() -> provider(template, KEY, Duration.ofSeconds(3)).fetch(SIX))
        .isInstanceOf(RateProviderException.class)
        .hasMessageNotContaining(KEY);

    String text = allLogText();
    assertThat(text).isNotBlank().contains("127.0.0.1");
    assertThat(text).doesNotContain(KEY).doesNotContain("/v6/").doesNotContain("latest/USD");
  }

  @Test
  void refusesToStartWhenThePlaceholderHasNoKey() {
    String template = "https://rates.example.org/v6/{apiKey}/latest/USD";

    for (String blank : new String[] {"", "   ", null}) {
      assertThatThrownBy(
              () ->
                  new FxProperties(
                      template,
                      blank,
                      SIX_LIST,
                      Duration.ofHours(1),
                      Duration.ofSeconds(2),
                      Duration.ofSeconds(3),
                      Duration.ofMinutes(1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("api-key");
    }
  }

  @Test
  void keepsTheKeyAndTheUrlOutOfTheDescriptionOfTheSettings() {
    FxProperties properties =
        new FxProperties(
            "https://rates.example.org/v6/{apiKey}/latest/USD",
            KEY,
            SIX_LIST,
            Duration.ofHours(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
            Duration.ofMinutes(1));

    assertThat(properties.toString()).doesNotContain(KEY).doesNotContain("rates.example.org");
    assertThat(properties.resolvedUrl()).contains(KEY);
  }

  @Test
  void rejectsSettingsOutOfRange() {
    Duration one = Duration.ofSeconds(1);
    String url = "https://rates.example.org/latest/USD";

    assertThatThrownBy(() -> new FxProperties(url, "", List.of(), one, one, one, one))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FxProperties(url, "", List.of("pen"), one, one, one, one))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FxProperties(url, "", SIX_LIST, Duration.ZERO, one, one, one))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new FxProperties("ftp://rates.example.org/x", "", SIX_LIST, one, one, one, one))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FxProperties(" ", "", SIX_LIST, one, one, one, one))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private String allLogText() {
    StringBuilder text = new StringBuilder();
    for (ILoggingEvent event : List.copyOf(logs.list)) {
      text.append(event.getFormattedMessage()).append('\n');
      if (event.getThrowableProxy() != null) {
        text.append(event.getThrowableProxy().getMessage()).append('\n');
      }
    }
    return text.toString();
  }
}
