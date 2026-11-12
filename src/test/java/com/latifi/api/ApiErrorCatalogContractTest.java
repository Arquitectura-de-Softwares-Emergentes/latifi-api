package com.latifi.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.latifi.api.shared.ApiErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.yaml.snakeyaml.Yaml;

/** Keeps the Java catalog and the {@code x-error-codes} list of the contract from drifting. */
class ApiErrorCatalogContractTest {

  private static final Map<String, HttpStatus> EXPECTED = new LinkedHashMap<>();

  static {
    EXPECTED.put("INVALID_ADDRESS", HttpStatus.BAD_REQUEST);
    EXPECTED.put("VALIDATION_FAILED", HttpStatus.BAD_REQUEST);
    EXPECTED.put("MALFORMED_REQUEST", HttpStatus.BAD_REQUEST);
    EXPECTED.put("UNSUPPORTED_CURRENCY", HttpStatus.BAD_REQUEST);
    EXPECTED.put("INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED);
    EXPECTED.put("UNAUTHORIZED", HttpStatus.UNAUTHORIZED);
    EXPECTED.put("FORBIDDEN_NOT_OWNER", HttpStatus.FORBIDDEN);
    EXPECTED.put("NOT_FOUND", HttpStatus.NOT_FOUND);
    EXPECTED.put("PROFILE_NOT_FOUND", HttpStatus.NOT_FOUND);
    EXPECTED.put("REPUTATION_NOT_FOUND", HttpStatus.NOT_FOUND);
    EXPECTED.put("LOAN_NOT_FOUND", HttpStatus.NOT_FOUND);
    EXPECTED.put("METHOD_NOT_ALLOWED", HttpStatus.METHOD_NOT_ALLOWED);
    EXPECTED.put("UNSUPPORTED_MEDIA_TYPE", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    EXPECTED.put("TOO_MANY_REQUESTS", HttpStatus.TOO_MANY_REQUESTS);
    EXPECTED.put("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR);
    EXPECTED.put("RATE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void enumNamesEqualTheContractList() throws IOException {
    assertThat(contractCodes()).doesNotHaveDuplicates();
    assertThat(contractCodes())
        .containsExactlyInAnyOrderElementsOf(
            Arrays.stream(ApiErrorCode.values()).map(Enum::name).collect(Collectors.toList()));
  }

  @Test
  void enumKeepsTheOrderOfTheContractList() throws IOException {
    assertThat(Arrays.stream(ApiErrorCode.values()).map(Enum::name).toList())
        .containsExactlyElementsOf(contractCodes());
  }

  @Test
  void everyCodeHasTheAgreedHttpStatus() {
    assertThat(ApiErrorCode.values()).hasSize(EXPECTED.size());
    for (ApiErrorCode code : ApiErrorCode.values()) {
      assertThat(code.status()).as(code.name()).isEqualTo(EXPECTED.get(code.name()));
    }
  }

  @SuppressWarnings("unchecked")
  private static List<String> contractCodes() throws IOException {
    try (InputStream in = Files.newInputStream(Path.of("openapi/latifi-api.v0.yaml"))) {
      Map<String, Object> root = new Yaml().load(in);
      Map<String, Object> components = (Map<String, Object>) root.get("components");
      Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
      Map<String, Object> apiError = (Map<String, Object>) schemas.get("ApiError");
      return (List<String>) apiError.get("x-error-codes");
    }
  }
}
