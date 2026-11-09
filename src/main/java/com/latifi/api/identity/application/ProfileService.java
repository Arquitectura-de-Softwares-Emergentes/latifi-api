package com.latifi.api.identity.application;

import com.latifi.api.identity.domain.ContactRules;
import com.latifi.api.identity.domain.ContactType;
import com.latifi.api.identity.domain.Profile;
import com.latifi.api.identity.domain.ProfileRepository;
import com.latifi.api.identity.domain.ProfileRepository.UpsertResult;
import com.latifi.api.identity.domain.ProfileSaved;
import com.latifi.api.shared.ApiErrorCode;
import com.latifi.api.shared.ApiException;
import com.latifi.api.shared.EthAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes profiles. Only the owner writes. The contact leaves this service only when the
 * caller is the owner. The service never logs the name or the contact.
 */
@Service
public class ProfileService {

  private final ProfileRepository profiles;
  private final Clock clock;
  private final ApplicationEventPublisher events;

  public ProfileService(ProfileRepository profiles, Clock clock, ApplicationEventPublisher events) {
    this.profiles = profiles;
    this.clock = clock;
    this.events = events;
  }

  /**
   * Reads a profile. The caller address is the lowercase subject of a verified token, or null for
   * an anonymous call.
   */
  @Transactional(readOnly = true)
  public ProfileView get(String addressPath, String callerAddressOrNull) {
    EthAddress address = EthAddress.parse(addressPath);
    Profile profile =
        profiles
            .find(address.lowercase())
            .orElseThrow(() -> new ApiException(ApiErrorCode.PROFILE_NOT_FOUND));
    return new ProfileView(profile, address.lowercase().equals(callerAddressOrNull));
  }

  /** Creates or replaces the profile of the caller. */
  @Transactional
  public SaveResult upsert(String addressPath, String callerAddress, UpsertCommand command) {
    EthAddress address = EthAddress.parse(addressPath);
    if (callerAddress == null || !address.lowercase().equals(callerAddress)) {
      throw new ApiException(ApiErrorCode.FORBIDDEN_NOT_OWNER);
    }
    String name = command.displayName() == null ? null : command.displayName().strip();
    String contact = command.contactValue() == null ? null : command.contactValue().strip();
    if (!ContactRules.isValidName(name) || !ContactRules.isValid(command.contactType(), contact)) {
      throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
    }
    // The database keeps microseconds, so the response matches what a later read returns.
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    UpsertResult result =
        profiles.upsert(
            new Profile(address.lowercase(), name, command.contactType(), contact, now, now));
    Profile stored = result.profile();
    events.publishEvent(new ProfileSaved(stored.address(), stored.createdAt()));
    return new SaveResult(new ProfileView(stored, true), result.created());
  }

  /** The values of a write request, before validation. */
  public record UpsertCommand(String displayName, ContactType contactType, String contactValue) {

    @Override
    public String toString() {
      return "UpsertCommand[]";
    }
  }

  /** A profile and whether the caller may see its contact. */
  public record ProfileView(Profile profile, boolean contactVisible) {}

  /** The saved view and whether the write created the profile. */
  public record SaveResult(ProfileView view, boolean created) {}
}
