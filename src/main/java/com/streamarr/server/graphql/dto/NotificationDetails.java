package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.ProfileShareAnswer;
import com.streamarr.server.domain.notification.ProfileShareEndedBy;
import com.streamarr.server.domain.notification.RequestResolution;
import com.streamarr.server.domain.notification.ShareNotification;
import com.streamarr.server.domain.notification.SubjectNotification;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/**
 * A Notification as the schema shows it, read at one instant. {@code kind} picks the concrete type
 * and {@code keptProfileName} is not a schema field: a reader who may no longer see the share's
 * Profile reads it as the Profile's name.
 */
@Builder
public record NotificationDetails(
    UUID id,
    NotificationKind kind,
    String createdAt,
    String latestEventAt,
    boolean unread,
    String expiresAt,
    RequestResolution resolution,
    ProfileShareAnswer answer,
    ProfileShareEndedBy endedBy,
    ProfileShareDetails share,
    String keptProfileName) {

  public static NotificationDetails from(SubjectNotification read, Instant now) {
    return switch (read) {
      case ShareNotification shareNotification -> fromShare(shareNotification, now);
    };
  }

  private static NotificationDetails fromShare(ShareNotification shareNotification, Instant now) {
    var notification = shareNotification.notification();
    var share = shareNotification.share();
    return NotificationDetails.builder()
        .id(notification.getId())
        .kind(notification.getKind())
        .createdAt(notification.getCreatedOn().toString())
        .latestEventAt(notification.getLatestEventAt().toString())
        .unread(shareNotification.unreadAt(now))
        .expiresAt(share.getExpiresAt() == null ? null : share.getExpiresAt().toString())
        .resolution(shareNotification.resolutionAt(now).orElse(null))
        .answer(notification.getFacts().answer().orElse(null))
        .endedBy(notification.getFacts().endedBy().orElse(null))
        .share(ProfileShareDetails.from(share, now))
        .keptProfileName(notification.getProfileName())
        .build();
  }
}
