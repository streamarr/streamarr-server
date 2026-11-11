package com.streamarr.server.services.notification;

import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The guard of the sharing kinds (ADR 0063 §The sharing kinds): the share's read rule, that the
 * recipient may view the Profile's administration or the target Household's administration. Cedar
 * decides it for the recipient Account from the Account's stored relationships, never from a
 * token's claims.
 */
@Component
@RequiredArgsConstructor
class ProfileShareGuard {

  private final AuthorizationService authorizationService;

  NotificationVisibility visibility(UUID recipientAccountId, UUID profileId, UUID householdId) {
    var profile =
        authorizationService.decideForAccount(
            recipientAccountId, new Intent.ViewProfileAdministration(profileId));
    if (profile instanceof Decision.Allowed<AuthorizationUnit>) {
      return NotificationVisibility.SHOWN;
    }

    var household =
        authorizationService.decideForAccount(
            recipientAccountId, new Intent.ViewHouseholdAdministration(householdId));
    return switch (household) {
      case Decision.Allowed<AuthorizationUnit> _ -> NotificationVisibility.SHOWN;
      case Decision.Failed<AuthorizationUnit> _ -> NotificationVisibility.UNDECIDED;
      case Decision.Denied<AuthorizationUnit> _ ->
          profile instanceof Decision.Failed<AuthorizationUnit>
              ? NotificationVisibility.UNDECIDED
              : NotificationVisibility.HIDDEN;
    };
  }
}
