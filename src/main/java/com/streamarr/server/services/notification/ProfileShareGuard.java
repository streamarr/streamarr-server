package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.GuardedNotification;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The guard of the sharing kinds (ADR 0063 §The sharing kinds): the share's read rule, that the
 * recipient may view the Profile's shares, as its ProfileManagers and ServerAdmins may, or the
 * target Household's administration. These are the questions that decide who may see one share (ADR
 * 0024). Viewing the Profile's administration is not enough: a HouseholdAdmin of any Household that
 * hosts the Profile may, and a share into another Household is not theirs. Cedar decides it for the
 * recipient Account from the Account's stored relationships, never from a token's claims.
 *
 * <p>A batch asks each recipient's question about one Profile or one Household once. The side with
 * fewer distinct values among a recipient's Notifications is asked first, so a page of requests
 * into one Household, or of news about one Profile, needs a fixed number of decisions however long
 * it is.
 */
@Component
@RequiredArgsConstructor
class ProfileShareGuard {

  private final AuthorizationService authorizationService;

  /** What the guard decides for each Notification, keyed by the Notification's id. */
  Map<UUID, NotificationVisibility> visibilities(Collection<GuardedNotification> notifications) {
    var visibilities = new HashMap<UUID, NotificationVisibility>();
    notifications.stream()
        .collect(Collectors.groupingBy(GuardedNotification::recipientAccountId))
        .forEach(
            (recipient, ofRecipient) -> {
              var decisions = new RecipientDecisions(recipient, ofRecipient);
              ofRecipient.forEach(
                  notification ->
                      visibilities.put(
                          notification.notificationId(), decisions.visibility(notification)));
            });
    return visibilities;
  }

  /** One recipient's answers over one batch, each question asked once. */
  private final class RecipientDecisions {

    private final UUID recipient;
    private final boolean householdFirst;
    private final Map<Intent.UnitIntent, Decision<AuthorizationUnit>> answers = new HashMap<>();

    private RecipientDecisions(UUID recipient, List<GuardedNotification> notifications) {
      this.recipient = recipient;
      this.householdFirst =
          distinct(notifications, GuardedNotification::householdId)
              <= distinct(notifications, GuardedNotification::profileId);
    }

    NotificationVisibility visibility(GuardedNotification notification) {
      Intent.UnitIntent profile = new Intent.ViewProfileShareHouseholds(notification.profileId());
      Intent.UnitIntent household =
          new Intent.ViewHouseholdAdministration(notification.householdId());
      var first = decide(householdFirst ? household : profile);
      if (first instanceof Decision.Allowed<AuthorizationUnit>) {
        return NotificationVisibility.SHOWN;
      }

      var second = decide(householdFirst ? profile : household);
      return switch (second) {
        case Decision.Allowed<AuthorizationUnit> _ -> NotificationVisibility.SHOWN;
        case Decision.Failed<AuthorizationUnit> _ -> NotificationVisibility.UNDECIDED;
        case Decision.Denied<AuthorizationUnit> _ ->
            first instanceof Decision.Failed<AuthorizationUnit>
                ? NotificationVisibility.UNDECIDED
                : NotificationVisibility.HIDDEN;
      };
    }

    private Decision<AuthorizationUnit> decide(Intent.UnitIntent intent) {
      return answers.computeIfAbsent(
          intent, asked -> authorizationService.decideForAccount(recipient, asked));
    }

    private static long distinct(
        List<GuardedNotification> notifications, Function<GuardedNotification, UUID> id) {
      return notifications.stream().map(id).distinct().count();
    }
  }
}
