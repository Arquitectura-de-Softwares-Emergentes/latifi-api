package com.latifi.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.bdd.support.TestWallet;
import com.latifi.api.identity.infrastructure.Web3jSignatureVerifier;
import org.junit.jupiter.api.Test;

class Web3jSignatureVerifierTest {

  private final Web3jSignatureVerifier verifier = new Web3jSignatureVerifier();

  @Test
  void returnsTheAddressOfTheWalletThatSigned() {
    TestWallet wallet = TestWallet.create();
    String signature = wallet.sign("hello latifi");

    assertThat(verifier.recoverSigner("hello latifi", signature)).contains(wallet.address());
  }

  @Test
  void acceptsRecoveryIdsOfZeroAndOne() {
    TestWallet wallet = TestWallet.create();
    String signature = wallet.sign("hello latifi");
    int v = Integer.parseInt(signature.substring(signature.length() - 2), 16) - 27;
    String shortened = signature.substring(0, signature.length() - 2) + "%02x".formatted(v);

    assertThat(verifier.recoverSigner("hello latifi", shortened)).contains(wallet.address());
  }

  @Test
  void aChangedMessageGivesAnotherAddress() {
    TestWallet wallet = TestWallet.create();
    String signature = wallet.sign("hello latifi");

    assertThat(verifier.recoverSigner("hello latifi!", signature))
        .isNotEqualTo(java.util.Optional.of(wallet.address()));
  }

  @Test
  void returnsEmptyForABrokenSignature() {
    String sixtyFourBytes = "0x" + "11".repeat(64);
    String notHex = "0x" + "zz".repeat(65);

    assertThat(verifier.recoverSigner("text", sixtyFourBytes)).isEmpty();
    assertThat(verifier.recoverSigner("text", notHex)).isEmpty();
    assertThat(verifier.recoverSigner("text", "")).isEmpty();
    assertThat(verifier.recoverSigner("text", null)).isEmpty();
  }
}
