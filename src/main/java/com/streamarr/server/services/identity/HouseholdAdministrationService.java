package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Coordinates authorized Household mutations with oracle-safe denial handling. */
@Service
@RequiredArgsConstructor
public class HouseholdAdministrationService {

  private final AuthorizationService authorizationService;
  private final HouseholdRepository householdRepository;
  private final MutationTransactions mutationTransactions;

  public Outcome<Household, AdministrationRejections.CreateHousehold> createHousehold(
      AuthenticatedIdentity identity, String name) {
    var transactional =
        mutationTransactions.write(
            () -> createHouseholdInsideTransaction(identity, name),
            _ -> Optional.<AdministrationRejections.CreateHousehold>empty());
    return transactional.fold(outcome -> outcome, Outcome::rejected);
  }

  public Outcome<Household, AdministrationRejections.RenameHousehold> renameHousehold(
      AuthenticatedIdentity identity, UUID householdId, String name) {
    var transactional =
        mutationTransactions.write(
            () -> renameHouseholdInsideTransaction(identity, householdId, name),
            _ -> Optional.<AdministrationRejections.RenameHousehold>empty());
    return transactional.fold(outcome -> outcome, Outcome::rejected);
  }

  private Outcome<Household, AdministrationRejections.CreateHousehold>
      createHouseholdInsideTransaction(AuthenticatedIdentity identity, String name) {
    authorizationService.requireAllowed(identity, new Intent.CreateHousehold());
    if (isBlank(name)) {
      return Outcome.rejected(new AdministrationRejections.HouseholdNameRequired());
    }

    var household = Household.builder().name(name.strip()).build();
    return Outcome.accepted(householdRepository.saveAndFlush(household));
  }

  private Outcome<Household, AdministrationRejections.RenameHousehold>
      renameHouseholdInsideTransaction(
          AuthenticatedIdentity identity, UUID householdId, String name) {
    var refusal = refusalOf(identity, householdId);
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }

    if (isBlank(name)) {
      return Outcome.rejected(new AdministrationRejections.HouseholdNameRequired());
    }

    var household = householdRepository.findById(householdId);
    if (household.isEmpty()) {
      return Outcome.rejected(new AdministrationRejections.HouseholdNotFound());
    }

    householdRepository.tryRename(householdId, name.strip());
    householdRepository.refresh(household.get());
    return Outcome.accepted(household.get());
  }

  private Optional<AdministrationRejections.RenameHousehold> refusalOf(
      AuthenticatedIdentity identity, UUID householdId) {
    return switch (authorizationService.decide(identity, new Intent.RenameHousehold(householdId))) {
      case Decision.Allowed<?> _ -> Optional.empty();
      case Decision.Failed<?> _ -> throw new AuthorizationUnavailableException();
      case Decision.Denied<?> _ -> {
        if (mayViewHousehold(identity, householdId)) {
          throw new AccessDeniedException("Not allowed.");
        }

        yield Optional.of(new AdministrationRejections.HouseholdNotFound());
      }
    };
  }

  private boolean mayViewHousehold(AuthenticatedIdentity identity, UUID householdId) {
    return authorizationService.isAllowed(
        identity, new Intent.ViewHouseholdAdministration(householdId));
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
