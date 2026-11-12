package com.latifi.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.shared.ApiErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The English and the Spanish bundle must say the same things with the same placeholders. */
class MessagesParityTest {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

  private final Properties english = load("/messages.properties");
  private final Properties spanish = load("/messages_es_419.properties");

  @Test
  void bothBundlesHaveTheSameKeys() {
    assertThat(spanish.stringPropertyNames()).isEqualTo(english.stringPropertyNames());
  }

  @Test
  void noValueIsBlank() {
    for (Properties bundle : new Properties[] {english, spanish}) {
      for (String key : bundle.stringPropertyNames()) {
        assertThat(bundle.getProperty(key)).as(key).isNotBlank();
      }
    }
  }

  @Test
  void placeholdersMatchPerKey() {
    for (String key : english.stringPropertyNames()) {
      assertThat(count(spanish.getProperty(key)))
          .as(key)
          .isEqualTo(count(english.getProperty(key)));
    }
  }

  @Test
  void everyErrorCodeHasAMessageInBothBundles() {
    for (ApiErrorCode code : ApiErrorCode.values()) {
      assertThat(english.getProperty(code.messageKey())).as("en " + code).isNotNull();
      assertThat(spanish.getProperty(code.messageKey())).as("es " + code).isNotNull();
    }
  }

  @Test
  void bundlesHoldNoKeyOutsideTheCatalog() {
    assertThat(english.stringPropertyNames())
        .allMatch(key -> key.startsWith("error."))
        .hasSize(ApiErrorCode.values().length);
  }

  private static int count(String value) {
    Matcher matcher = PLACEHOLDER.matcher(value);
    int total = 0;
    while (matcher.find()) {
      total++;
    }
    return total;
  }

  private static Properties load(String resource) {
    try (InputStream in = MessagesParityTest.class.getResourceAsStream(resource)) {
      assertThat(in).as(resource).isNotNull();
      Properties properties = new Properties();
      try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
        properties.load(reader);
      }
      return properties;
    } catch (IOException e) {
      throw new AssertionError("Cannot read " + resource, e);
    }
  }
}
