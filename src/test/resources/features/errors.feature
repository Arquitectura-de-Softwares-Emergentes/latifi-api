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
