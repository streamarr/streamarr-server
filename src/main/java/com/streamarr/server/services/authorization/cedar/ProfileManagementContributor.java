package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.value.PrimBool;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

  // A batch holds reads only: each relationship is read once for all its Profiles, and no direct
  // management is locked.
  @Override
  public void contributeEach(
      AuthenticatedIdentity identity, Map<AuthorizationCheck, EntitySlice> slices) {
    var profileIds =
        slices.keySet().stream().map(AuthorizationCheck::resourceId).collect(Collectors.toSet());
    var relationships = relationshipsTo(identity, profileIds);
    slices.forEach((check, slice) -> describe(slice, relationships.to(check.resourceId())));
  }

  private PrincipalRelationships relationshipsTo(
      AuthenticatedIdentity identity, Set<UUID> profileIds) {
    var accountId = identity.accountId();
    return PrincipalRelationships.builder()
        .restricted(
            profileRepository.findAllById(profileIds).stream()
                .collect(Collectors.toMap(Profile::getId, Profile::isRestricted)))
        .personalProfileId(
            userAccountRepository
                .findById(accountId)
                .map(UserAccount::getPersonalProfileId)
                .orElse(null))
        .directlyManaged(
            profileManagerRepository.findByAccountIdAndProfileIdIn(accountId, profileIds).stream()
                .map(ProfileManager::getProfileId)
                .collect(Collectors.toSet()))
        .personalProfiles(
            userAccountRepository.findByPersonalProfileIdIn(profileIds).stream()
                .map(UserAccount::getPersonalProfileId)
                .collect(Collectors.toSet()))
        .availableInPrincipalHousehold(
            shareRepository.findProfileIdsActivelySharedInto(identity.householdId(), profileIds))
        .build();
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

  /** The principal's relationships to a batch of Profiles, each read once for them all. */
  @Builder
  private record PrincipalRelationships(
      Map<UUID, Boolean> restricted,
      UUID personalProfileId,
      Set<UUID> directlyManaged,
      Set<UUID> personalProfiles,
      Set<UUID> availableInPrincipalHousehold) {

    ProfileManagementFacts to(UUID profileId) {
      var isRestricted = restricted.get(profileId);
      if (isRestricted == null) {
        throw new InvalidEntitySliceException("Profile facts are unavailable.");
      }

      return ProfileManagementFacts.builder()
          .selfManaged(!isRestricted && profileId.equals(personalProfileId))
          .directManager(directlyManaged.contains(profileId))
          .sovereignPersonal(!isRestricted && personalProfiles.contains(profileId))
          .availableInPrincipalHousehold(availableInPrincipalHousehold.contains(profileId))
          .build();
    }
  }
}
