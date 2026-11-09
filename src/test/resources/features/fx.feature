@fx
Feature: Exchange rates
  The API converts stablecoin amounts to local currencies from rates that it caches for one hour.
  The mobile app calls only the API. The API calls the rate provider, and tests replace that
  provider with a double, so no scenario reaches the network.

  Background:
    Given the wallet R is generated at run time
    And the wallet R is logged in

  @op-convertAmount
  Scenario: A rate converts an amount
    Given the rate provider holds the standard rates published at "2026-01-01T11:00:00Z"
    When the wallet R converts "250000000" base units to PEN
    Then the response status is 200
    And the exchange response conforms to the API contract
    And the conversion reports the amount "250000000"
    And the conversion is "859.51" PEN at the rate "3.438038"
    And the conversion is published at "2026-01-01T11:00:00Z" and stale is false
    And the cache holds one row for each of the currencies "PEN,COP,MXN,ARS,BRL,CLP"
    And the rate provider was called 1 time
