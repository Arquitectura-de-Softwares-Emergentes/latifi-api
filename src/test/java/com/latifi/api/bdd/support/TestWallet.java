package com.latifi.api.bdd.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

/** A wallet with a key pair created at run time. It signs challenges and logs in. */
public final class TestWallet {

  private final ECKeyPair keyPair;
  private final String address;

  private TestWallet(ECKeyPair keyPair) {
    this.keyPair = keyPair;
    this.address = "0x" + Keys.getAddress(keyPair).toLowerCase(Locale.ROOT);
  }

  public static TestWallet create() {
    try {
      return new TestWallet(Keys.createEcKeyPair());
    } catch (Exception e) {
      throw new IllegalStateException("Could not create a test key pair", e);
    }
  }

  /** The lowercase {@code 0x} address. */
  public String address() {
    return address;
  }

  public String checksumAddress() {
    return Keys.toChecksumAddress(address);
  }

  /** Signs with personal_sign and returns r, s and v as one 0x hex string of 65 bytes. */
  public String sign(String message) {
    Sign.SignatureData data =
        Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8), keyPair);
    byte[] all = new byte[65];
    System.arraycopy(data.getR(), 0, all, 0, 32);
    System.arraycopy(data.getS(), 0, all, 32, 32);
    all[64] = data.getV()[0];
    return Numeric.toHexString(all);
  }

  /** Runs challenge, sign and verify against the API and returns the access token. */
  public String login(ApiClient api) {
    api.post("/api/v1/auth/challenge", "{\"address\":\"" + address + "\"}");
    JsonNode challenge = api.json();
    String body =
        "{\"challengeId\":\"%s\",\"address\":\"%s\",\"signature\":\"%s\"}"
            .formatted(
                challenge.get("challengeId").asText(),
                address,
                sign(challenge.get("message").asText()));
    api.post("/api/v1/auth/verify", body);
    if (api.status() != 200) {
      throw new IllegalStateException("Login failed with status " + api.status());
    }
    return api.json().get("accessToken").asText();
  }
}
