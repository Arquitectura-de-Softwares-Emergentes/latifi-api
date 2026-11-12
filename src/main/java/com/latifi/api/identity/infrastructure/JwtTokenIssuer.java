package com.latifi.api.identity.infrastructure;

import com.latifi.api.config.JwtProperties;
import com.latifi.api.identity.domain.TokenIssuer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** Issues HS256 tokens whose subject is the lowercase wallet address. */
@Component
public class JwtTokenIssuer implements TokenIssuer {

  private final JwtEncoder encoder;
  private final Clock clock;
  private final JwtProperties properties;

  public JwtTokenIssuer(JwtEncoder encoder, Clock clock, JwtProperties properties) {
    this.encoder = encoder;
    this.clock = clock;
    this.properties = properties;
  }

  @Override
  public IssuedToken issue(String address) {
    Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    Instant expiresAt = issuedAt.plus(properties.ttl());
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(address)
            .issuer(properties.issuer())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .build();
    // Without an explicit header Nimbus picks RS256 and fails on a symmetric key.
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new IssuedToken(token, properties.ttl().toSeconds());
  }
}
