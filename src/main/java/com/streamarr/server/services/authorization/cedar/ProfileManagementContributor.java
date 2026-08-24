package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.PrimBool;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The resource Profile relative to the principal: managed by it (a direct manager row, or the
 * principal's own unrestricted Adult Personal Profile), available in the principal's membership
 * Household.
 */
@Component
@RequiredArgsConstructor
class ProfileManagementContributor implements FactContributor {

  static final String MANAGED_BY_PRINCIPAL = "managedByPrincipal";
  static final String AVAILABLE_IN_PRINCIPAL_HOUSEHOLD = "availableInPrincipalHousehold";

  private final ProfileRepository profileRepository;
  private final ProfileManagerRepository profileManagerRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final UserAccountRepository userAccountRepository;

  @Override
  public FactRequirement provides() {
    return FactRequirement.PROFILE_MANAGEMENT;
  }

  @Override
  public void contribute(
      AuthenticatedIdentity identity, AuthorizationCheck check, EntitySlice slice) {
    var profileId = check.resourceId();
    var profile = profileRepository.findById(profileId);
    if (profile.isEmpty()) {
      throw new InvalidEntitySliceException("Profile facts are unavailable.");
    }

    var restricted = profile.get().isRestricted();
    var selfManaged =
        !restricted
            && userAccountRepository
                .findById(identity.accountId())
                .map(account -> profileId.equals(account.getPersonalProfileId()))
                .orElse(false);
    var directManager =
        profileManagerRepository
            .findByAccountIdAndProfileId(identity.accountId(), profileId)
            .isPresent();
    slice.resourceAttribute(MANAGED_BY_PRINCIPAL, new PrimBool(selfManaged || directManager));
    slice.resourceAttribute(
        AVAILABLE_IN_PRINCIPAL_HOUSEHOLD,
        new PrimBool(shareRepository.isActivelyShared(profileId, identity.householdId())));
  }
}
