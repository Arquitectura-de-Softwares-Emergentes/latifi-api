package com.latifi.api.identity.interfaces;

import com.latifi.api.identity.application.ChallengeService;
import com.latifi.api.identity.domain.Challenge;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Wallet sign-in. The {@code /api/v1} prefix comes from WebConfig. */
@RestController
@RequestMapping("/auth")
public class AuthController {

  private final ChallengeService challenges;

  public AuthController(ChallengeService challenges) {
    this.challenges = challenges;
  }

  @PostMapping("/challenge")
  public ChallengeResponse challenge(@Valid @RequestBody ChallengeRequest request) {
    return ChallengeResponse.from(challenges.issue(request.address()));
  }

  public record ChallengeRequest(@NotNull String address) {}

  public record ChallengeResponse(
      UUID challengeId, String nonce, String message, Instant issuedAt, Instant expiresAt) {

    static ChallengeResponse from(Challenge challenge) {
      return new ChallengeResponse(
          challenge.id(),
          challenge.nonce(),
          challenge.message(),
          challenge.issuedAt(),
          challenge.expiresAt());
    }
  }
}
