package com.latifi.api.shared;

/** A failure that the API reports to the client with a stable code. */
public class ApiException extends RuntimeException {

  private final ApiErrorCode code;
  private final transient Object[] messageArgs;
  private final long retryAfterSeconds;

  public ApiException(ApiErrorCode code, Object... messageArgs) {
    this(code, messageArgs, 0L);
  }

  private ApiException(ApiErrorCode code, Object[] messageArgs, long retryAfterSeconds) {
    super(code.name());
    this.code = code;
    this.messageArgs = messageArgs;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /** A 429 that tells the client how many seconds to wait. */
  public static ApiException tooManyRequests(long retryAfterSeconds) {
    return new ApiException(ApiErrorCode.TOO_MANY_REQUESTS, new Object[0], retryAfterSeconds);
  }

  /** Seconds for the {@code Retry-After} header, or 0 when the failure carries none. */
  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }

  public ApiErrorCode code() {
    return code;
  }

  public Object[] messageArgs() {
    return messageArgs;
  }
}
