package com.latifi.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocaleResolverTest {

  private final AcceptLanguageLocaleResolver resolver = new AcceptLanguageLocaleResolver();

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "   ",
        "en-US",
        "en",
        "fr",
        "fr-FR",
        "*",
        "de;q=0.9,fr;q=0.8",
        ";;;q=abc",
        "es;q=0"
      })
  void answersEnglish(String header) {
    assertThat(resolve(header)).isEqualTo(Locale.of("en", "US"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "es",
        "es-419",
        "es_419",
        "es-PE",
        "es-MX",
        "es-ES",
        "es-419,en;q=0.5",
        "fr;q=0.9,es;q=0.8",
        "en;q=0.5,es;q=0.9"
      })
  void answersLatinAmericanSpanish(String header) {
    assertThat(resolve(header).toLanguageTag()).isEqualTo("es-419");
  }

  @ParameterizedTest
  @ValueSource(strings = {"en;q=0.9,es;q=0.5", "en-US,es-419;q=0.8"})
  void followsTheHigherWeightWhenBothLanguagesAppear(String header) {
    assertThat(resolve(header)).isEqualTo(Locale.of("en", "US"));
  }

  @Test
  void refusesToChangeTheLocale() {
    assertThatThrownBy(
            () ->
                resolver.setLocale(
                    new MockHttpServletRequest(), new MockHttpServletResponse(), Locale.ENGLISH))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private Locale resolve(String header) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (header != null) {
      request.addHeader("Accept-Language", header);
    }
    return resolver.resolveLocale(request);
  }
}
