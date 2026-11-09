package com.latifi.api.identity.interfaces;

import com.latifi.api.identity.application.ChallengeService;
import com.latifi.api.identity.application.VerifyService;
import com.latifi.api.identity.application.VerifyService.Verified;
import com.latifi.api.identity.domain.Challenge;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
  private final VerifyService verification;

  public AuthController(ChallengeService challenges, VerifyService verification) {
    this.challenges = challenges;
    this.verification = verification;
  }

  @PostMapping("/challenge")
  public ChallengeResponse challenge(@Valid @RequestBody ChallengeRequest request) {
    return ChallengeResponse.from(challenges.issue(request.address()));
  }

  @PostMapping("/verify")
  public TokenResponse verify(@Valid @RequestBody VerifyRequest request) {
    Verified verified =
        verification.verify(request.challengeId(), request.address(), request.signature());
    return new TokenResponse(
        verified.token().accessToken(),
        "Bearer",
        verified.token().expiresInSeconds(),
        verified.address());
  }

  public record VerifyRequest(
      @NotNull UUID challengeId,
      @NotNull String address,
      @NotNull @Pattern(regexp = "^0x[0-9a-fA-F]{130}$") String signature) {

    @Override
    public String toString() {
      return "VerifyRequest[challengeId=" + challengeId + "]";
    }
  }

  public record TokenResponse(
      String accessToken, String tokenType, long expiresIn, String address) {

    @Override
    public String toString() {
      return "TokenResponse[expiresIn=" + expiresIn + "]";
    }
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
