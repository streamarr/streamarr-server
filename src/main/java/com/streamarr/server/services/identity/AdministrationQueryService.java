package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Administration reads (ADR 0024 §Read authorization): reads are as specific as writes. A denied
 * per-resource read is indistinguishable from a missing resource; the catalogue is a whole-surface
 * ServerAdmin gate. Accounts of a Household ride the Household decision that fetched it.
 */
@Service
@RequiredArgsConstructor
public class AdministrationQueryService {

  private final AuthorizationService authorizationService;
  private final HouseholdRepository householdRepository;
  private final UserAccountRepository userAccountRepository;

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

  /** Every Household on the server, in stable name-then-id order. */
  public List<Household> households(AuthenticatedIdentity identity) {
    authorizationService.requireAllowed(identity, new Intent.ViewHouseholds());
    return householdRepository.findAll().stream()
        .sorted(Comparator.comparing(Household::getName).thenComparing(Household::getId))
        .toList();
  }

  /** The Accounts of one already-authorized Household, in stable name-then-id order. */
  public List<UserAccount> accountsOf(UUID householdId) {
    return userAccountRepository.findByHouseholdId(householdId).stream()
        .sorted(Comparator.comparing(UserAccount::getDisplayName).thenComparing(UserAccount::getId))
        .toList();
  }
}
