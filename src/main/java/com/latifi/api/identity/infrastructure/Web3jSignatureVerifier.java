package com.latifi.api.identity.infrastructure;

import com.latifi.api.identity.domain.SignatureVerifier;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

/** Recovers the signer of an EIP-191 personal_sign signature with web3j. */
@Component
public class Web3jSignatureVerifier implements SignatureVerifier {

  private static final Pattern SIGNATURE = Pattern.compile("^(0x)?[0-9a-fA-F]{130}$");
  private static final int V_OFFSET = 27;

  @Override
  public Optional<String> recoverSigner(String message, String signatureHex) {
    if (message == null || signatureHex == null || !SIGNATURE.matcher(signatureHex).matches()) {
      return Optional.empty();
    }
    try {
      byte[] signature = Numeric.hexStringToByteArray(signatureHex);
      byte v = signature[64];
      if (v < V_OFFSET) {
        v = (byte) (v + V_OFFSET);
      }
      Sign.SignatureData data =
          new Sign.SignatureData(
              v, Arrays.copyOfRange(signature, 0, 32), Arrays.copyOfRange(signature, 32, 64));
      BigInteger publicKey =
          Sign.signedPrefixedMessageToKey(message.getBytes(StandardCharsets.UTF_8), data);
      return Optional.of("0x" + Keys.getAddress(publicKey).toLowerCase(Locale.ROOT));
    } catch (SignatureException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
