package com.streamarr.server.services.auth;

import com.streamarr.server.exceptions.InvalidUserCodeException;
import java.util.Locale;

/**
 * The display handle a person reads off a TV and types on the web. Streamarr adopts the
 * twenty-consonant alphabet from RFC 8628 §6.1's non-normative usability example: no vowels, so no
 * code spells a word, and no {@code 0}/{@code O} or {@code 1}/{@code I} to misread across a room.
 *
 * <p>Two forms exist and must not be confused: the grouped display form a client shows ({@code
 * BCDF-GHJK}), and the normalized wire and storage form ({@code BCDFGHJK}).
 */
public final class UserCode {

  static final String ALPHABET = "BCDFGHJKLMNPQRSTVWXZ";
  static final int LENGTH = 8;

  private static final int GROUP_SIZE = 4;

  private UserCode() {}

  /**
   * Accepts what a person actually types — any case, with or without separators or surrounding
   * whitespace — and returns the single stored form, or rejects it.
   */
  public static String normalize(String typed) {
    if (typed == null) {
      throw new InvalidUserCodeException();
    }

    var normalized = typed.replace("-", "").replace(" ", "").strip().toUpperCase(Locale.ROOT);
    requireValid(normalized);

    return normalized;
  }

  /** Groups a normalized code for display: {@code BCDFGHJK} becomes {@code BCDF-GHJK}. */
  public static String forDisplay(String normalized) {
    var groups = new StringBuilder();
    for (var start = 0; start < normalized.length(); start += GROUP_SIZE) {
      if (start > 0) {
        groups.append('-');
      }
      groups.append(normalized, start, Math.min(start + GROUP_SIZE, normalized.length()));
    }
    return groups.toString();
  }

  private static void requireValid(String normalized) {
    if (normalized.length() != LENGTH) {
      throw new InvalidUserCodeException();
    }
    if (!normalized.chars().allMatch(character -> ALPHABET.indexOf(character) >= 0)) {
      throw new InvalidUserCodeException();
    }
  }
}
