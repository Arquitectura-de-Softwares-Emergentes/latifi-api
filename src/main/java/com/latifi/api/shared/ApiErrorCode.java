package com.latifi.api.shared;

import org.springframework.http.HttpStatus;

/**
 * Stable machine readable error codes, in the order of the {@code x-error-codes} list of the
 * OpenAPI contract. The message of each one lives in messages.properties and its Spanish twin.
 */
public enum ApiErrorCode {
  INVALID_ADDRESS(HttpStatus.BAD_REQUEST),
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
  MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
  UNSUPPORTED_CURRENCY(HttpStatus.BAD_REQUEST),
  INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
  FORBIDDEN_NOT_OWNER(HttpStatus.FORBIDDEN),
  NOT_FOUND(HttpStatus.NOT_FOUND),
  PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND),
  REPUTATION_NOT_FOUND(HttpStatus.NOT_FOUND),
  LOAN_NOT_FOUND(HttpStatus.NOT_FOUND),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
  TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
  RATE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);

  private final HttpStatus status;

  ApiErrorCode(HttpStatus status) {
    this.status = status;
  }

  public HttpStatus status() {
    return status;
  }

  public String messageKey() {
    return "error." + name();
  }
}
