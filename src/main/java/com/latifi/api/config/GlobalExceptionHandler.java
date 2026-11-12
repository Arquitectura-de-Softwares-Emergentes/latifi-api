package com.latifi.api.config;

import com.latifi.api.shared.ApiError;
import com.latifi.api.shared.ApiErrorCode;
import com.latifi.api.shared.ApiException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every failure into an {@link ApiError}. The body never carries a stack trace or an
 * exception name, and the message follows the locale of the request. Spring MVC exceptions that
 * carry their own status keep it, so an unknown route answers 404 and a wrong method answers 405.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private final MessageSource messages;

  public GlobalExceptionHandler(MessageSource messages) {
    this.messages = messages;
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> handleApi(ApiException e) {
    ResponseEntity<ApiError> response = respond(e.code(), e.messageArgs());
    if (e.retryAfterSeconds() <= 0) {
      return response;
    }
    return ResponseEntity.status(response.getStatusCode())
        .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
        .body(response.getBody());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    ConstraintViolationException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  ResponseEntity<ApiError> handleValidation(Exception e) {
    return respond(ApiErrorCode.VALIDATION_FAILED);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiError> handleMalformed(HttpMessageNotReadableException e) {
    return respond(ApiErrorCode.MALFORMED_REQUEST);
  }

  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  ResponseEntity<ApiError> handleNotFound(Exception e) {
    return respond(ApiErrorCode.NOT_FOUND);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiError> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
    ResponseEntity<ApiError> response = respond(ApiErrorCode.METHOD_NOT_ALLOWED);
    if (e.getSupportedHttpMethods() == null || e.getSupportedHttpMethods().isEmpty()) {
      return response;
    }
    return ResponseEntity.status(response.getStatusCode())
        .allow(e.getSupportedHttpMethods().toArray(new HttpMethod[0]))
        .body(response.getBody());
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ApiError> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
    return respond(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE);
  }

  /**
   * Catches what is left. A Spring MVC exception with its own status maps to the catalog code of
   * that status, and anything else is an unexpected failure that is logged on the server only.
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> handleFallback(Exception e) {
    if (e instanceof ErrorResponse errorResponse) {
      ApiErrorCode code = codeFor(errorResponse.getStatusCode());
      if (code != ApiErrorCode.INTERNAL_ERROR) {
        return respond(code);
      }
    }
    LOG.error("Unexpected failure while handling a request", e);
    return respond(ApiErrorCode.INTERNAL_ERROR);
  }

  private static ApiErrorCode codeFor(HttpStatusCode status) {
    return switch (status.value()) {
      case 404 -> ApiErrorCode.NOT_FOUND;
      case 405 -> ApiErrorCode.METHOD_NOT_ALLOWED;
      case 415 -> ApiErrorCode.UNSUPPORTED_MEDIA_TYPE;
      case 429 -> ApiErrorCode.TOO_MANY_REQUESTS;
      default ->
          status.is4xxClientError() ? ApiErrorCode.MALFORMED_REQUEST : ApiErrorCode.INTERNAL_ERROR;
    };
  }

  private ResponseEntity<ApiError> respond(ApiErrorCode code, Object... args) {
    String message = messages.getMessage(code.messageKey(), args, LocaleContextHolder.getLocale());
    return ResponseEntity.status(code.status())
        .body(new ApiError(code.status().value(), code.name(), message));
  }
}
