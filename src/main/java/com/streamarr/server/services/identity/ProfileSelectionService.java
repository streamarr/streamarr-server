package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.exceptions.ProfileLockedException;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.ProfilePinVerifier;
import com.streamarr.server.services.auth.TokenContext;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.authorization.ProfileSafetyRule;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The complete select-profile ceremony (ADR 0024 §PIN safety): authenticate the Account and live
 * session, confirm the Profile is available in the context Household, decide whether it requires a
 * PIN, throttle and verify the PIN, apply the Household safety lock through Cedar, record the
 * selection, and hand back the context the Profile-scoped token is minted from. The verified-PIN
 * result exists only here as trusted attempt context.
 */
@Service
@RequiredArgsConstructor
public class ProfileSelectionService {

  private final ProfileRepository profileRepository;
  private final ProfilePinVerifier pinVerifier;
  private final AuthorizationService authorizationService;
  private final SessionContextService sessionContextService;

  /**
   * The PIN is verified outside the transaction (Argon2 must never pin a pooled connection), then
   * the selection is written inside one.
   */
  public TokenContext selectProfile(AuthenticatedIdentity identity, SelectProfileCommand command) {
    var available = profileRepository.findAvailableInHousehold(identity.contextHouseholdId());
    var profile =
        available.stream()
            .filter(candidate -> candidate.getId().equals(command.profileId()))
            .findFirst()
            .orElseThrow(ProfileAccessDeniedException::new);

    var pinVerified = false;
    if (profile.hasEffectivePin()) {
      pinVerifier.verify(identity.accountId(), profile, command.pin());
      pinVerified = true;
    }

    var decision =
        authorizationService.decide(
            identity, new Intent.SelectProfile(profile.getId(), pinVerified));
    return switch (decision) {
      case Decision.Allowed<?> _ ->
          sessionContextService.recordProfileSelection(identity, command.profileId());
      case Decision.Denied<?> _ -> throw denial(profile, available);
      case Decision.Failed<?> _ -> throw new AuthorizationUnavailableException();
    };
  }

  /** Names the reason the same way Cedar decided it, for the client's guidance only. */
  private static RuntimeException denial(Profile profile, List<Profile> available) {
    if (ProfileSafetyRule.lockedProfiles(available).contains(profile.getId())) {
      return new ProfileLockedException();
    }

    return new ProfileAccessDeniedException();
  }
}
