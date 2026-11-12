package com.streamarr.server.domain.notification;

import lombok.Builder;
import lombok.NonNull;

/**
 * A sharing Notification's line in English (ADR 0063 §What a Notification carries), for a client
 * that shows the type but handles it no further. It names the Profile alone, by the name its
 * recipient was told, and words the rest from the facts the Notification keeps, so it reads the
 * same however the share, the Profile or the Households change afterwards.
 *
 * <p>The line is plain text. The Profile's name is chosen by a person, so it stands in quotation
 * marks without its control and format characters, and cannot pass for the line's own words or
 * reorder them.
 */
@Builder
public record ProfileShareLine(
    @NonNull NotificationKind kind,
    @NonNull String profileName,
    ProfileShareAnswer answer,
    ProfileShareEndedBy endedBy) {

  public String text() {
    var profile = quoted(profileName);
    return switch (kind) {
      case PROFILE_SHARE_OFFERED -> "%s was offered to your Household.".formatted(profile);
      case PROFILE_SHARE_ANSWERED -> answered(profile);
      case PROFILE_SHARE_ENDED -> ended(profile);
    };
  }

  private String answered(String profile) {
    var verb =
        switch (answer) {
          case ACCEPTED -> "accepted";
          case REJECTED -> "declined";
        };
    return "The offer of %s was %s.".formatted(profile, verb);
  }

  private String ended(String profile) {
    return switch (endedBy) {
      case HOUSEHOLD_SIDE -> "A Household stopped sharing %s.".formatted(profile);
      case OFFERING_SIDE -> "%s is no longer shared with your Household.".formatted(profile);
      case SERVER -> "The share of %s was ended.".formatted(profile);
    };
  }

  private static String quoted(String name) {
    var printable = new StringBuilder();
    name.codePoints()
        .filter(
            codePoint ->
                switch (Character.getType(codePoint)) {
                  case Character.CONTROL, Character.FORMAT -> false;
                  default -> true;
                })
        .forEach(printable::appendCodePoint);
    return "“" + printable + "”";
  }
}
