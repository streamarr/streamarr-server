package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.authorization.ProfileSafetyRule;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Pager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model behind the GraphQL me query: Account, Households, and the context's Profile picker.
 */
@Service
@RequiredArgsConstructor
public class IdentityQueryService {

  private final UserAccountRepository userAccountRepository;
  private final HouseholdRepository householdRepository;
  private final ProfileRepository profileRepository;
  private final AuthorizationService authorizationService;
  private final SelectableProfilesList selectableProfilesList;
  private final UsableHouseholdsList usableHouseholdsList;

  @Transactional(readOnly = true)
  public MeDetails meDetails(AuthenticatedIdentity identity) {
    var account = requireAuthorizedAccount(identity);

    var membership = summary(account.getHouseholdId());
    var context = summary(identity.contextHouseholdId());
    return MeDetails.builder()
        .account(account)
        .scope(identity.scope())
        .household(membership)
        .contextHousehold(context)
        .deviceBound(false)
        .build();
  }

  /** A page of the Profiles selectable in the context Household, by name, then ID. */
  @Transactional(readOnly = true)
  public ListPage<SelectableProfileDetails> selectableProfiles(
      AuthenticatedIdentity identity, PageRequest request) {
    var account = requireAuthorizedAccount(identity);
    var householdId = identity.contextHouseholdId();
    var page = Pager.page(selectableProfilesList, householdId, request);
    var ruleInputs = new LinkedHashMap<UUID, Profile>();
    profileRepository
        .findAvailableKidsInHousehold(householdId)
        .forEach(kid -> ruleInputs.put(kid.getId(), kid));
    page.rows().forEach(row -> ruleInputs.put(row.item().getId(), row.item()));
    // A Profile's lock depends only on itself and the Household's Kid Profiles.
    var locked = ProfileSafetyRule.lockedProfiles(ruleInputs.values());
    return page.map(profile -> selectable(identity, account, profile, locked));
  }

  /**
   * A page of the Households the Account may use: its own Household first, then the Households its
   * Personal Profile visits, by name, then ID.
   */
  @Transactional(readOnly = true)
  public ListPage<UsableHouseholdDetails> usableHouseholds(
      AuthenticatedIdentity identity, PageRequest request) {
    var account = requireAuthorizedAccount(identity);
    return Pager.page(usableHouseholdsList, account.getId(), request)
        .map(
            household ->
                new UsableHouseholdDetails(
                    summaryOf(household), household.getId().equals(account.getHouseholdId())));
  }

  @Transactional(readOnly = true)
  public Optional<SelectableProfileDetails> selectedProfile(AuthenticatedIdentity identity) {
    var account = requireAuthorizedAccount(identity);
    return selectableProfiles(identity, account).stream()
        .filter(SelectableProfileDetails::selected)
        .findFirst();
  }

  private UserAccount requireAuthorizedAccount(AuthenticatedIdentity identity) {
    var account =
        userAccountRepository
            .findById(identity.accountId())
            .orElseThrow(AuthenticationRequiredException::new);
    authorizationService.requireAllowed(identity, new Intent.ViewProfilePicker());
    return account;
  }

  /** Membership Household first, then visited Households in stable id order. */
  private List<SelectableProfileDetails> selectableProfiles(
      AuthenticatedIdentity identity, UserAccount account) {
    var available = profileRepository.findAvailableInHousehold(identity.contextHouseholdId());
    var locked = ProfileSafetyRule.lockedProfiles(available);
    return available.stream()
        .map(profile -> selectable(identity, account, profile, locked))
        .toList();
  }

  private static SelectableProfileDetails selectable(
      AuthenticatedIdentity identity, UserAccount account, Profile profile, Set<UUID> locked) {
    return SelectableProfileDetails.builder()
        .id(profile.getId())
        .name(profile.getName())
        .picture(Optional.ofNullable(profile.getPicture()))
        .kind(profile.getKind())
        .personal(profile.getId().equals(account.getPersonalProfileId()))
        .pinConfigured(profile.hasEffectivePin())
        .locked(locked.contains(profile.getId()))
        .selected(profile.getId().equals(identity.profileId()))
        .build();
  }

  private HouseholdSummaryDetails summary(UUID householdId) {
    return householdRepository
        .findById(householdId)
        .map(IdentityQueryService::summaryOf)
        .orElseThrow(AuthenticationRequiredException::new);
  }

  private static HouseholdSummaryDetails summaryOf(Household household) {
    return new HouseholdSummaryDetails(household.getId(), household.getName());
  }

  @Builder
  public record MeDetails(
      UserAccount account,
      TokenScope scope,
      HouseholdSummaryDetails household,
      HouseholdSummaryDetails contextHousehold,
      boolean deviceBound) {

    public HouseholdRole householdRole() {
      return account.getHouseholdRole();
    }
  }

  public record HouseholdSummaryDetails(UUID id, String name) {}

  public record UsableHouseholdDetails(HouseholdSummaryDetails household, boolean membership) {}

  @Builder
  public record SelectableProfileDetails(
      UUID id,
      String name,
      Optional<String> picture,
      ProfileKind kind,
      boolean personal,
      boolean pinConfigured,
      boolean locked,
      boolean selected) {}
}
