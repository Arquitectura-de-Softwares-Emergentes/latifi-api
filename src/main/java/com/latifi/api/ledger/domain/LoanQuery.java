package com.latifi.api.ledger.domain;

import java.math.BigInteger;

/**
 * A validated request for a page of the loan feed. Every filter is optional and {@code null} means
 * that the filter is off.
 *
 * @param status keep only loans in this state
 * @param minAmount smallest principal, in base units
 * @param maxAmount largest principal, in base units
 * @param maxTermDays longest term in days
 * @param minLevelRank lowest rank of the borrower level, 0 for NEW to 3 for EXCELLENT
 * @param borrower lowercase address of the borrower
 * @param lender lowercase address of the lender
 * @param sort the order of the feed
 * @param page zero based page index
 * @param size items per page, from 1 to 100
 */
public record LoanQuery(
    LoanStatus status,
    BigInteger minAmount,
    BigInteger maxAmount,
    Integer maxTermDays,
    Integer minLevelRank,
    String borrower,
    String lender,
    LoanSort sort,
    int page,
    int size) {}
