package com.latifi.api.identity.domain;

/** Issues the session token of a verified wallet. */
public interface TokenIssuer {

  IssuedToken issue(String address);

  /** A session token and its lifetime in seconds. */
  record IssuedToken(String accessToken, long expiresInSeconds) {

    @Override
    public String toString() {
      return "IssuedToken[expiresInSeconds=" + expiresInSeconds + "]";
    }
  }
}
