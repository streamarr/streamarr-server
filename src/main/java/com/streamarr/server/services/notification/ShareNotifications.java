package com.streamarr.server.services.notification;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.notification.GuardedNotification;
import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.domain.notification.ShareNotification;
import com.streamarr.server.domain.notification.SubjectNotification;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The sharing kinds' Notifications read with the shares they are about, and guarded by the share's
 * read rule ({@link ProfileShareGuard}). Each batch reads its shares in one query.
 */
@Component
@RequiredArgsConstructor
class ShareNotifications implements NotificationSubjects {

  private final ProfileHouseholdShareRepository shareRepository;
  private final ProfileShareGuard guard;

  @Override
  public List<SubjectNotification> shown(List<Notification> notifications) {
    var read = withShares(notifications);
    var visibilities = guard.visibilities(read.stream().map(ShareNotifications::guarded).toList());
    return read.stream()
        .filter(notification -> shown(visibilities, notification))
        .<SubjectNotification>map(notification -> notification)
        .toList();
  }

  @Override
  public Map<UUID, NotificationVisibility> visibilities(List<Notification> notifications) {
    return guard.visibilities(
        withShares(notifications).stream().map(ShareNotifications::guarded).toList());
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
