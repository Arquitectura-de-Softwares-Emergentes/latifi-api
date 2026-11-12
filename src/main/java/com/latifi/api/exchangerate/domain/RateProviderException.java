package com.latifi.api.exchangerate.domain;

/**
 * The provider failed, was too slow or answered something that cannot be trusted. The message never
 * holds the provider URL or key, so it is safe to log.
 */
public class RateProviderException extends RuntimeException {

  public RateProviderException(String message) {
    super(message);
  }
}
