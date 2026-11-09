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

  @op-convertAmount
  Scenario Outline: Each supported currency keeps its own precision
    Given the rate provider holds the standard rates published at "2026-01-01T11:00:00Z"
    When the wallet R converts "250000000" base units to <currency>
    Then the response status is 200
    And the exchange response conforms to the API contract
    And the conversion is "<value>" <currency> at the rate "<rate>"

    Examples:
      | currency | value     | rate        |
      | PEN      | 859.51    | 3.438038    |
      | COP      | 809963.68 | 3239.854719 |
      | MXN      | 4663.09   | 18.652374   |
      | ARS      | 386349.35 | 1545.3974   |
      | BRL      | 1375.68   | 5.502723    |
      | CLP      | 243595    | 974.378765  |

  @op-convertAmount
  Scenario: A fresh cache answers without calling the provider
    Given the rate provider holds the standard rates published at "2026-01-01T11:00:00Z"
    When the wallet R converts "250000000" base units to PEN
    Then the response status is 200
    And the conversion is published at "2026-01-01T11:00:00Z" and stale is false
    When the wallet R converts "100000000" base units to COP
    Then the response status is 200
    And the exchange response conforms to the API contract
    And the conversion is "323985.47" COP at the rate "3239.854719"
    And the conversion is published at "2026-01-01T11:00:00Z" and stale is false
    And the rate provider was called 1 time

  @op-convertAmount
  Scenario: An expired cache refreshes from the provider
    Given the rate provider holds the standard rates published at "2026-01-01T11:00:00Z"
    When the wallet R converts "250000000" base units to PEN
    Then the rate provider was called 1 time
    When the clock advances 3599 seconds
    And the wallet R is logged in
    And the rate provider now holds the rate "4" for PEN, published at "2026-01-01T12:30:00Z"
    And the wallet R converts "250000000" base units to PEN
    Then the response status is 200
    And the conversion is "859.51" PEN at the rate "3.438038"
    And the rate provider was called 1 time
    When the clock advances 2 seconds
    And the wallet R converts "250000000" base units to PEN
    Then the response status is 200
    And the exchange response conforms to the API contract
    And the conversion is "1000.00" PEN at the rate "4"
    And the conversion is published at "2026-01-01T12:30:00Z" and stale is false
    And the rate provider was called 2 times

  @op-convertAmount @op-listRates
  Scenario: A provider outage serves the last cached rate flagged stale
    Given the rate provider holds the standard rates published at "2026-01-01T11:00:00Z"
    When the wallet R converts "250000000" base units to PEN
    Then the response status is 200
    When the clock advances 7200 seconds
    And the wallet R is logged in
    And the rate provider is down
    And the wallet R converts "250000000" base units to PEN
    Then the response status is 200
    And the exchange response conforms to the API contract
    And the conversion is "859.51" PEN at the rate "3.438038"
    And the conversion is published at "2026-01-01T11:00:00Z" and stale is true
    When the wallet R reads the exchange rates
    Then the response status is 200
    And the exchange response conforms to the API contract
    And the exchange rates list shows these quotes:
      | quote | rate        | asOf                 | stale |
      | PEN   | 3.438038    | 2026-01-01T11:00:00Z | true  |
      | COP   | 3239.854719 | 2026-01-01T11:00:00Z | true  |
      | MXN   | 18.652374   | 2026-01-01T11:00:00Z | true  |
      | ARS   | 1545.3974   | 2026-01-01T11:00:00Z | true  |
      | BRL   | 5.502723    | 2026-01-01T11:00:00Z | true  |
      | CLP   | 974.378765  | 2026-01-01T11:00:00Z | true  |
    And every exchange quote is stale

  @op-convertAmount @op-listRates
  Scenario: No cache and no provider answers 503
    Given the client sends the Accept-Language header "es-419"
    And the rate provider is down
    When the wallet R converts "250000000" base units to PEN
    Then the response status is 503
    And the response is an ApiError with code "RATE_UNAVAILABLE"
    And the exchange response conforms to the API contract
    And the error message is "No hay un tipo de cambio disponible en este momento."
    When the wallet R reads the exchange rates
    Then the response status is 503
    And the response is an ApiError with code "RATE_UNAVAILABLE"
    And the exchange response conforms to the API contract
    And the error message is "No hay un tipo de cambio disponible en este momento."

  @op-convertAmount
  Scenario: The unavailable message is English by default
    Given the rate provider is down
    When the wallet R converts "250000000" base units to PEN
    Then the response status is 503
    And the response is an ApiError with code "RATE_UNAVAILABLE"
    And the error message is "No exchange rate is available right now."

  @op-convertAmount
  Scenario: Failures are not retried before the backoff
    Given the rate provider holds the standard rates published at "2026-01-01T11:00:00Z"
    When the wallet R converts "250000000" base units to PEN
    And the clock advances 7200 seconds
    And the wallet R is logged in
    And the rate provider is down
    And the wallet R converts "250000000" base units to PEN 5 times
    Then the response status is 200
    And the conversion is published at "2026-01-01T11:00:00Z" and stale is true
    And the rate provider was called 2 times
    When the clock advances 59 seconds
    And the wallet R converts "250000000" base units to PEN
    Then the rate provider was called 2 times
    When the clock advances 1 seconds
    And the wallet R converts "250000000" base units to PEN
    Then the response status is 200
    And the conversion is published at "2026-01-01T11:00:00Z" and stale is true
    And the rate provider was called 3 times

  @op-convertAmount
  Scenario: With no cache a failure is not retried before the backoff either
    Given the rate provider is down
    When the wallet R converts "250000000" base units to PEN 3 times
    Then the response status is 503
    And the rate provider was called 1 time

  @op-convertAmount
  Scenario: A recovered provider clears the stale flag
    Given the rate provider holds the standard rates published at "2026-01-01T11:00:00Z"
    When the wallet R converts "250000000" base units to PEN
    And the clock advances 7200 seconds
    And the wallet R is logged in
    And the rate provider is down
    And the wallet R converts "250000000" base units to PEN
    Then the conversion is published at "2026-01-01T11:00:00Z" and stale is true
    When the rate provider works again
    And the rate provider now holds the rate "3.5" for PEN, published at "2026-01-01T13:00:00Z"
    And the wallet R converts "250000000" base units to PEN
    Then the conversion is published at "2026-01-01T11:00:00Z" and stale is true
    When the clock advances 60 seconds
    And the wallet R converts "250000000" base units to PEN
    Then the response status is 200
    And the exchange response conforms to the API contract
    And the conversion is "875.00" PEN at the rate "3.5"
    And the conversion is published at "2026-01-01T13:00:00Z" and stale is false

  @op-convertAmount
  Scenario Outline: An unsupported currency is rejected
    When the wallet R converts "250000000" base units to <currency>
    Then the response status is 400
    And the response is an ApiError with code "UNSUPPORTED_CURRENCY"
    And the exchange response conforms to the API contract
    And the error message is "The currency is not supported."
    And the rate provider was called 0 times

    Examples:
      | currency |
      | USD      |
      | XXX      |

  @op-convertAmount
  Scenario: The unsupported currency message follows the language
    Given the client sends the Accept-Language header "es-419"
    When the wallet R converts "250000000" base units to USD
    Then the response status is 400
    And the response is an ApiError with code "UNSUPPORTED_CURRENCY"
    And the error message is "La moneda no es compatible."

  @op-convertAmount
  Scenario Outline: Invalid conversion input is rejected
    When the wallet R converts with the query "<query>"
    Then the response status is 400
    And the response is an ApiError with code "VALIDATION_FAILED"
    And the exchange response conforms to the API contract
    And the rate provider was called 0 times

    Examples:
      | query                                                                                                         |
      | currency=PEN                                                                                                  |
      | amount=abc&currency=PEN                                                                                       |
      | amount=-1&currency=PEN                                                                                        |
      | amount=1.5&currency=PEN                                                                                       |
      | amount=115792089237316195423570985008687907853269984665640564039457584007913129639936&currency=PEN           |
      | amount=250000000                                                                                              |
      | amount=250000000&currency=pen                                                                                 |
      | amount=250000000&currency=PE                                                                                  |

  @op-listRates
  Scenario: The rates list covers the six currencies
    Given the rate provider holds the standard rates published at "2026-01-01T11:00:00Z"
    When the wallet R reads the exchange rates
    Then the response status is 200
    And the exchange response conforms to the API contract
    And the exchange rates list shows these quotes:
      | quote | rate        | asOf                 | stale |
      | PEN   | 3.438038    | 2026-01-01T11:00:00Z | false |
      | COP   | 3239.854719 | 2026-01-01T11:00:00Z | false |
      | MXN   | 18.652374   | 2026-01-01T11:00:00Z | false |
      | ARS   | 1545.3974   | 2026-01-01T11:00:00Z | false |
      | BRL   | 5.502723    | 2026-01-01T11:00:00Z | false |
      | CLP   | 974.378765  | 2026-01-01T11:00:00Z | false |

  @op-convertAmount @op-listRates
  Scenario: Exchange rates need a session
    When an anonymous client converts "250000000" base units to PEN
    Then the response status is 401
    And the response is an ApiError with code "UNAUTHORIZED"
    When an anonymous client reads the exchange rates
    Then the response status is 401
    And the response is an ApiError with code "UNAUTHORIZED"
    And the rate provider was called 0 times

  @op-convertAmount
  Scenario: A zero amount converts to zero
    When the wallet R converts "0" base units to PEN
    Then the response status is 200
    And the exchange response conforms to the API contract
    And the conversion is "0.00" PEN at the rate "3.438038"
    When the wallet R converts "0" base units to CLP
    Then the response status is 200
    And the conversion is "0" CLP at the rate "974.378765"

  @op-convertAmount
  Scenario: The largest amount converts without overflow
    When the wallet R converts "115792089237316195423570985008687907853269984665640564039457584007913129639935" base units to CLP
    Then the response status is 200
    And the exchange response conforms to the API contract
    And the conversion reports the amount "115792089237316195423570985008687907853269984665640564039457584007913129639935"
