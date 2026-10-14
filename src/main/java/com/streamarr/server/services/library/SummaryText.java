package com.streamarr.server.services.library;

final class SummaryText {

  private SummaryText() {}

  static String counted(long count, String singular, String plural) {
    if (count == 1) {
      return "1 " + singular;
    }

    return count + " " + plural;
  }
}
