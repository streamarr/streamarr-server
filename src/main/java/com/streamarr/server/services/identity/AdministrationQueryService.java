package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Hides denied resource reads as not-found and gates the server catalogue as one surface. */
@Service
@RequiredArgsConstructor
public class AdministrationQueryService {

  private final AuthorizationService authorizationService;
  private final HouseholdRepository householdRepository;
  private final UserAccountRepository userAccountRepository;
  private final PaginationService paginationService;
  private final ProfileRepository profileRepository;
  private final AccountInvitationRepository accountInvitationRepository;

  public Optional<Household> householdAdministration(
      AuthenticatedIdentity identity, UUID householdId) {
    return switch (authorizationService.decide(
        identity, new Intent.ViewHouseholdAdministration(householdId))) {
      case Decision.Allowed<?> _ -> householdRepository.findById(householdId);
      case Decision.Denied<?> _ -> Optional.empty();
      case Decision.Failed<?> _ -> throw new AuthorizationUnavailableException();
    };
  }

  public Optional<UserAccount> accountAdministration(
      AuthenticatedIdentity identity, UUID accountId) {
    return switch (authorizationService.decide(
        identity, new Intent.ViewAccountAdministration(accountId))) {
      case Decision.Allowed<?> _ -> userAccountRepository.findById(accountId);
      case Decision.Denied<?> _ -> Optional.empty();
      case Decision.Failed<?> _ -> throw new AuthorizationUnavailableException();
    };
  }

  public Optional<ProfileAdministrationDetails> profileAdministration(
      AuthenticatedIdentity identity, UUID profileId) {
    return switch (authorizationService.decide(
        identity, new Intent.ViewProfileAdministration(profileId))) {
      case Decision.Allowed<?> _ ->
          profileRepository.findById(profileId).map(this::profileAdministrationDetails);
      case Decision.Denied<?> _ -> Optional.empty();
      case Decision.Failed<?> _ -> throw new AuthorizationUnavailableException();
    };
  }

  /** The details carry the live Account linkage the entity alone cannot answer. */
  public ProfileAdministrationDetails profileAdministrationDetails(Profile profile) {
    var linked = userAccountRepository.findByPersonalProfileId(profile.getId()).isPresent();
    return new ProfileAdministrationDetails(profile, linked);
  }

  public record ProfileAdministrationDetails(Profile profile, boolean linked) {}

  /** Every invitation, newest first — ServerAdmin's inspection surface. */
  public MediaPage<AccountInvitation> accountInvitations(
      AuthenticatedIdentity identity, MediaPaginationOptions options) {
    authorizationService.requireAllowed(identity, new Intent.ViewAccountInvitations());
    var items =
        accountInvitationRepository.findAdministrationPage(options).stream()
            .map(invitation -> new PageItem<>(invitation, invitation.getCreatedOn()))
            .toList();
    return paginationService.buildMediaPage(
        items, options.getPaginationOptions(), options.getCursorId());
  }

  /** A bounded page of Households on the server, in stable name-then-id order. */
  public MediaPage<Household> households(
      AuthenticatedIdentity identity, MediaPaginationOptions options) {
    authorizationService.requireAllowed(identity, new Intent.ViewHouseholds());
    var items =
        householdRepository.findAdministrationPage(options).stream()
            .map(household -> new PageItem<>(household, household.getName()))
            .toList();
    return paginationService.buildMediaPage(
        items, options.getPaginationOptions(), options.getCursorId());
  }

  /** A bounded page of Accounts for one already-authorized Household. */
  public Map<UUID, MediaPage<UserAccount>> accountPagesOf(
      Set<UUID> householdIds, MediaPaginationOptions options) {
    var accountsByHousehold = userAccountRepository.findAdministrationPages(householdIds, options);
    var pages = new LinkedHashMap<UUID, MediaPage<UserAccount>>();
    householdIds.forEach(
        householdId ->
            pages.put(
                householdId,
                accountPage(accountsByHousehold.getOrDefault(householdId, List.of()), options)));
    return pages;
  }

  private MediaPage<UserAccount> accountPage(
      List<UserAccount> accounts, MediaPaginationOptions options) {
    var items =
        accounts.stream()
            .map(account -> new PageItem<>(account, account.getDisplayName()))
            .toList();
    return paginationService.buildMediaPage(
        items, options.getPaginationOptions(), options.getCursorId());
  }
}
