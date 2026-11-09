package com.latifi.api.identity.domain;

import java.util.Optional;

/** Recovers the wallet that signed a text. */
public interface SignatureVerifier {

  /**
   * Returns the lowercase {@code 0x} address that produced the personal_sign signature, or empty
   * when the signature is malformed or cannot be recovered.
   */
  Optional<String> recoverSigner(String message, String signatureHex);
}
