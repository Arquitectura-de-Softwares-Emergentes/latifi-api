package com.latifi.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The generated documentation. It repeats the facts of {@code openapi/latifi-api.v0.yaml}: the
 * version, the bearer scheme, and the security shape of the public operations. {@code
 * OpenApiCoverageTest} compares the result with that file, so a drift fails the build.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

  static final String BEARER = "bearerAuth";

  /** Operations that need no token at all. */
  private static final Set<String> ANONYMOUS = Set.of("issueChallenge", "verifyChallenge");

  /** The token is optional, and the answer changes when it belongs to the address. */
  private static final String OPTIONAL_TOKEN = "getProfile";

  @Bean
  OpenAPI latifiOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("LatiFi API")
                .version("0.2.0")
                .description(
                    "Wallet sign-in, profiles, reputation, the loan feed and display-only exchange"
                        + " rates. Rates come from a third party. The attribution text is \"Rates By"
                        + " Exchange Rate API\" and the link is https://www.exchangerate-api.com."))
        .tags(
            List.of(
                tag(
                    "Auth",
                    "Wallet signature login. No password and no key ever reaches the server."),
                tag("Profiles", "Lightweight user profile bound to a wallet address."),
                tag("Reputation", "Hybrid reputation score and its event history."),
                tag("Loans", "Read model of the loans that the indexer mirrors from the chain."),
                tag(
                    "ExchangeRate",
                    "Display-only conversion from the stablecoin to a local currency.")))
        .schemaRequirement(
            BEARER,
            new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Session token returned by the verify operation."))
        .addSecurityItem(new SecurityRequirement().addList(BEARER));
  }

  /** Sets the security of the public operations after springdoc builds the document. */
  @Bean
  OpenApiCustomizer publicOperationsCustomizer() {
    return openApi -> {
      Paths paths = openApi.getPaths();
      if (paths == null) {
        return;
      }
      for (PathItem item : paths.values()) {
        for (Operation operation : item.readOperations()) {
          String id = operation.getOperationId();
          if (ANONYMOUS.contains(id)) {
            operation.setSecurity(List.of());
          } else if (OPTIONAL_TOKEN.equals(id)) {
            operation.setSecurity(
                List.of(new SecurityRequirement(), new SecurityRequirement().addList(BEARER)));
          }
        }
      }
    };
  }

  private static Tag tag(String name, String description) {
    return new Tag().name(name).description(description);
  }
}
