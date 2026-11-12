package com.latifi.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.latifi.api.shared.ApiError;
import com.latifi.api.shared.ApiErrorCode;
import com.latifi.api.shared.ApiException;
import jakarta.validation.ConstraintViolationException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

  private static final Locale ENGLISH = Locale.of("en", "US");
  private static final Locale SPANISH = Locale.forLanguageTag("es-419");

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler(bundle());

  @AfterEach
  void resetLocale() {
    LocaleContextHolder.resetLocaleContext();
  }

  @Test
  void validationFailuresAnswerValidationFailed() throws Exception {
    Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("sample", String.class);
    MethodParameter parameter = new MethodParameter(method, 0);
    Exception[] failures = {
      new MethodArgumentNotValidException(parameter, new BeanPropertyBindingResult("x", "target")),
      new ConstraintViolationException(Set.of()),
      new MethodArgumentTypeMismatchException("abc", Integer.class, "n", parameter, null),
      new MissingServletRequestParameterException("page", "int")
    };
    for (Exception failure : failures) {
      assertInBothLanguages(
          () -> handler.handleValidation(failure), ApiErrorCode.VALIDATION_FAILED);
    }
  }

  @Test
  void anUnreadableBodyAnswersMalformedRequest() {
    HttpMessageNotReadableException failure =
        new HttpMessageNotReadableException("bad json", new MockHttpInputMessage(new byte[0]));
    assertInBothLanguages(() -> handler.handleMalformed(failure), ApiErrorCode.MALFORMED_REQUEST);
  }

  @Test
  void anUnknownRouteAnswersNotFound() {
    Exception[] failures = {
      new NoResourceFoundException(HttpMethod.GET, "/nothing"),
      new NoHandlerFoundException("GET", "/nothing", HttpHeaders.EMPTY)
    };
    for (Exception failure : failures) {
      assertInBothLanguages(() -> handler.handleNotFound(failure), ApiErrorCode.NOT_FOUND);
    }
  }

  @Test
  void aWrongMethodAnswersMethodNotAllowedAndListsTheAllowedOnes() {
    HttpRequestMethodNotSupportedException failure =
        new HttpRequestMethodNotSupportedException("DELETE", Set.of("GET"));
    assertInBothLanguages(
        () -> handler.handleMethodNotAllowed(failure), ApiErrorCode.METHOD_NOT_ALLOWED);
    assertThat(handler.handleMethodNotAllowed(failure).getHeaders().getAllow())
        .containsExactly(HttpMethod.GET);
  }

  @Test
  void aWrongContentTypeAnswersUnsupportedMediaType() {
    HttpMediaTypeNotSupportedException failure =
        new HttpMediaTypeNotSupportedException("text/plain");
    assertInBothLanguages(
        () -> handler.handleUnsupportedMediaType(failure), ApiErrorCode.UNSUPPORTED_MEDIA_TYPE);
  }

  @Test
  void anApiExceptionKeepsItsOwnCode() {
    for (ApiErrorCode code : ApiErrorCode.values()) {
      assertInBothLanguages(() -> handler.handleApi(new ApiException(code)), code);
    }
  }

  @Test
  void aSpringExceptionWithItsOwnClientStatusIsNotAnInternalError() {
    ResponseEntity<ApiError> response =
        handler.handleFallback(new HttpMediaTypeNotAcceptableException("not acceptable"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().code()).isEqualTo("MALFORMED_REQUEST");
  }

  @Test
  void anUnexpectedExceptionAnswersInternalErrorWithoutDetails() {
    RuntimeException failure = new IllegalStateException("secret database detail");
    assertInBothLanguages(() -> handler.handleFallback(failure), ApiErrorCode.INTERNAL_ERROR);
    assertThat(handler.handleFallback(failure).getBody().message())
        .doesNotContain("secret", "IllegalState");
  }

  @Test
  void theMessageFollowsTheLocaleOfTheRequest() {
    LocaleContextHolder.setLocale(SPANISH);
    assertThat(handler.handleNotFound(new NoResourceFoundException(HttpMethod.GET, "/x")).getBody())
        .extracting(ApiError::message)
        .isEqualTo("El recurso no existe.");
    LocaleContextHolder.setLocale(ENGLISH);
    assertThat(handler.handleNotFound(new NoResourceFoundException(HttpMethod.GET, "/x")).getBody())
        .extracting(ApiError::message)
        .isEqualTo("The resource does not exist.");
  }

  private static void assertInBothLanguages(
      Supplier<ResponseEntity<ApiError>> call, ApiErrorCode code) {
    LocaleContextHolder.setLocale(ENGLISH);
    ResponseEntity<ApiError> english = call.get();
    LocaleContextHolder.setLocale(SPANISH);
    ResponseEntity<ApiError> spanish = call.get();
    check(english, code);
    check(spanish, code);
    assertThat(spanish.getBody().message()).isNotEqualTo(english.getBody().message());
  }

  private static void check(ResponseEntity<ApiError> response, ApiErrorCode code) {
    assertThat(response.getStatusCode()).isEqualTo(code.status());
    ApiError body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.code()).isEqualTo(code.name());
    assertThat(body.status()).isEqualTo(code.status().value());
    assertThat(body.message()).isNotBlank();
    assertThat(new ObjectMapper().valueToTree(body).size()).isEqualTo(3);
  }

  @SuppressWarnings("unused")
  private void sample(String value) {}

  private static ResourceBundleMessageSource bundle() {
    ResourceBundleMessageSource source = new ResourceBundleMessageSource();
    source.setBasename("messages");
    source.setDefaultEncoding("UTF-8");
    source.setFallbackToSystemLocale(false);
    return source;
  }
}
