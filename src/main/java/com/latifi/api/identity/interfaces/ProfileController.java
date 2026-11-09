package com.latifi.api.identity.interfaces;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.latifi.api.identity.application.ProfileService;
import com.latifi.api.identity.application.ProfileService.ProfileView;
import com.latifi.api.identity.application.ProfileService.SaveResult;
import com.latifi.api.identity.application.ProfileService.UpsertCommand;
import com.latifi.api.identity.domain.ContactType;
import com.latifi.api.identity.domain.Profile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Profile endpoints. The {@code /api/v1} prefix comes from WebConfig. */
@RestController
@RequestMapping("/profiles")
public class ProfileController {

  private final ProfileService profiles;

  public ProfileController(ProfileService profiles) {
    this.profiles = profiles;
  }

  @GetMapping("/{address}")
  public ProfileResponse get(@PathVariable String address, Authentication authentication) {
    return ProfileResponse.from(profiles.get(address, caller(authentication)));
  }

  @PutMapping("/{address}")
  public ResponseEntity<ProfileResponse> put(
      @PathVariable String address,
      @Valid @RequestBody ProfileUpdateRequest request,
      Authentication authentication) {
    SaveResult result =
        profiles.upsert(
            address,
            caller(authentication),
            new UpsertCommand(
                request.displayName(), request.contact().type(), request.contact().value()));
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(ProfileResponse.from(result.view()));
  }

  /** The caller is the subject of a verified token. An anonymous call has none. */
  private static String caller(Authentication authentication) {
    if (authentication instanceof JwtAuthenticationToken jwt) {
      return jwt.getName();
    }
    return null;
  }

  public record ProfileUpdateRequest(
      @NotNull @Size(min = 1, max = 80) String displayName, @NotNull @Valid ContactDto contact) {

    @Override
    public String toString() {
      return "ProfileUpdateRequest[]";
    }
  }

  public record ContactDto(
      @NotNull ContactType type, @NotNull @Size(min = 3, max = 254) String value) {

    @Override
    public String toString() {
      return "ContactDto[]";
    }
  }

  /** The contact is omitted, never null, when the caller may not see it. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ProfileResponse(
      String address,
      String displayName,
      ContactDto contact,
      Instant createdAt,
      Instant updatedAt) {

    static ProfileResponse from(ProfileView view) {
      Profile profile = view.profile();
      ContactDto contact =
          view.contactVisible()
              ? new ContactDto(profile.contactType(), profile.contactValue())
              : null;
      return new ProfileResponse(
          profile.address(),
          profile.displayName(),
          contact,
          profile.createdAt(),
          profile.updatedAt());
    }

    @Override
    public String toString() {
      return "ProfileResponse[address=" + address + "]";
    }
  }
}
