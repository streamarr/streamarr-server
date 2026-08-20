package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.PrimBool;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ADR 0024 §Restricted Profile supervision, read live: the principal supervises the resource
 * Profile while the Profile is restricted, actively shared into the principal's live membership
 * Household, and the principal is an eligible HouseholdAdmin there. Derived authority — it ends
 * with the share and never creates a portable relationship.
 */
@Component
@RequiredArgsConstructor
class ProfileSupervisionContributor implements FactContributor {

  static final String SUPERVISED_BY_PRINCIPAL = "supervisedByPrincipal";

  private final UserAccountRepository userAccountRepository;
  private final ProfileRepository profileRepository;
  private final ProfileHouseholdShareRepository shareRepository;

  @Override
  public FactRequirement provides() {
    return FactRequirement.PROFILE_SUPERVISION;
  }

  @Override
  public void contribute(
      AuthenticatedIdentity identity, AuthorizationCheck check, EntitySlice slice) {
    slice.resourceAttribute(
        SUPERVISED_BY_PRINCIPAL, new PrimBool(supervises(identity, check.resourceId())));
  }

  private boolean supervises(AuthenticatedIdentity identity, UUID profileId) {
    var restricted = profileRepository.findById(profileId).map(Profile::isRestricted).orElse(false);
    if (!restricted) {
      return false;
    }
    return userAccountRepository
        .findById(identity.accountId())
        .filter(account -> account.getHouseholdRole() == HouseholdRole.ADMIN)
        .filter(
            account ->
                profileRepository
                    .findById(account.getPersonalProfileId())
                    .map(personal -> !personal.isRestricted())
                    .orElse(false))
        .map(account -> shareRepository.isActivelyShared(profileId, account.getHouseholdId()))
        .orElse(false);
  }
}
