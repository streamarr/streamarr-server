package com.streamarr.server.services.notification;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.notification.GuardedNotification;
import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.domain.notification.ShareNotification;
import com.streamarr.server.domain.notification.SubjectNotification;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The sharing kinds' Notifications read with the shares they are about, and guarded by their own
 * guard ({@link ProfileShareGuard}), from each share as it stands when the batch is read and the
 * side each Notification was addressed to. Each batch reads its shares in one query.
 */
@Component
@RequiredArgsConstructor
class ShareNotifications implements NotificationSubjects {

  private final ProfileHouseholdShareRepository shareRepository;
  private final ProfileShareGuard guard;
  private final Clock clock;

  @Override
  public List<SubjectNotification> shown(List<Notification> notifications) {
    var read = withShares(notifications);
    var visibilities = guard.visibilities(guarded(read));
    return read.stream()
        .filter(notification -> shown(visibilities, notification))
        .<SubjectNotification>map(notification -> notification)
        .toList();
  }

  @Override
  public Map<UUID, NotificationVisibility> visibilities(List<Notification> notifications) {
    return guard.visibilities(guarded(withShares(notifications)));
  }

  // A Notification whose share was deleted meanwhile is gone with it.
  private List<ShareNotification> withShares(List<Notification> notifications) {
    var shares =
        shareRepository
            .findAllById(notifications.stream().map(Notification::getShareId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(ProfileHouseholdShare::getId, Function.identity()));
    return notifications.stream()
        .flatMap(
            notification ->
                Optional.ofNullable(shares.get(notification.getShareId()))
                    .map(share -> new ShareNotification(notification, share))
                    .stream())
        .toList();
  }

  private List<GuardedNotification> guarded(List<ShareNotification> notifications) {
    var now = clock.instant();
    return notifications.stream()
        .map(
            read ->
                GuardedNotification.builder()
                    .notificationId(read.notification().getId())
                    .recipientAccountId(read.notification().getRecipientAccountId())
                    .profileId(read.share().getProfileId())
                    .householdId(read.share().getHouseholdId())
                    .shareLive(read.share().statusAt(now).isLive())
                    .addressedTo(read.notification().getAddressedTo())
                    .build())
        .toList();
  }

  private static boolean shown(
      Map<UUID, NotificationVisibility> visibilities, ShareNotification notification) {
    return switch (visibilities.get(notification.notification().getId())) {
      case SHOWN -> true;
      case HIDDEN -> false;
      case UNDECIDED -> throw new AuthorizationUnavailableException();
    };
  }
}
