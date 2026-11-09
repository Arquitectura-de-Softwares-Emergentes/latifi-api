@profiles
Feature: Wallet profile
  A wallet owner stores a display name and a contact. Everybody reads the name, only the owner
  reads the contact, and only the owner writes.

  @op-upsertProfile @op-getProfile
  Scenario: An owner creates a profile and the public sees only the name
    Given the wallet A is generated at run time
    And the wallet A is logged in
    When the wallet A saves its own profile with name "Ana Quispe" and EMAIL contact "ana@example.org"
    Then the response status is 201
    And the profile response conforms to the API contract
    When an anonymous client reads the profile of A
    Then the response status is 200
    And the profile response conforms to the API contract
    And the profile body has the display name "Ana Quispe"
    And the profile body has no contact key
    When the wallet A reads the profile of A
    Then the response status is 200
    And the profile body has the contact EMAIL "ana@example.org"
    And the profile row of A has the lowercase address

  @op-upsertProfile
  Scenario: Nobody writes another wallet's profile
    Given the wallet A is generated at run time
    And the wallet B is generated at run time
    And the wallet A is logged in
    When the wallet A saves the profile of B with name "Intruder" and EMAIL contact "intruder@example.org"
    Then the response status is 403
    And the response is an ApiError with code "FORBIDDEN_NOT_OWNER"
    And the profile response conforms to the API contract
    And no profile row exists for B
    When the client sends the Accept-Language header "es-419"
    And the wallet A saves the profile of B with name "Intruder" and EMAIL contact "intruder@example.org"
    Then the response status is 403
    And the error message is "Solo puedes modificar datos que pertenecen a tu propia dirección."
    And no profile row exists for B

  @op-upsertProfile
  Scenario: A profile write without a token is rejected
    Given the wallet A is generated at run time
    When an anonymous client saves the profile of A with name "Ana" and EMAIL contact "ana@example.org"
    Then the response status is 401
    And the response is an ApiError with code "UNAUTHORIZED"
    And no profile row exists for A

  @op-upsertProfile
  Scenario: An expired token cannot write a profile
    Given the wallet A is generated at run time
    And the wallet A is logged in
    When the clock advances 901 seconds
    And the wallet A saves its own profile with name "Ana" and EMAIL contact "ana@example.org"
    Then the response status is 401
    And the response is an ApiError with code "UNAUTHORIZED"
    And no profile row exists for A

  @op-getProfile
  Scenario: An invalid token on the public read is rejected
    Given the wallet A is generated at run time
    And the wallet A is logged in
    And the wallet A saves its own profile with name "Ana" and EMAIL contact "ana@example.org"
    When the client reads the profile of A with the bearer value "not.a.token"
    Then the response status is 401
    And the response is an ApiError with code "UNAUTHORIZED"

  @op-getProfile
  Scenario: Another wallet never sees the contact
    Given the wallet A is generated at run time
    And the wallet B is generated at run time
    And the wallet A is logged in
    And the wallet B is logged in
    And the wallet A saves its own profile with name "Ana Quispe" and EMAIL contact "ana@example.org"
    When the wallet B reads the profile of A
    Then the response status is 200
    And the profile response conforms to the API contract
    And the profile body has the display name "Ana Quispe"
    And the profile body has no contact key
    And the response body does not contain "ana@example.org"

  @op-upsertProfile
  Scenario: An update replaces the profile and keeps its creation time
    Given the wallet A is generated at run time
    And the wallet A is logged in
    And no profile event has been collected
    When the wallet A saves its own profile with name "Ana" and EMAIL contact "ana@example.org"
    Then the response status is 201
    And the profile response is remembered
    When the clock advances 60 seconds
    And the wallet A saves its own profile with name "Ana Maria" and PHONE contact "+51 999 111 222"
    Then the response status is 200
    And the profile response conforms to the API contract
    And the profile response is remembered
    And the remembered profile responses keep createdAt and move updatedAt by 60 seconds
    And the profile body has the display name "Ana Maria"
    And the profile body has the contact PHONE "+51 999 111 222"
    And 2 profile events were collected for A with the creation time of the first response

  @op-getProfile
  Scenario: A profile that does not exist answers 404
    Given the wallet A is generated at run time
    When an anonymous client reads the profile of A
    Then the response status is 404
    And the response is an ApiError with code "PROFILE_NOT_FOUND"
    And the profile response conforms to the API contract

  @op-getProfile
  Scenario: A bad address in the path answers INVALID_ADDRESS
    When an anonymous client reads the profile path "0x12"
    Then the response status is 400
    And the response is an ApiError with code "INVALID_ADDRESS"

  @op-upsertProfile @op-getProfile
  Scenario: The checksummed address reaches the same profile
    Given the wallet A is generated at run time
    And the wallet A is logged in
    When the wallet A saves its profile under the checksummed address with name "Ana" and EMAIL contact "ana@example.org"
    Then the response status is 201
    When an anonymous client reads the profile of A
    Then the response status is 200
    And the profile body has the display name "Ana"
    And the profile row of A has the lowercase address

  @op-upsertProfile
  Scenario Outline: Invalid profile values are rejected
    Given the wallet A is generated at run time
    And the wallet A is logged in
    When the wallet A saves a profile where the <field> is "<value>"
    Then the response status is 400
    And the response is an ApiError with code "<code>"
    And no profile row exists for A

    Examples:
      | field   | value                       | code              |
      | name    | blank                       | VALIDATION_FAILED |
      | name    | repeat a 81 times           | VALIDATION_FAILED |
      | email   | not-an-email                | VALIDATION_FAILED |
      | email   | a b@example.org             | VALIDATION_FAILED |
      | phone   | call me                     | VALIDATION_FAILED |
      | phone   | repeat 1 31 times           | VALIDATION_FAILED |
      | type    | LANDLINE                    | MALFORMED_REQUEST |
      | contact | missing                     | VALIDATION_FAILED |

  @op-upsertProfile
  Scenario: Contact data never reaches the logs
    Given the wallet A is generated at run time
    And the wallet A is logged in
    And the profile log output is being captured
    When the wallet A saves its own profile with name "Quispe Distintivo 7f3a9c" and EMAIL contact "rastro.7f3a9c@example.org"
    Then the response status is 201
    When the wallet A reads the profile of A
    Then the response status is 200
    And no log line contains the contact value or the display name
