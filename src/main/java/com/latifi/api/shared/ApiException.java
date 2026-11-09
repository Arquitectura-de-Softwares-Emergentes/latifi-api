package com.latifi.api.shared;

/** A failure that the API reports to the client with a stable code. */
public class ApiException extends RuntimeException {

  private final ApiErrorCode code;
  private final transient Object[] messageArgs;

  public ApiException(ApiErrorCode code, Object... messageArgs) {
    super(code.name());
    this.code = code;
    this.messageArgs = messageArgs;
  }

  public ApiErrorCode code() {
    return code;
  }

  public Object[] messageArgs() {
    return messageArgs;
  }
}
