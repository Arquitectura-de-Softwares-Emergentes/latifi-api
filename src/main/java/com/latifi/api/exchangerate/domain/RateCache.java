package com.latifi.api.exchangerate.domain;

import java.time.Instant;
import java.util.List;

/** Port to the stored rates. */
public interface RateCache {

  List<CachedRate> readAll();

  /** Stores every currency of {@code rates} in one transaction, so a reader never sees a mix. */
  void replaceAll(ProviderRates rates, Instant fetchedAt);
}
