package com.latifi.api.identity.domain;

import java.time.Instant;

/** Renders the EIP-4361 sign-in text. Lines are separated by LF only. */
public final class ChallengeMessage {

  static final String STATEMENT =
      "Sign in to LatiFi. This request does not trigger a blockchain transaction or cost any fee.";

  private ChallengeMessage() {}

  public static String render(
      String domain,
      String uri,
      String checksumAddress,
      long chainId,
      String nonce,
      Instant issuedAt,
      Instant expiresAt) {
    return String.join(
        "\n",
        domain + " wants you to sign in with your Ethereum account:",
        checksumAddress,
        "",
        STATEMENT,
        "",
        "URI: " + uri,
        "Version: 1",
        "Chain ID: " + chainId,
        "Nonce: " + nonce,
        "Issued At: " + issuedAt,
        "Expiration Time: " + expiresAt);
  }
}
