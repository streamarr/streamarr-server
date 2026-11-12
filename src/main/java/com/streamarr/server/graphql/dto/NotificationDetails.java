package com.streamarr.server.graphql.dto;

import com.streamarr.server.services.events.notification.NotificationKind;
import com.streamarr.server.services.events.notification.ProfileShareAnswer;
import com.streamarr.server.services.events.notification.ProfileShareAnsweredSnapshot;
import com.streamarr.server.services.events.notification.ProfileShareEndedBy;
import com.streamarr.server.services.events.notification.ProfileShareEndedSnapshot;
import com.streamarr.server.services.events.notification.ProfileShareOfferedSnapshot;
import com.streamarr.server.services.events.notification.RequestResolution;
import com.streamarr.server.services.events.notification.SubjectReference;
import com.streamarr.server.services.notification.StoredNotification;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/**
 * A Notification as the schema shows it, read at one instant from what its recipient was told and
 * its stored state. {@code kind} picks the concrete type; the other fields are the type's.
 */
@Builder
public record NotificationDetails(
    UUID id,
    NotificationKind kind,
    String message,
    String createdAt,
    String latestEventAt,
    boolean unread,
    String expiresAt,
    RequestResolution resolution,
    String resolvedAt,
    UUID shareId,
    String profileName,
    ProfileShareAnswer answer,
    ProfileShareEndedBy endedBy) {

  public static NotificationDetails from(StoredNotification notification, Instant now) {
    var details =
        NotificationDetails.builder()
            .id(notification.id())
            .kind(notification.kind())
            .message(notification.lineAt(now))
            .createdAt(notification.createdAt().toString())
            .latestEventAt(notification.latestEventAt().toString())
            .unread(notification.unreadAt(now))
            .expiresAt(notification.expiresAt().map(Instant::toString).orElse(null))
            .resolution(notification.resolutionAt(now).orElse(null))
            .resolvedAt(notification.resolutionTimeAt(now).map(Instant::toString).orElse(null))
            .shareId(shareIdOf(notification.subject()));
    return switch (notification.snapshot()) {
      case ProfileShareOfferedSnapshot(var profileName, var answer) ->
          details.profileName(profileName).answer(answer.orElse(null)).build();
      case ProfileShareAnsweredSnapshot(var profileName, var answer) ->
          details.profileName(profileName).answer(answer).build();
      case ProfileShareEndedSnapshot(var profileName, var endedBy) ->
          details.profileName(profileName).endedBy(endedBy).build();
      default -> details.build();
    };
  }

  private static UUID shareIdOf(SubjectReference subject) {
    if (!SubjectReference.PROFILE_SHARE.equals(subject.type())) {
      return null;
    }

    return subject.id();
  }
}
