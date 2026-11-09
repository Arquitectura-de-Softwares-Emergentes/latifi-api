package com.latifi.api.shared;

import org.springframework.http.HttpStatus;

/** Stable machine readable error codes. The message of each one lives in messages.properties. */
public enum ApiErrorCode {
  INVALID_ADDRESS(HttpStatus.BAD_REQUEST),
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

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
