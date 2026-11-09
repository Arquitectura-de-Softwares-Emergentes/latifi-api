package com.latifi.api.ledger.domain;

import java.math.BigInteger;
import java.time.Instant;

/**
 * The current state of one loan, rebuilt from its events. Fields that the loan does not have yet
 * are {@code null}: the lender, the funding time, the total due and the due dates of an OPEN loan,
 * and the next due date of a loan that is no longer ACTIVE.
 *
 * @param borrower lowercase address
 * @param lender lowercase address, or {@code null} while the loan is OPEN
 * @param totalDue principal plus interest, known from the funding on
 * @param totalPaid the sum of every payment so far
 * @param installmentsPaid installments that the payments covered in full, from 0 to the number of
 *     installments
 * @param requestedAt the block time of the request
 * @param fundedAt the funding time that the contract reported
 * @param finalDueDate when the whole loan falls due
 * @param nextDueDate when the installment that is being paid falls due
 */
public record LoanState(
    BigInteger loanId,
    String borrower,
    String lender,
    BigInteger principal,
    int interestRateBps,
    int termDays,
    int installments,
    LoanStatus status,
    BigInteger totalDue,
    BigInteger totalPaid,
    int installmentsPaid,
    Instant requestedAt,
    Instant fundedAt,
    Instant finalDueDate,
    Instant nextDueDate) {}
