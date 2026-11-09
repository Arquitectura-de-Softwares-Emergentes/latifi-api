package com.latifi.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.latifi.api.shared.ApiError;
import com.latifi.api.shared.ApiErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Writes an {@link ApiError} straight to the servlet response. The Spring Security entry points run
 * before DispatcherServlet sets the locale, so this class asks the {@code localeResolver} bean for
 * the language of the request itself.
 */
@Component
class ApiErrorWriter {

  private final ObjectMapper mapper;
  private final MessageSource messages;
  private final LocaleResolver localeResolver;

  ApiErrorWriter(ObjectMapper mapper, MessageSource messages, LocaleResolver localeResolver) {
    this.mapper = mapper;
    this.messages = messages;
    this.localeResolver = localeResolver;
  }

  void write(HttpServletRequest request, HttpServletResponse response, ApiErrorCode code)
      throws IOException {
    Locale locale = localeResolver.resolveLocale(request);
    String message = messages.getMessage(code.messageKey(), null, locale);
    response.setStatus(code.status().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    mapper.writeValue(
        response.getOutputStream(), new ApiError(code.status().value(), code.name(), message));
  }
}
