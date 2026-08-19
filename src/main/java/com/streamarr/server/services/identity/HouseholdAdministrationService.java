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

/**
 * Household administration (ADR 0024 §Household): creating an empty Household is live ServerAdmin
 * work — a whole-surface gate, so its denial stays a top-level FORBIDDEN. Renaming belongs to that
 * Household's live HouseholdAdmins and ServerAdmin; a hidden Household refuses as not-found.
 */
@Service
@RequiredArgsConstructor
public class HouseholdAdministrationService {

  private final AuthorizationService authorizationService;
  private final HouseholdRepository householdRepository;
  private final MutationTransactions mutationTransactions;

  public Outcome<Household, AdministrationRejections.CreateHousehold> createHousehold(
      AuthenticatedIdentity identity, String name) {
    authorizationService.requireAllowed(identity, new Intent.CreateHousehold());
    if (isBlank(name)) {
      return Outcome.rejected(new AdministrationRejections.HouseholdNameRequired());
    }
    var household = Household.builder().name(name.strip()).build();
    return mutationTransactions.write(
        () -> householdRepository.saveAndFlush(household), _ -> Optional.empty());
  }

  public Outcome<Household, AdministrationRejections.RenameHousehold> renameHousehold(
      AuthenticatedIdentity identity, UUID householdId, String name) {
    var refusal = refusalOf(identity, householdId);
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }
    if (isBlank(name)) {
      return Outcome.rejected(new AdministrationRejections.HouseholdNameRequired());
    }
    if (householdRepository.findById(householdId).isEmpty()) {
      return Outcome.rejected(new AdministrationRejections.HouseholdNotFound());
    }
    return mutationTransactions.write(
        () -> {
          householdRepository.tryRename(householdId, name.strip());
          return householdRepository.findById(householdId).orElseThrow();
        },
        _ -> Optional.empty());
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
    return authorizationService.decide(
            identity, new Intent.ViewHouseholdAdministration(householdId))
        instanceof Decision.Allowed<?>;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
