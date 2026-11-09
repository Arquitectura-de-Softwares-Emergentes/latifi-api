package com.latifi.api.identity.application;

import com.latifi.api.identity.domain.Challenge;
import com.latifi.api.identity.domain.ChallengeStore;
import com.latifi.api.identity.domain.SignatureVerifier;
import com.latifi.api.identity.domain.TokenIssuer;
import com.latifi.api.identity.domain.TokenIssuer.IssuedToken;
import com.latifi.api.shared.ApiErrorCode;
import com.latifi.api.shared.ApiException;
import com.latifi.api.shared.EthAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Trades a signed challenge for a session token. The signer is recovered from the text that the
 * server stored, never from client data, and the challenge is consumed only after the signer
 * matches. Every rejection throws the same exception, so a caller cannot tell the reasons apart.
 */
@Service
public class VerifyService {

  private final ChallengeStore store;
  private final SignatureVerifier verifier;
  private final TokenIssuer issuer;
  private final Clock clock;

  public VerifyService(
      ChallengeStore store, SignatureVerifier verifier, TokenIssuer issuer, Clock clock) {
    this.store = store;
    this.verifier = verifier;
    this.issuer = issuer;
    this.clock = clock;
  }

  public Verified verify(UUID challengeId, String rawAddress, String signature) {
    EthAddress declared = EthAddress.parse(rawAddress);
    Instant now = clock.instant();
    Challenge challenge = store.find(challengeId).orElseThrow(VerifyService::rejected);
    if (!challenge.expiresAt().isAfter(now)
        || challenge.usedAt() != null
        || !challenge.address().equals(declared.lowercase())) {
      throw rejected();
    }
    String signer =
        verifier.recoverSigner(challenge.message(), signature).orElseThrow(VerifyService::rejected);
    if (!signer.equals(declared.lowercase()) || !signer.equals(challenge.address())) {
      throw rejected();
    }
    if (!store.consume(challengeId, now)) {
      throw rejected();
    }
    IssuedToken token = issuer.issue(declared.lowercase());
    return new Verified(token, declared.lowercase());
  }

  private static ApiException rejected() {
    return new ApiException(ApiErrorCode.INVALID_CREDENTIALS);
  }

  /** The result of a successful verification. */
  public record Verified(IssuedToken token, String address) {}
}
