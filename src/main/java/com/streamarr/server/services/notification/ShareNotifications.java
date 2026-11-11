package com.streamarr.server.services.notification;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.notification.GuardedNotification;
import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.domain.notification.ShareNotification;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads stored Notifications with the shares they are about, and keeps the ones that the guard
 * shows their recipients. Every read of Notifications — the list, the unread count and live changes
 * — goes through here, so they agree on what a recipient sees.
 */
@Component
@RequiredArgsConstructor
class ShareNotifications {

  private final ProfileHouseholdShareRepository shareRepository;
  private final ProfileShareGuard guard;

  /**
   * The Notifications that the guard shows their recipients, in the order given, with their shares
   * read in one query. A Notification whose share was deleted meanwhile is gone with it.
   *
   * @throws AuthorizationUnavailableException when the guard cannot decide a Notification
   */
  List<ShareNotification> shown(Collection<Notification> notifications) {
    var shares =
        shareRepository
            .findAllById(notifications.stream().map(Notification::getShareId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(ProfileHouseholdShare::getId, Function.identity()));
    var read =
        notifications.stream()
            .flatMap(
                notification ->
                    Optional.ofNullable(shares.get(notification.getShareId()))
                        .map(share -> new ShareNotification(notification, share))
                        .stream())
            .toList();
    var visibilities = guard.visibilities(read.stream().map(ShareNotifications::guarded).toList());
    return read.stream().filter(notification -> shown(visibilities, notification)).toList();
  }

  private static GuardedNotification guarded(ShareNotification notification) {
    return new GuardedNotification(
        notification.notification().getId(),
        notification.notification().getRecipientAccountId(),
        notification.share().getProfileId(),
        notification.share().getHouseholdId());
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
