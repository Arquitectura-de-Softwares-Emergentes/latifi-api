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

  @op-verifyChallenge
  Scenario: A wallet that signs its challenge receives a session token
    Given a wallet generated at run time
    When the client requests a sign-in challenge for that address
    And the wallet signs the challenge message and verifies it
    Then the response status is 200
    And the verify response conforms to the API contract
    And the token response is a Bearer token of 900 seconds for the wallet address
    And the token names the wallet as subject, latifi-api as issuer and lives 900 seconds
    And the challenge is marked as used

  Scenario: A valid token passes the security chain
    Given a wallet generated at run time
    When the client requests a sign-in challenge for that address
    And the wallet signs the challenge message and verifies it
    And the client calls the session probe without a token
    Then the response status is 401
    And the error code is "UNAUTHORIZED" with a message
    When the client calls the session probe with the session token
    Then the response status is 404
    And the error code is "NOT_FOUND" with a message

  @op-verifyChallenge
  Scenario: A signature from another wallet is rejected
    Given a wallet generated at run time
    When the client requests a sign-in challenge for that address
    And another wallet signs the challenge message and verifies it
    Then the response status is 401
    And the error code is "INVALID_CREDENTIALS" with a message
    And the challenge is not marked as used

  @op-verifyChallenge
  Scenario: A used challenge cannot be used twice
    Given a wallet generated at run time
    When the client requests a sign-in challenge for that address
    And the wallet signs the challenge message and verifies it
    Then the response status is 200
    When the wallet signs the challenge message and verifies it
    Then the response status is 401
    And the error code is "INVALID_CREDENTIALS" with a message

  @op-verifyChallenge
  Scenario Outline: An expired challenge is rejected
    Given a wallet generated at run time
    When the client requests a sign-in challenge for that address
    And the clock advances <seconds> seconds
    And the wallet signs the challenge message and verifies it
    Then the response status is 401
    And the error code is "INVALID_CREDENTIALS" with a message

    Examples:
      | seconds |
      | 300     |
      | 301     |

  @op-verifyChallenge
  Scenario: An unknown challenge is rejected
    Given a wallet generated at run time
    When the client verifies a challenge that does not exist
    Then the response status is 401
    And the error code is "INVALID_CREDENTIALS" with a message

  @op-verifyChallenge
  Scenario: A challenge issued for another address is rejected
    Given a wallet generated at run time
    When the client requests a sign-in challenge for that address
    And another wallet verifies that challenge with its own address
    Then the response status is 401
    And the error code is "INVALID_CREDENTIALS" with a message

  @op-verifyChallenge
  Scenario: A signature with a recovery id of 0 or 1 is accepted
    Given a wallet generated at run time
    When the client requests a sign-in challenge for that address
    And the wallet verifies with a recovery id of 0 or 1
    Then the response status is 200

  @op-verifyChallenge
  Scenario: A malformed signature is a VALIDATION_FAILED
    Given a wallet generated at run time
    When the client requests a sign-in challenge for that address
    And the wallet verifies the challenge with the signature "0x0123456789"
    Then the response status is 400
    And the error code is "VALIDATION_FAILED" with a message

  @op-verifyChallenge
  Scenario: All verify failures look the same
    Given a wallet generated at run time
    When the client requests a sign-in challenge for that address
    And another wallet signs the challenge message and verifies it
    Then the response status is 401
    And the response body is remembered as a verify failure
    When the client requests a sign-in challenge for that address
    And the wallet signs the challenge message and verifies it
    And the wallet signs the challenge message and verifies it
    Then the response status is 401
    And the response body is remembered as a verify failure
    When the client verifies a challenge that does not exist
    Then the response status is 401
    And the response body is remembered as a verify failure
    When the client requests a sign-in challenge for that address
    And the clock advances 301 seconds
    And the wallet signs the challenge message and verifies it
    Then the response status is 401
    And the response body is remembered as a verify failure
    And the 4 remembered verify failures are byte for byte equal

  Scenario: A session token expires after 15 minutes
    Given a wallet generated at run time
    When the wallet logs in
    And the clock advances 899 seconds
    And the client calls the session probe with the session token
    Then the response status is 404
    When the clock advances 2 seconds
    And the client calls the session probe with the session token
    Then the response status is 401
    And the error code is "UNAUTHORIZED" with a message

  Scenario: Forged tokens are rejected
    Given a wallet generated at run time
    When the wallet logs in
    And the client calls the session probe with a token whose payload was changed
    Then the response status is 401
    And the error code is "UNAUTHORIZED" with a message
    When the client calls the session probe with an unsigned token whose algorithm is none
    Then the response status is 401
    And the error code is "UNAUTHORIZED" with a message
    When the client calls the session probe with a token signed with another secret
    Then the response status is 401
    And the error code is "UNAUTHORIZED" with a message

  Scenario: Too many auth requests from one client are rejected
    When the client requests 11 sign-in challenges for different random addresses
    Then the first 10 responses were 200
    And the response status is 429
    And the error code is "TOO_MANY_REQUESTS" with a message
    And the response has a Retry-After header of at least 1 second

  @op-issueChallenge
  Scenario: Too many challenges for one address are rejected
    Given a wallet generated at run time
    When the client requests 6 sign-in challenges for that address from different clients
    Then the first 5 responses were 200
    And the response status is 429
    And the error code is "TOO_MANY_REQUESTS" with a message
    And the response has a Retry-After header of at least 1 second

  Scenario: Credentials never reach the log
    Given the log output is being captured
    And a wallet generated at run time
    When the client requests a sign-in challenge for that address
    And the wallet signs the challenge message and verifies it
    Then the response status is 200
    And no log line contains the signature, the access token or the token secret
