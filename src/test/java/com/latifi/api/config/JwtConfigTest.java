package com.latifi.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.latifi.api.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class JwtConfigTest {

  private final JwtConfig config = new JwtConfig();

  private static JwtProperties properties(String secret) {
    return new JwtProperties(secret, Duration.ofMinutes(15), "latifi-api");
  }

  @Test
  void aSecretOf31BytesStopsTheApplication() {
    assertThatThrownBy(() -> config.jwtSecretKey(properties("a".repeat(31))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32 bytes");
  }

  @Test
  void aBlankSecretStopsTheApplication() {
    assertThatThrownBy(() -> config.jwtSecretKey(properties("   ")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> config.jwtSecretKey(properties(null)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aSecretOf32BytesBuildsTheEncoderAndTheDecoder() {
    JwtProperties properties = properties("a".repeat(32));
    SecretKey key = config.jwtSecretKey(properties);

    assertThat(config.jwtEncoder(key)).isNotNull();
    assertThat(config.jwtDecoder(key, new MutableClock(Instant.EPOCH), properties)).isNotNull();
  }

  @Test
  void theTextOfTheSecretStaysOutOfToString() {
    assertThat(properties("super-secret-value-of-enough-length").toString())
        .doesNotContain("super-secret");
  }
}
