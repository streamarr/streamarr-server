package com.streamarr.server.domain.notification;

import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * A sharing Notification's line in English (ADR 0063 §What a Notification carries), for a client
 * that shows the type but handles it no further. Each name is one its reader may read when the
 * server reads it: a name left empty is one the reader may not read, and the line leaves it out.
 *
 * <p>The line is plain text. Each name is chosen by a person, so it stands in quotation marks
 * without its control and format characters, and cannot pass for the line's own words or reorder
 * them. A Household the line cannot name is "your Household" only when it is the reader's own.
 */
@Builder(toBuilder = true)
public record ProfileShareLine(
    @NonNull NotificationKind kind,
    @NonNull String profileName,
    @NonNull Optional<String> offererName,
    @NonNull Optional<String> householdName,
    ProfileShareAnswer answer,
    ProfileShareEndedBy endedBy,
    boolean toReadersHousehold) {

  public String text() {
    var profile = "the Profile " + quoted(profileName);
    return switch (kind) {
      case PROFILE_SHARE_OFFERED ->
          offererName
              .map(
                  offerer ->
                      "%s offered %s to %s."
                          .formatted(quoted(offerer), profile, unnamedHousehold()))
              .orElseGet(
                  () ->
                      "%s was offered to %s.".formatted(capitalized(profile), unnamedHousehold()));
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
        .map(household -> "%s %s %s.".formatted(quoted(household), verb, profile))
        .orElseGet(() -> "The offer of %s was %s.".formatted(profile, verb));
  }

  private String ended(String profile) {
    return switch (endedBy) {
      case HOUSEHOLD_SIDE ->
          "%s stopped sharing %s."
              .formatted(
                  householdName.map(ProfileShareLine::quoted).orElse("A Household"), profile);
      case OFFERING_SIDE, SERVER ->
          "%s is no longer shared with %s."
              .formatted(
                  capitalized(profile),
                  householdName.map(ProfileShareLine::quoted).orElseGet(this::unnamedHousehold));
    };
  }

  private String unnamedHousehold() {
    return toReadersHousehold ? "your Household" : "a Household";
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

  private static String capitalized(String text) {
    return Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }
}
