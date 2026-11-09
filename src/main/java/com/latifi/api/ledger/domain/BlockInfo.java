package com.latifi.api.ledger.domain;

import java.time.Instant;

/** The two facts about a block that a log does not carry: its hash and its timestamp. */
public record BlockInfo(long number, String hash, Instant timestamp) {}
