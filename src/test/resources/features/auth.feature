@auth
Feature: Wallet sign-in challenge
  A wallet asks for a challenge, signs it, and later trades the signature for a session.
  This feature covers the first half: issuing the challenge.

  @op-issueChallenge
  Scenario: A valid address receives a challenge
    Given a wallet address generated at run time
    When the client requests a sign-in challenge for that address
    Then the response status is 200
    And the challenge response conforms to the API contract
    And the challenge message is a well formed sign-in text for that address
    And the stored challenge matches the response

  @op-issueChallenge
  Scenario: Two challenges for one address carry different nonces
    Given a wallet address generated at run time
    When the client requests two sign-in challenges for that address
    Then the two nonces differ and have 32 hex characters

  @op-issueChallenge
  Scenario: A malformed address is rejected with INVALID_ADDRESS
    When the client requests a sign-in challenge for the address "0x123"
    Then the response status is 400
    And the error code is "INVALID_ADDRESS" with a message
    And the error response conforms to the API contract
