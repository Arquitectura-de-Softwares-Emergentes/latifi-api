package com.latifi.api.reputation.application;

import com.latifi.api.reputation.domain.HistoryPage;
import com.latifi.api.reputation.domain.ReputationRepository;
import com.latifi.api.reputation.domain.ReputationState;
import com.latifi.api.reputation.domain.SignalType;
import com.latifi.api.shared.ApiErrorCode;
import com.latifi.api.shared.ApiException;
import com.latifi.api.shared.EthAddress;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serves the stored reputation of an address and the entries behind it. */
@Service
public class ReputationService {

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  private final ReputationRepository reputation;

  public ReputationService(ReputationRepository reputation) {
    this.reputation = reputation;
  }

  /** The state of an address, or 404 REPUTATION_NOT_FOUND when it has no profile and no loan. */
  @Transactional(readOnly = true)
  public ReputationState get(String addressPath) {
    EthAddress address = EthAddress.parse(addressPath);
    return reputation
        .findState(address.lowercase())
        .orElseThrow(() -> new ApiException(ApiErrorCode.REPUTATION_NOT_FOUND));
  }

  /**
   * One page of the ledger, newest first. A page below 0 and a size outside 1 to 100 answer 400
   * VALIDATION_FAILED, and so does an address that is not well formed, before any lookup.
   */
  @Transactional(readOnly = true)
  public HistoryPage history(String addressPath, SignalType typeOrNull, int page, int size) {
    EthAddress address = EthAddress.parse(addressPath);
    if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
      throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
    }
    if (reputation.findState(address.lowercase()).isEmpty()) {
      throw new ApiException(ApiErrorCode.REPUTATION_NOT_FOUND);
    }
    return reputation.history(address.lowercase(), typeOrNull, page, size);
  }
}
