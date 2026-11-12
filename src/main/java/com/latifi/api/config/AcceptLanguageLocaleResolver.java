package com.latifi.api.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Picks the message language from the Accept-Language header. Any Spanish range, including es-ES
 * and es-PE, answers in neutral Latin American Spanish (es-419), and everything else, including a
 * missing, wildcard or malformed header, answers in English (en-US). The bean name must stay {@code
 * localeResolver} so DispatcherServlet uses it, and the security entry points call it directly.
 */
@Component("localeResolver")
public class AcceptLanguageLocaleResolver implements LocaleResolver {

  private static final Locale ENGLISH = Locale.of("en", "US");
  private static final Locale SPANISH = Locale.forLanguageTag("es-419");

  @Override
  public Locale resolveLocale(HttpServletRequest request) {
    String header = request.getHeader("Accept-Language");
    if (header == null || header.isBlank()) {
      return ENGLISH;
    }
    List<Locale.LanguageRange> ranges;
    try {
      ranges = Locale.LanguageRange.parse(header.replace('_', '-'));
    } catch (IllegalArgumentException e) {
      return ENGLISH;
    }
    List<Locale.LanguageRange> byWeight =
        ranges.stream()
            .filter(range -> range.getWeight() > 0)
            .sorted(Comparator.comparingDouble(Locale.LanguageRange::getWeight).reversed())
            .toList();
    for (Locale.LanguageRange range : byWeight) {
      String tag = range.getRange();
      if (tag.equals("es") || tag.startsWith("es-")) {
        return SPANISH;
      }
      if (tag.equals("en") || tag.startsWith("en-")) {
        return ENGLISH;
      }
    }
    return ENGLISH;
  }

  @Override
  public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
    throw new UnsupportedOperationException("The locale follows the Accept-Language header");
  }
}
