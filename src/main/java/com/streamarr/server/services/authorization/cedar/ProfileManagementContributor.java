package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.PrimBool;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.UUID;
import lombok.Builder;
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
  static final String DIRECTLY_MANAGED_BY_PRINCIPAL = "directlyManagedByPrincipal";
  static final String SOVEREIGN_PERSONAL_PROFILE_OF_PRINCIPAL =
      "sovereignPersonalProfileOfPrincipal";
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
    describe(
        slice,
        ProfileManagementFacts.builder()
            .selfManaged(
                !restricted
                    && userAccountRepository
                        .findById(identity.accountId())
                        .map(account -> profileId.equals(account.getPersonalProfileId()))
                        .orElse(false))
            .directManager(directlyManages(identity, check, profileId))
            .sovereignPersonal(
                !restricted && userAccountRepository.findByPersonalProfileId(profileId).isPresent())
            .availableInPrincipalHousehold(
                shareRepository.isActivelyShared(profileId, identity.householdId()))
            .build());
  }

  private static void describe(EntitySlice slice, ProfileManagementFacts facts) {
    var selfManaged = facts.selfManaged();
    var directManager = facts.directManager();
    slice.resourceAttribute(MANAGED_BY_PRINCIPAL, new PrimBool(selfManaged || directManager));
    // Relinquishing needs a stored grant to give up; self-management has no row to relinquish.
    slice.resourceAttribute(DIRECTLY_MANAGED_BY_PRINCIPAL, new PrimBool(directManager));
    // The sovereign Account curates its own Personal Profile's managers.
    slice.resourceAttribute(SOVEREIGN_PERSONAL_PROFILE_OF_PRINCIPAL, new PrimBool(selfManaged));
    // A self-managed Personal Profile is offered only by its own Account or ServerAdmin (ADR 0024
    // §Profile sharing) — acceptance admits the person, so a retained direct manager cannot offer
    // it. selfManaged implies a sovereign Personal Profile, so the sovereign arm needs only it and
    // the other arm needs only the direct grant.
    slice.resourceAttribute(
        OFFERABLE_BY_PRINCIPAL,
        new PrimBool(facts.sovereignPersonal() ? selfManaged : directManager));
    slice.resourceAttribute(
        AVAILABLE_IN_PRINCIPAL_HOUSEHOLD, new PrimBool(facts.availableInPrincipalHousehold()));
  }

  private boolean directlyManages(
      AuthenticatedIdentity identity, AuthorizationCheck check, UUID profileId) {
    if (check.action().authorizesWrite()) {
      return profileManagerRepository.lockDirectManagement(identity.accountId(), profileId);
    }

    return profileManagerRepository.existsByAccountIdAndProfileId(identity.accountId(), profileId);
  }

  /** The principal's relationship to one Profile, as read before it is set on the slice. */
  @Builder
  private record ProfileManagementFacts(
      boolean selfManaged,
      boolean directManager,
      boolean sovereignPersonal,
      boolean availableInPrincipalHousehold) {}
}
