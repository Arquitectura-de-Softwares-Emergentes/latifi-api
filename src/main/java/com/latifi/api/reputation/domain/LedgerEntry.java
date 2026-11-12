package com.latifi.api.reputation.domain;

/**
 * One line of the ledger of an address.
 *
 * @param seq position in the ledger, starting at 1
 * @param signal the signal that produced the entry
 * @param delta effective change, so that {@code scoreAfter = scoreBefore + delta} always holds
 * @param scoreAfter score once the entry applied
 */
public record LedgerEntry(int seq, ReputationSignal signal, int delta, int scoreAfter) {}
