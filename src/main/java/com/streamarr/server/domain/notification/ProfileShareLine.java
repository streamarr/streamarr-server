package com.streamarr.server.domain.notification;

import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * A sharing Notification's line in English (ADR 0063 §What a Notification carries), for a client
 * that shows the type but handles it no further. Each name is one its reader may read when the
 * server reads it: a name left empty is one the reader may not read, and the line leaves it out.
 */
@Builder
public record ProfileShareLine(
    @NonNull NotificationKind kind,
    @NonNull String profileName,
    @NonNull Optional<String> offererName,
    @NonNull Optional<String> householdName,
    ProfileShareAnswer answer,
    ProfileShareEndedBy endedBy) {

  public String text() {
    var profile = "the Profile " + profileName;
    return switch (kind) {
      case PROFILE_SHARE_OFFERED ->
          offererName
              .map(offerer -> "%s offered %s to your Household.".formatted(offerer, profile))
              .orElseGet(() -> "%s was offered to your Household.".formatted(capitalized(profile)));
      case PROFILE_SHARE_ANSWERED -> answered(profile);
      case PROFILE_SHARE_ENDED -> ended(profile);
    };
  }

  private String answered(String profile) {
    var verb =
        switch (answer) {
          case ACCEPTED -> "accepted";
          case REJECTED -> "rejected";
        };
    return householdName
        .map(household -> "%s %s %s.".formatted(household, verb, profile))
        .orElseGet(() -> "The offer of %s was %s.".formatted(profile, verb));
  }

  private String ended(String profile) {
    return switch (endedBy) {
      case HOUSEHOLD_SIDE ->
          "%s stopped sharing %s.".formatted(householdName.orElse("A Household"), profile);
      case OFFERING_SIDE, SERVER ->
          "%s is no longer shared with %s."
              .formatted(capitalized(profile), householdName.orElse("your Household"));
    };
  }

  private static String capitalized(String text) {
    return Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }
}
