package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.PrimBool;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ADR 0024 §Permanent Profile deletion, read live: ordinary deletion needs an unlinked Profile with
 * no active or pending shares and the principal as its sole remaining direct manager.
 */
@Component
@RequiredArgsConstructor
class ProfileDeletionContributor implements FactContributor {

  static final String UNLINKED = "unlinked";
  static final String SHARE_FREE = "shareFree";
  static final String PRINCIPAL_SOLE_MANAGER = "principalSoleManager";

  private final UserAccountRepository userAccountRepository;
  private final ProfileManagerRepository profileManagerRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final Clock clock;

  @Override
  public FactRequirement provides() {
    return FactRequirement.PROFILE_DELETION;
  }

  @Override
  public void contribute(
      AuthenticatedIdentity identity, AuthorizationCheck check, EntitySlice slice) {
    var profileId = check.resourceId();
    var managers =
        profileManagerRepository.findByProfileId(profileId).stream()
            .map(ProfileManager::getAccountId)
            .toList();
    slice.resourceAttribute(
        UNLINKED, new PrimBool(userAccountRepository.findByPersonalProfileId(profileId).isEmpty()));
    slice.resourceAttribute(
        SHARE_FREE,
        new PrimBool(!shareRepository.hasActiveOrPendingShares(profileId, clock.instant())));
    slice.resourceAttribute(
        PRINCIPAL_SOLE_MANAGER, new PrimBool(managers.equals(List.of(identity.accountId()))));
  }
}
