package com.latifi.api.config;

import com.latifi.api.shared.ApiError;
import com.latifi.api.shared.ApiErrorCode;
import com.latifi.api.shared.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns every failure into an {@link ApiError}. The body never carries a stack trace or an
 * exception name, and the message follows the locale of the request.
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
    return respond(e.code(), e.messageArgs());
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  ResponseEntity<ApiError> handleValidation(Exception e) {
    return respond(ApiErrorCode.VALIDATION_FAILED);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> handleUnexpected(Exception e) {
    LOG.error("Unexpected failure while handling a request", e);
    return respond(ApiErrorCode.INTERNAL_ERROR);
  }

  private ResponseEntity<ApiError> respond(ApiErrorCode code, Object... args) {
    String message = messages.getMessage(code.messageKey(), args, LocaleContextHolder.getLocale());
    return ResponseEntity.status(code.status())
        .body(new ApiError(code.status().value(), code.name(), message));
  }
}
