package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
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
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
  private final PaginationService paginationService;

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

  @Transactional(readOnly = true)
  public MediaPage<SelectableProfileDetails> selectableProfiles(
      AuthenticatedIdentity identity, KeysetPaginationOptions options) {
    var account = requireAuthorizedAccount(identity);
    var items =
        selectableProfiles(identity, account).stream()
            .map(profile -> new PageItem<>(profile, profile.name()))
            .toList();
    return paginationService.buildKeysetPage(items, options, SelectableProfileDetails::id);
  }

  @Transactional(readOnly = true)
  public MediaPage<UsableHouseholdDetails> usableHouseholds(
      AuthenticatedIdentity identity, KeysetPaginationOptions options) {
    var account = requireAuthorizedAccount(identity);
    var items =
        usableHouseholds(account).values().stream()
            .map(
                household ->
                    new UsableHouseholdDetails(
                        summaryOf(household), household.getId().equals(account.getHouseholdId())))
            .map(household -> new PageItem<>(household, household.membership() ? 0 : 1))
            .toList();
    return paginationService.buildKeysetPage(
        items, options, household -> household.household().id());
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
  private Map<UUID, Household> usableHouseholds(UserAccount account) {
    var ids = userAccountRepository.findUsableHouseholdIds(account.getId());
    var byId =
        householdRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(Household::getId, Function.identity()));
    var ordered = new LinkedHashMap<UUID, Household>();
    for (var id : ids) {
      var household = byId.get(id);
      if (household != null) {
        ordered.put(id, household);
      }
    }

    return ordered;
  }

  private List<SelectableProfileDetails> selectableProfiles(
      AuthenticatedIdentity identity, UserAccount account) {
    var available = profileRepository.findAvailableInHousehold(identity.contextHouseholdId());
    var locked = ProfileSafetyRule.lockedProfiles(available);
    return available.stream()
        .map(
            profile ->
                SelectableProfileDetails.builder()
                    .id(profile.getId())
                    .name(profile.getName())
                    .picture(Optional.ofNullable(profile.getPicture()))
                    .kind(profile.getKind())
                    .personal(profile.getId().equals(account.getPersonalProfileId()))
                    .pinConfigured(profile.hasEffectivePin())
                    .locked(locked.contains(profile.getId()))
                    .selected(profile.getId().equals(identity.profileId()))
                    .build())
        .toList();
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
