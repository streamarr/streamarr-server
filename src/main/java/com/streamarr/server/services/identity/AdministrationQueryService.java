package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Pager;
import com.streamarr.server.services.pagination.ServerScope;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Hides denied resource reads as not-found and gates the server catalogue as one surface. */
@Service
@Builder
@RequiredArgsConstructor
public class AdministrationQueryService {

  private final AuthorizationService authorizationService;
  private final HouseholdRepository householdRepository;
  private final UserAccountRepository userAccountRepository;
  private final ProfileRepository profileRepository;
  private final HouseholdsList householdsList;
  private final AccountInvitationsList accountInvitationsList;
  private final HouseholdAccountsList householdAccountsList;

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
    if (profileRepository.findById(profileId).isEmpty()) {
      return Optional.empty();
    }

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

  /** A page of every invitation, newest first, then by ID descending. */
  public ListPage<AccountInvitation> accountInvitations(
      AuthenticatedIdentity identity, PageRequest request) {
    authorizationService.requireAllowed(identity, new Intent.ViewAccountInvitations());
    return Pager.page(accountInvitationsList, ServerScope.SERVER, request);
  }

  /** A page of the server's Households, ordered by name, then ID. */
  public ListPage<Household> households(AuthenticatedIdentity identity, PageRequest request) {
    authorizationService.requireAllowed(identity, new Intent.ViewHouseholds());
    return Pager.page(householdsList, ServerScope.SERVER, request);
  }

  /** A page of Accounts for each already-authorized Household, ordered by display name, then ID. */
  public Map<UUID, ListPage<UserAccount>> accountPages(Map<UUID, PageRequest> requests) {
    return Pager.pages(householdAccountsList, requests);
  }
}
