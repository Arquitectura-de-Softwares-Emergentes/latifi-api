@errors
Feature: Localized error responses
  Every failure answers with one ApiError body, and the message follows the Accept-Language header.

  @op-issueChallenge
  Scenario Outline: Accept-Language selects the message language
    Given the client sends the Accept-Language header "<header>"
    When the client requests a sign-in challenge for the address "0x123"
    Then the response status is 400
    And the response is an ApiError with code "INVALID_ADDRESS"
    And the error message is "<message>"

    Examples:
      | header           | message                                              |
      | es-419           | La dirección no es una dirección de billetera válida. |
      | es_419           | La dirección no es una dirección de billetera válida. |
      | es-PE            | La dirección no es una dirección de billetera válida. |
      | es-MX,en;q=0.5   | La dirección no es una dirección de billetera válida. |
      | fr;q=0.9,es;q=0.8 | La dirección no es una dirección de billetera válida. |
      | en-US            | The address is not a valid wallet address.           |
      | fr-FR            | The address is not a valid wallet address.           |
      | *                | The address is not a valid wallet address.           |
      | ;;;q=abc         | The address is not a valid wallet address.           |
      |                  | The address is not a valid wallet address.           |

  @op-issueChallenge
  Scenario: Malformed JSON is a MALFORMED_REQUEST
    When the client posts the body "{" to the challenge endpoint
    Then the response status is 400
    And the response is an ApiError with code "MALFORMED_REQUEST"
    And the error response conforms to the API contract

  @op-issueChallenge
  Scenario: A missing address is a VALIDATION_FAILED
    When the client posts the body "{}" to the challenge endpoint
    Then the response status is 400
    And the response is an ApiError with code "VALIDATION_FAILED"
    And the error response conforms to the API contract

  @op-issueChallenge
  Scenario: A wrong content type is UNSUPPORTED_MEDIA_TYPE
    When the client posts the body "address=0x123" as "text/plain" to the challenge endpoint
    Then the response status is 415
    And the response is an ApiError with code "UNSUPPORTED_MEDIA_TYPE"

  Scenario: An unknown public route is a NOT_FOUND and not an internal error
    When the client calls GET on the public route "/swagger-ui/missing.css"
    Then the response status is 404
    And the response is an ApiError with code "NOT_FOUND"

  Scenario: A wrong method on a public route is a METHOD_NOT_ALLOWED
    When the client calls POST on the public route "/swagger-ui/swagger-ui.css"
    Then the response status is 405
    And the response is an ApiError with code "METHOD_NOT_ALLOWED"

  Scenario Outline: A protected route without a token answers 401 in the caller language
    Given the client sends the Accept-Language header "<header>"
    When the client requests the protected route "/api/v1/loans" without a token
    Then the response status is 401
    And the response is an ApiError with code "UNAUTHORIZED"
    And the error message is "<message>"

    Examples:
      | header | message                                          |
      | es-419 | Tu token de sesión falta, no es válido o venció. |
      |        | Your session token is missing, invalid or expired. |
