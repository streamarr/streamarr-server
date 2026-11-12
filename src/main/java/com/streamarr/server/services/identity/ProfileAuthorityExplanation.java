package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ProfileReaderRelation;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Why a caller that may still read a Profile's administration is refused what only the Profile's
 * managers may do, when what it is to the Profile now explains it (ADR 0024 §HouseholdAdmin): it
 * supervises the restricted Profile where it is available, and supervision does not cover this, or
 * it administers the unrestricted Profile there, which covers no change at all. Nothing else
 * explains a refusal: a manager is refused for the change it asked for, and a ServerAdmin's
 * authority does not come from a Household. Only a caller that may read the Profile asks, so the
 * reason tells it nothing it could not read.
 */
@Component
@RequiredArgsConstructor
class ProfileAuthorityExplanation {

  private final AuthorizationService authorizationService;
  private final ProfileRepository profileRepository;

  Optional<ProfileRejections.ManagersOnly> managersOnly(
      AuthenticatedIdentity caller, UUID profileId) {
    if (authorizationService.isAllowed(caller, new Intent.ViewHouseholds())) {
      return Optional.empty();
    }

    return profileRepository.relationsTo(caller.accountId(), List.of(profileId)).stream()
        .findFirst()
        .flatMap(this::reasonFor)
        .map(ProfileRejections.ManagersOnly::new);
  }

  private Optional<ProfileRejections.ManagersOnlyReason> reasonFor(ProfileReaderRelation relation) {
    if (relation.authority().isPresent()) {
      return switch (relation.authority().get()) {
        case SUPERVISES -> Optional.of(ProfileRejections.ManagersOnlyReason.NOT_MANAGER);
        case MANAGES -> Optional.empty();
      };
    }

    return profileRepository
        .findById(relation.profileId())
        .filter(profile -> !profile.isRestricted())
        .map(_ -> ProfileRejections.ManagersOnlyReason.NOT_RESTRICTED);
  }
}
