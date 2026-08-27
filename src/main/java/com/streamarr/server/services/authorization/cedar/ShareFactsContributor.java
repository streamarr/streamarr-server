package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.PrimBool;
import com.streamarr.server.domain.auth.AccountShareFacts;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The resource Share's structural flag and the principal's live relationships to it: target
 * Household admin (live role), direct manager of the Profile, sovereign Account over its own
 * Personal Profile, or the offerer. A missing Share contributes nothing; the service answers
 * not-found before Cedar is asked, so the ServerAdmin arms never meet an empty resource.
 */
@Component
@RequiredArgsConstructor
class ShareFactsContributor implements FactContributor {

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
    return FactRequirement.SHARE_FACTS;
  }

  @Override
  public void contribute(
      AuthenticatedIdentity identity, AuthorizationCheck check, EntitySlice slice) {
    var share = shareRepository.findById(check.resourceId());
    if (share.isEmpty()) {
      return;
    }

    var found = share.get();
    var unrestrictedProfile = profileRepository.lockIfUnrestricted(found.getProfileId());
    var principal = userAccountRepository.findShareFacts(identity.accountId());
    var sovereign = sovereignOverProfile(principal, found, unrestrictedProfile);
    var directlyManaged =
        profileManagerRepository
            .findByAccountIdAndProfileId(identity.accountId(), found.getProfileId())
            .isPresent();
    slice.resourceAttribute(STRUCTURAL, new PrimBool(found.isStructural()));
    slice.resourceAttribute(
        OFFERED_BY_PRINCIPAL,
        new PrimBool(identity.accountId().equals(found.getOfferedByAccountId())));
    slice.resourceAttribute(
        PRINCIPAL_MEMBER_OF_TARGET, new PrimBool(memberOfTarget(principal, found)));
    slice.resourceAttribute(
        PRINCIPAL_ADMIN_OF_TARGET, new PrimBool(adminOfTarget(principal, found)));
    slice.resourceAttribute(DIRECTLY_MANAGED_BY_PRINCIPAL, new PrimBool(directlyManaged));
    slice.resourceAttribute(SOVEREIGN_OVER_PROFILE, new PrimBool(sovereign));
  }

  private boolean adminOfTarget(
      Optional<AccountShareFacts> principal, ProfileHouseholdShare share) {
    return principal
        .filter(account -> account.householdRole() == HouseholdRole.ADMIN)
        .filter(account -> share.getHouseholdId().equals(account.householdId()))
        .isPresent();
  }

  private boolean memberOfTarget(
      Optional<AccountShareFacts> principal, ProfileHouseholdShare share) {
    return principal
        .filter(account -> share.getHouseholdId().equals(account.householdId()))
        .isPresent();
  }

  private boolean sovereignOverProfile(
      Optional<AccountShareFacts> principal,
      ProfileHouseholdShare share,
      boolean unrestrictedProfile) {
    if (!unrestrictedProfile) {
      return false;
    }

    return principal
        .filter(account -> share.getProfileId().equals(account.personalProfileId()))
        .isPresent();
  }
}
