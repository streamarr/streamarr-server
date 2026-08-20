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
  static final String OFFERABLE_BY_PRINCIPAL = "offerableByPrincipal";
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
        profileManagerRepository.existsByAccountIdAndProfileId(identity.accountId(), profileId);
    slice.resourceAttribute(MANAGED_BY_PRINCIPAL, new PrimBool(selfManaged || directManager));
    // A self-managed Personal Profile is offered only by its own Account (ADR 0024 §Profile
    // sharing) — acceptance admits the person, so a retained direct manager cannot offer it.
    // selfManaged implies a sovereign Personal Profile, so the sovereign arm needs only it and
    // the other arm needs only the direct grant.
    var sovereignPersonal =
        !restricted && userAccountRepository.findByPersonalProfileId(profileId).isPresent();
    slice.resourceAttribute(
        OFFERABLE_BY_PRINCIPAL, new PrimBool(sovereignPersonal ? selfManaged : directManager));
    slice.resourceAttribute(
        AVAILABLE_IN_PRINCIPAL_HOUSEHOLD,
        new PrimBool(shareRepository.isActivelyShared(profileId, identity.householdId())));
  }
}
