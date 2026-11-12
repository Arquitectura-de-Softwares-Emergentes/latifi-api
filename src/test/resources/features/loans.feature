@loans
Feature: Loans
  The API serves the loans that the indexer mirrored from the chain. One route returns a loan by
  id, and another returns a filtered, sorted and paged feed in which every loan carries the
  reputation of its borrower.

  Rule: The feed serves six mirrored loans

    The data of these scenarios: loans 1, 2 and 5 are open, loans 3 and 6 are active and loan 4 is
    repaid. Borrower NORMAL has 500 and NEW. Borrower GHOST has no reputation row, so the feed shows
    500 and NEW. The wallet MID has 570 and BUILDING, and the wallet TOP has 610 and TRUSTED.

    Background:
      Given the wallet R is generated at run time
      And the wallet R is logged in
      And the wallet MID is generated at run time
      And the wallet MID is logged in
      And the wallet MID saves its own profile with name "Mid Borrower" and EMAIL contact "mid@example.org"
      And the wallet TOP is generated at run time
      And the wallet TOP is logged in
      And the wallet TOP saves its own profile with name "Top Borrower" and EMAIL contact "top@example.org"
      And the node has 70 blocks
      And the six feed loans are emitted for the borrowers MID and TOP
      And the indexer runs once
      And the reputation row of the borrower of loan 2 is removed

    @op-listLoans
    Scenario: The default order is the borrower reputation
      When the wallet R reads the loan feed with the query ""
      Then the response status is 200
      And the loan response conforms to the API contract
      And the feed lists the loans "6,4,5,3,2,1"
      And the feed reports page 0 of size 20 with 6 total items
      And the feed shows these borrower reputations:
        | loan | score | level    |
        | 1    | 500   | NEW      |
        | 2    | 500   | NEW      |
        | 3    | 570   | BUILDING |
        | 4    | 610   | TRUSTED  |
        | 5    | 570   | BUILDING |
        | 6    | 610   | TRUSTED  |
      And every feed item has a total due

    @op-listLoans
    Scenario Outline: A sort key orders the feed
      When the wallet R reads the loan feed with the query "sort=<sort>"
      Then the response status is 200
      And the loan response conforms to the API contract
      And the feed lists the loans "<ids>"
      And the feed reports page 0 of size 20 with 6 total items

      Examples:
        | sort       | ids         |
        | reputation | 6,4,5,3,2,1 |
        | amount     | 6,2,4,3,1,5 |
        | term       | 5,1,3,4,2,6 |
        | rate       | 5,4,1,3,2,6 |
        | recent     | 6,5,4,3,2,1 |
        | nextDue    | 6,3,5,4,2,1 |

    @op-listLoans
    Scenario: The status filter keeps one state
      When the wallet R reads the loan feed with the query "status=OPEN"
      Then the response status is 200
      And the loan response conforms to the API contract
      And the feed lists the loans "5,2,1"
      And the feed reports page 0 of size 20 with 3 total items
      When the wallet R reads the loan feed with the query "status=OPEN&sort=recent"
      Then the response status is 200
      And the loan response conforms to the API contract
      And the feed lists the loans "5,2,1"
      When the wallet R reads the loan feed with the query "status=ACTIVE&sort=recent"
      Then the feed lists the loans "6,3"
      When the wallet R reads the loan feed with the query "status=REPAID"
      Then the feed lists the loans "4"
      When the wallet R reads the loan feed with the query "status=DEFAULTED"
      Then the response status is 200
      And the feed lists the loans ""
      And the feed reports page 0 of size 20 with 0 total items

    @op-listLoans
    Scenario: Amount and term filters narrow the feed
      When the wallet R reads the loan feed with the query "minAmount=1000000000"
      Then the response status is 200
      And the loan response conforms to the API contract
      And the feed lists the loans "6,4,3,2"
      And the feed reports page 0 of size 20 with 4 total items
      When the wallet R reads the loan feed with the query "maxAmount=1000000000"
      Then the feed lists the loans "5,3,1"
      When the wallet R reads the loan feed with the query "minAmount=500000000&maxAmount=1500000000"
      Then the feed lists the loans "4,3,1"
      When the wallet R reads the loan feed with the query "maxTermDays=60"
      Then the response status is 200
      And the loan response conforms to the API contract
      And the feed lists the loans "5,3,1"
      When the wallet R reads the loan feed with the query "maxTermDays=60&minAmount=500000000&status=OPEN"
      Then the feed lists the loans "1"
      When the wallet R reads the loan feed with the query "minAmount=2000000000&maxAmount=1000000000"
      Then the response status is 200
      And the loan response conforms to the API contract
      And the feed lists the loans ""
      And the feed reports page 0 of size 20 with 0 total items

    @op-listLoans
    Scenario: The level filter uses the borrower level
      When the wallet R reads the loan feed with the query "minLevel=TRUSTED"
      Then the response status is 200
      And the loan response conforms to the API contract
      And the feed lists the loans "6,4"
      When the wallet R reads the loan feed with the query "minLevel=BUILDING"
      Then the feed lists the loans "6,4,5,3"
      When the wallet R reads the loan feed with the query "minLevel=NEW"
      Then the feed lists the loans "6,4,5,3,2,1"
      When the wallet R reads the loan feed with the query "minLevel=EXCELLENT"
      Then the response status is 200
      And the feed lists the loans ""

    @op-listLoans
    Scenario: Borrower and lender filters ignore address case
      When the wallet R reads the loan feed with the query "borrower={MID}"
      Then the response status is 200
      And the loan response conforms to the API contract
      And the feed lists the loans "5,3"
      When the wallet R reads the loan feed with the query "borrower={MID_CHECKSUM}"
      Then the feed lists the loans "5,3"
      When the wallet R reads the loan feed with the query "lender={LENDER}"
      Then the response status is 200
      And the loan response conforms to the API contract
      And the feed lists the loans "6,4,3"
      When the wallet R reads the loan feed with the query "lender={LENDER_UPPER}"
      Then the feed lists the loans "6,4,3"
      When the wallet R reads the loan feed with the query "lender={LENDER}&borrower={TOP}"
      Then the feed lists the loans "6,4"

    @op-listLoans
    Scenario: Pages are stable and counted
      When the wallet R reads page 0 of the feed with the query "size=2&sort=term"
      And the wallet R reads page 1 of the feed with the query "size=2&sort=term"
      And the wallet R reads page 2 of the feed with the query "size=2&sort=term"
      Then the pages together list the loans "5,1,3,4,2,6" without a repeat
      And every page reported 6 total items
      When the wallet R reads the loan feed with the query "size=2&sort=term&page=3"
      Then the response status is 200
      And the loan response conforms to the API contract
      And the feed lists the loans ""
      And the feed reports page 3 of size 2 with 6 total items

    @op-listLoans
    Scenario Outline: Invalid feed parameters are rejected
      When the wallet R reads the loan feed with the query "<query>"
      Then the response status is 400
      And the loan response conforms to the API contract
      And the response is an ApiError with code "<code>"

      Examples:
        | query                                                                                      | code              |
        | status=NOPE                                                                                | VALIDATION_FAILED |
        | status=open                                                                                | VALIDATION_FAILED |
        | sort=banana                                                                                | VALIDATION_FAILED |
        | sort=amount;drop table loan--                                                              | VALIDATION_FAILED |
        | minAmount=abc                                                                              | VALIDATION_FAILED |
        | minAmount=-5                                                                               | VALIDATION_FAILED |
        | minAmount=1.5                                                                              | VALIDATION_FAILED |
        | maxAmount=115792089237316195423570985008687907853269984665640564039457584007913129639936 | VALIDATION_FAILED |
        | maxTermDays=0                                                                              | VALIDATION_FAILED |
        | maxTermDays=abc                                                                            | VALIDATION_FAILED |
        | minLevel=GOD                                                                               | VALIDATION_FAILED |
        | size=0                                                                                     | VALIDATION_FAILED |
        | size=101                                                                                   | VALIDATION_FAILED |
        | page=-1                                                                                    | VALIDATION_FAILED |
        | page=abc                                                                                   | VALIDATION_FAILED |
        | borrower=0x12                                                                              | INVALID_ADDRESS   |
        | lender=zzz                                                                                 | INVALID_ADDRESS   |

    @op-listLoans
    Scenario: An injection attempt changes nothing
      When the wallet R reads the loan feed with the query "sort=amount; drop table loan; --"
      Then the response status is 400
      And the response is an ApiError with code "VALIDATION_FAILED"
      When the wallet R reads the loan feed with the query "status='; drop table loan; --"
      Then the response status is 400
      And the response is an ApiError with code "VALIDATION_FAILED"
      When the wallet R reads the loan feed with the query "minAmount=1; drop table loan"
      Then the response status is 400
      And the response is an ApiError with code "VALIDATION_FAILED"
      When the wallet R reads the loan feed with the query "maxTermDays=1; drop table loan"
      Then the response status is 400
      And the response is an ApiError with code "VALIDATION_FAILED"
      When the wallet R reads the loan feed with the query "borrower='; drop table loan; --"
      Then the response status is 400
      And the response is an ApiError with code "INVALID_ADDRESS"
      When the wallet R reads the loan feed with the query "lender=0x2222222222222222222222222222222222222222' or '1'='1"
      Then the response status is 400
      And the response is an ApiError with code "INVALID_ADDRESS"
      Then the loan table has 6 rows
      When the wallet R reads the loan feed with the query ""
      Then the response status is 200
      And the loan response conforms to the API contract
      And the feed lists the loans "6,4,5,3,2,1"

    @op-listLoans @op-getLoan
    Scenario: The feed and the detail show the same loan
      When the wallet R reads the loan "3"
      Then the response status is 200
      And the loan response conforms to the API contract
      And the loan response has the field loanId with the value "3"
      And the loan response has the field status with the value "ACTIVE"
      And the loan response shows the borrower reputation 570 and BUILDING
      When the wallet R reads the loan feed with the query "borrower={MID}&status=ACTIVE"
      Then the feed lists the loans "3"

  Rule: One loan at a time

    Background:
      Given the wallet R is generated at run time
      And the wallet R is logged in

    @op-listLoans @op-getLoan
    Scenario: An open request shows its projected total due
      Given the node has 20 blocks
      And a LoanRequested log for loan 7 of 1000000000 units at 1000 basis points over 90 days in 3 installments is emitted at block 12
      When the indexer runs once
      And the wallet R reads the loan "7"
      Then the response status is 200
      And the loan response conforms to the API contract
      And the loan response has the status OPEN, the total due "1024657534" and the amount repaid "0"
      And the loan response has no field lender
      And the loan response has no field fundedAt
      And the loan response has no field nextDueDate
      And the loan response shows the borrower reputation 500 and NEW
      When the wallet R reads the loan feed with the query "status=OPEN"
      Then the response status is 200
      And the loan response conforms to the API contract
      And the first feed item shows the status OPEN, the total due "1024657534" and the amount repaid "0"

    @op-getLoan
    Scenario: A funded loan shows its lender and schedule
      Given the node has 40 blocks
      And a LoanRequested log for loan 7 of 1000000000 units at 1000 basis points over 90 days in 3 installments is emitted at block 10
      And a LoanFunded log for loan 7 at the funding time 1800000000 with the due date 1807776000 is emitted at block 20
      When the indexer runs once
      And the wallet R reads the loan "7"
      Then the response status is 200
      And the loan response conforms to the API contract
      And the loan response has the status ACTIVE, the total due "1024657534" and the amount repaid "0"
      And the loan response has the field lender with the value "0x2222222222222222222222222222222222222222"
      And the loan response has the time fundedAt at 1800000000
      And the loan response has the time finalDueDate at 1807776000
      And the loan response has the time nextDueDate at 1802592000
      Given a LoanRepaid log for loan 7 of 1024657534 units is emitted at block 30
      When the indexer runs once
      And the wallet R reads the loan "7"
      Then the response status is 200
      And the loan response conforms to the API contract
      And the loan response has the status REPAID, the total due "1024657534" and the amount repaid "1024657534"
      And the loan response has the field installmentsPaid with the value "3"
      And the loan response has the field lender with the value "0x2222222222222222222222222222222222222222"
      And the loan response has no field nextDueDate

    @op-getLoan
    Scenario: An unknown loan answers 404
      When the wallet R reads the loan "99"
      Then the response status is 404
      And the loan response conforms to the API contract
      And the response is an ApiError with code "LOAN_NOT_FOUND"
      And the error message is "The loan does not exist."
      When the client sends the Accept-Language header "es-419"
      And the wallet R reads the loan "99"
      Then the response status is 404
      And the response is an ApiError with code "LOAN_NOT_FOUND"
      And the error message is "El préstamo no existe."

    @op-getLoan
    Scenario Outline: A malformed loan id answers 400
      When the wallet R reads the loan "<id>"
      Then the response status is 400
      And the loan response conforms to the API contract
      And the response is an ApiError with code "VALIDATION_FAILED"

      Examples:
        | id                                                                                       |
        | abc                                                                                      |
        | -1                                                                                       |
        | 1.5                                                                                      |
        | 0x10                                                                                     |
        | 115792089237316195423570985008687907853269984665640564039457584007913129639936 |

    @op-listLoans @op-getLoan
    Scenario: Loans need a session
      When an anonymous client reads the loan feed
      Then the response status is 401
      And the loan response conforms to the API contract
      And the response is an ApiError with code "UNAUTHORIZED"
      When an anonymous client reads the loan "1"
      Then the response status is 401
      And the loan response conforms to the API contract
      And the response is an ApiError with code "UNAUTHORIZED"
