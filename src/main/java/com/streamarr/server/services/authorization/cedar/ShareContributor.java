package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.PrimBool;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The resource Share's lifecycle and the principal's live relationships to it: target-Household
 * admin (live role), direct manager of the Profile, sovereign Account over its own Personal
 * Profile, or the offerer. A missing Share contributes nothing, and absent facts read as denied.
 */
@Component
@RequiredArgsConstructor
class ShareContributor implements FactContributor {

  static final String STRUCTURAL = "structural";
  static final String OFFERED_BY_PRINCIPAL = "offeredByPrincipal";
  static final String PRINCIPAL_MEMBER_OF_TARGET = "principalMemberOfTarget";
  static final String PRINCIPAL_ADMIN_OF_TARGET = "principalAdminOfTarget";
  static final String DIRECTLY_MANAGED_BY_PRINCIPAL = "directlyManagedByPrincipal";
  static final String SOVEREIGN_OVER_PROFILE = "sovereignOverProfileByPrincipal";

  private final ProfileHouseholdShareRepository shareRepository;
  private final ProfileRepository profileRepository;
  private final ProfileManagerRepository profileManagerRepository;
  private final UserAccountRepository userAccountRepository;

  @Override
  public FactRequirement provides() {
    return FactRequirement.SHARE;
  }

  @Override
  public void contribute(
      AuthenticatedIdentity identity, AuthorizationCheck check, EntitySlice slice) {
    var share = shareRepository.findById(check.resourceId());
    if (share.isEmpty()) {
      return;
    }

    var found = share.get();
    slice.resourceAttribute(STRUCTURAL, new PrimBool(found.isStructural()));
    slice.resourceAttribute(
        OFFERED_BY_PRINCIPAL,
        new PrimBool(identity.accountId().equals(found.getOfferedByAccountId())));
    slice.resourceAttribute(
        PRINCIPAL_MEMBER_OF_TARGET, new PrimBool(memberOfTarget(identity, found)));
    slice.resourceAttribute(
        PRINCIPAL_ADMIN_OF_TARGET, new PrimBool(adminOfTarget(identity, found)));
    slice.resourceAttribute(
        DIRECTLY_MANAGED_BY_PRINCIPAL,
        new PrimBool(
            profileManagerRepository.existsByAccountIdAndProfileId(
                identity.accountId(), found.getProfileId())));
    slice.resourceAttribute(
        SOVEREIGN_OVER_PROFILE, new PrimBool(sovereignOverProfile(identity, found)));
  }

  private boolean adminOfTarget(AuthenticatedIdentity identity, ProfileHouseholdShare share) {
    return userAccountRepository
        .findById(identity.accountId())
        .filter(account -> account.getHouseholdRole() == HouseholdRole.ADMIN)
        .filter(account -> share.getHouseholdId().equals(account.getHouseholdId()))
        .isPresent();
  }

  private boolean memberOfTarget(AuthenticatedIdentity identity, ProfileHouseholdShare share) {
    return userAccountRepository
        .findById(identity.accountId())
        .filter(account -> share.getHouseholdId().equals(account.getHouseholdId()))
        .isPresent();
  }

  private boolean sovereignOverProfile(
      AuthenticatedIdentity identity, ProfileHouseholdShare share) {
    return userAccountRepository
        .findById(identity.accountId())
        .filter(account -> share.getProfileId().equals(account.getPersonalProfileId()))
        .flatMap(account -> profileRepository.findById(share.getProfileId()))
        .filter(profile -> !profile.isRestricted())
        .isPresent();
  }
}
