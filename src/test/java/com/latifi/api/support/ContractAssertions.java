package com.latifi.api.support;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;

/**
 * Checks real responses against the OpenAPI contract file. Only the response side is validated,
 * because the request side would reject the Accept-Language form that the contract relaxes later.
 */
public final class ContractAssertions {

  private static final OpenApiInteractionValidator VALIDATOR =
      OpenApiInteractionValidator.createFor("openapi/latifi-api.v0.yaml").build();

  private ContractAssertions() {}

  public static void assertResponseConforms(
      Request.Method method, String path, int status, String contentType, String body) {
    SimpleResponse.Builder builder = SimpleResponse.Builder.status(status);
    if (contentType != null) {
      builder.withContentType(contentType);
    }
    if (body != null) {
      builder.withBody(body);
    }
    ValidationReport report = VALIDATOR.validateResponse(path, method, builder.build());
    if (report.hasErrors()) {
      throw new AssertionError(
          "Response does not match the contract: "
              + report.getMessages().stream().map(ValidationReport.Message::getMessage).toList());
    }
  }
}
