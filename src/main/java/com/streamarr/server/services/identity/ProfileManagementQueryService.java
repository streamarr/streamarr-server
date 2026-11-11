package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.HouseholdProfilesFilter;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.authorization.ProfileSafetyRule;
import com.streamarr.server.services.identity.AdministrationQueryService.ProfileAdministrationDetails;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Pager;
import java.util.LinkedHashMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads behind the Profile management pages of Household settings and Profile settings (ADR
 * 0024 §Read authorization). A reader who may not read a list gets it empty.
 */
@Service
@RequiredArgsConstructor
public class ProfileManagementQueryService {

  private final AuthorizationService authorizationService;
  private final AdministrationQueryService administrationQueryService;
  private final ManagedProfilesList managedProfilesList;
  private final HouseholdProfilesList householdProfilesList;
  private final ProfileRepository profileRepository;

  /**
   * A page of the Profiles the Account manages, in every Household, by name, then ID. Empty for a
   * device-bound session and for a disabled Account.
   */
  @Transactional(readOnly = true)
  public ListPage<ProfileAdministrationDetails> managedProfiles(
      AuthenticatedIdentity identity, PageRequest request) {
    if (!authorizationService.isAllowed(identity, new Intent.ViewManagedProfiles())) {
      return ListPage.empty();
    }

    return administrationQueryService.profileAdministrationDetails(
        Pager.page(managedProfilesList, identity.accountId(), request));
  }

  /**
   * A page of a Household's Profiles for Household settings, by name, then ID. Its HouseholdAdmins
   * and ServerAdmins get every Profile available there, the visitors shared in among them, and the
   * Profiles they manage that belong there but are not available there. Each row says whether the
   * Profile is a visitor and whether the Household's PIN safety rule locks it there, and never
   * where a visitor comes from.
   */
  @Transactional(readOnly = true)
  public ListPage<HouseholdProfileDetails> householdProfiles(
      AuthenticatedIdentity identity, UUID householdId, PageRequest request) {
    if (!authorizationService.isAllowed(
        identity, new Intent.ViewHouseholdAdministration(householdId))) {
      return ListPage.empty();
    }

    var filter = new HouseholdProfilesFilter(householdId, identity.accountId(), true);
    return describedIn(householdId, Pager.page(householdProfilesList, filter, request));
  }

  private ListPage<HouseholdProfileDetails> describedIn(UUID householdId, ListPage<Profile> page) {
    var profiles = page.rows().stream().map(Row::item).toList();
    var available =
        profileRepository.availableAmong(
            householdId, profiles.stream().map(Profile::getId).toList());
    // A Profile's lock depends only on itself and the Household's Kid Profiles.
    var ruleInputs = new LinkedHashMap<UUID, Profile>();
    profileRepository
        .findAvailableKidsInHousehold(householdId)
        .forEach(kid -> ruleInputs.put(kid.getId(), kid));
    profiles.stream()
        .filter(profile -> available.contains(profile.getId()))
        .forEach(profile -> ruleInputs.put(profile.getId(), profile));
    var locked = ProfileSafetyRule.lockedProfiles(ruleInputs.values());
    return administrationQueryService
        .profileAdministrationDetails(page)
        .map(
            details ->
                new HouseholdProfileDetails(
                    details,
                    !householdId.equals(details.profile().getHouseholdId()),
                    locked.contains(details.profile().getId())));
  }
}
