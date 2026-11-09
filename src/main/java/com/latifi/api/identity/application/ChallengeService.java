package com.latifi.api.identity.application;

import com.latifi.api.config.AuthProperties;
import com.latifi.api.config.ChainProperties;
import com.latifi.api.identity.domain.Challenge;
import com.latifi.api.identity.domain.ChallengeMessage;
import com.latifi.api.identity.domain.ChallengeStore;
import com.latifi.api.shared.EthAddress;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Issues sign-in challenges. The server keeps the exact text it issued. */
@Service
public class ChallengeService {

  private static final int NONCE_BYTES = 16;

  private final ChallengeStore store;
  private final Clock clock;
  private final SecureRandom random;
  private final AuthProperties auth;
  private final ChainProperties chain;

  public ChallengeService(
      ChallengeStore store,
      Clock clock,
      SecureRandom random,
      AuthProperties auth,
      ChainProperties chain) {
    this.store = store;
    this.clock = clock;
    this.random = random;
    this.auth = auth;
    this.chain = chain;
  }

  public Challenge issue(String rawAddress) {
    EthAddress address = EthAddress.parse(rawAddress);
    String nonce = newNonce();
    Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    Instant expiresAt = issuedAt.plus(auth.challengeTtl());
    String message =
        ChallengeMessage.render(
            auth.domain(), auth.uri(), address.checksum(), chain.id(), nonce, issuedAt, expiresAt);
    Challenge challenge =
        new Challenge(
            UUID.randomUUID(), address.lowercase(), nonce, message, issuedAt, expiresAt, null);
    store.save(challenge);
    return challenge;
  }

  private String newNonce() {
    byte[] bytes = new byte[NONCE_BYTES];
    random.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }
}
