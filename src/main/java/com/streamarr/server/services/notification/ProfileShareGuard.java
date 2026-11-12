package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.GuardedNotification;
import com.streamarr.server.domain.notification.ProfileShareSide;
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
 * The guard of the sharing kinds (ADR 0063 §The sharing kinds), decided by Cedar for the recipient
 * Account from the Account's stored relationships, never from a token's claims. A reader passes in
 * one of two ways. It may read the share now, by ADR 0024's rule for reading one share: a
 * ProfileManager of the share's Profile or a ServerAdmin at every status, which is the question the
 * share mutations ask of the offering side ({@code viewProfileShareHouseholds}), or a
 * HouseholdAdmin of the target Household while the offer is pending and not expired or the share is
 * active. Or the Notification was addressed to the Household's side, and the reader administers the
 * target Household now. The stored side decides, never the kind. The Household's side is one
 * question, the Cedar action of its own that carries the share's liveness and the stored side as
 * its context. Viewing the Profile's administration is never enough: a HouseholdAdmin of every
 * Household that hosts the Profile may, and a share into another Household is not theirs.
 *
 * <p>A batch asks each recipient's question about one Profile, or one Household with the same
 * liveness and side, once. The side with fewer distinct questions among a recipient's Notifications
 * is asked first, so a page of requests into one Household, or of news about one Profile, needs a
 * fixed number of decisions however long it is.
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
          distinct(notifications, RecipientDecisions::householdSide)
              <= distinct(notifications, RecipientDecisions::offeringSide);
    }

    NotificationVisibility visibility(GuardedNotification notification) {
      var profile = offeringSide(notification);
      var household = householdSide(notification);
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

    private static Intent.UnitIntent offeringSide(GuardedNotification notification) {
      return new Intent.ViewProfileShareHouseholds(notification.profileId());
    }

    private static Intent.UnitIntent householdSide(GuardedNotification notification) {
      return new Intent.ViewProfileShareNotification(
          notification.householdId(),
          notification.shareLive(),
          notification.addressedTo() == ProfileShareSide.HOUSEHOLD_SIDE);
    }

    private static long distinct(
        List<GuardedNotification> notifications,
        Function<GuardedNotification, Intent.UnitIntent> question) {
      return notifications.stream().map(question).distinct().count();
    }
  }
}
