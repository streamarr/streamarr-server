package com.streamarr.server.services.identity;

import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.ShareCodeRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A live share code stays live only while the Account that issued it may still issue a share code
 * for the code's Household, as a HouseholdAdmin of it or as ServerAdmin, and the system invalidates
 * it once that Account may not, is disabled, or is deleted (ADR 0024 §Share codes). The question is
 * the issuing decision itself, asked again of the Account as it now stands, not the role the code
 * was issued under.
 *
 * <p>Provisional (issue #870, decision 10): the rule lives only here, and every change that can
 * take the authority away calls it inside the changing transaction, after the change, or before a
 * deletion once it holds the issuer's row, so reversing the rule means removing this class and its
 * calls.
 */
@Component
@RequiredArgsConstructor
class ShareCodeIssuerAuthority {

  private final ShareCodeRepository shareCodeRepository;
  private final UserAccountRepository userAccountRepository;
  private final AuthorizationService authorizationService;

  /**
   * Invalidates the issuer's live codes for each Household it may no longer issue one for, after a
   * change to its roles or its Household; answers how many it invalidated. The decision depends on
   * the Household alone, so it is asked once for each Household the issuer holds live codes for.
   */
  int afterIssuerChanged(UUID issuerAccountId, Instant now) {
    // The change was written past the managed copy, and the decision reads the role from it.
    userAccountRepository.findByIdAndReloadFromDatabase(issuerAccountId);
    return shareCodeRepository.findHouseholdIdsWithLiveCodesIssuedBy(issuerAccountId, now).stream()
        .filter(householdId -> !mayStillIssue(issuerAccountId, householdId))
        .mapToInt(
            householdId ->
                shareCodeRepository.invalidateLiveIssuedFor(issuerAccountId, householdId, now))
        .sum();
  }

  /**
   * Invalidates every live code of an issuer just disabled, which may issue none, without asking
   * about each; answers how many.
   */
  int afterIssuerDisabled(UUID issuerAccountId, Instant now) {
    return shareCodeRepository.invalidateLiveIssuedBy(issuerAccountId, now);
  }

  /** Invalidates every live code of an issuer about to be deleted; answers how many. */
  int beforeIssuerDeleted(UUID issuerAccountId, Instant now) {
    // An issue holds the issuer's row while it decides and stores, so once this lock is granted
    // every code it stored has committed and is read below, and a later issue finds no Account.
    userAccountRepository.lockById(issuerAccountId);
    return shareCodeRepository.invalidateLiveIssuedBy(issuerAccountId, now);
  }

  private boolean mayStillIssue(UUID issuerAccountId, UUID householdId) {
    return switch (authorizationService.decideForAccount(
        issuerAccountId, new Intent.IssueShareCode(householdId))) {
      case Decision.Allowed<AuthorizationUnit> _ -> true;
      case Decision.Denied<AuthorizationUnit> _ -> false;
      case Decision.Failed<AuthorizationUnit> _ -> throw new AuthorizationUnavailableException();
    };
  }
}
