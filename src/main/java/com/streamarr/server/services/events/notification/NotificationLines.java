package com.streamarr.server.services.events.notification;

import com.streamarr.server.domain.notification.ProfileShareAnswer;

/** The words the Notifications' lines share. */
final class NotificationLines {

  private NotificationLines() {}

  /**
   * A name a person chose, in quotation marks and without its control and format characters, so it
   * cannot pass for the line's own words or reorder them.
   */
  static String quoted(String name) {
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

  /** How an offer's answer reads; a Household declines an offer it rejects. */
  static String answered(ProfileShareAnswer answer) {
    return switch (answer) {
      case ACCEPTED -> "accepted";
      case REJECTED -> "declined";
    };
  }
}
