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
