package com.streamarr.server.services.auth;

import java.text.Normalizer;

/**
 * Sanitizes the one free-text field a pre-auth caller controls. Unicode names survive intact —
 * "Salón TV" is a real device name — because output encoding, not an input whitelist, is the
 * defence against injection on the page that renders it. What is stripped is what a name can never
 * legitimately contain: control characters that would forge log lines and bidi controls that would
 * visually reorder text after output encoding.
 */
public final class DeviceName {

  private static final String FALLBACK = "Unknown device";
  private static final int MAX_SCALARS = 64;

  private DeviceName() {}

  public static String sanitize(String rawDeviceName) {
    if (rawDeviceName == null) {
      return FALLBACK;
    }

    var normalized =
        Normalizer.normalize(rawDeviceName, Normalizer.Form.NFC)
            .codePoints()
            .filter(codePoint -> !Character.isISOControl(codePoint) && !isBidiControl(codePoint))
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString()
            .strip();

    if (normalized.isEmpty()) {
      return FALLBACK;
    }

    return truncateToScalars(normalized);
  }

  private static boolean isBidiControl(int codePoint) {
    return codePoint == 0x061C
        || codePoint == 0x200E
        || codePoint == 0x200F
        || (codePoint >= 0x202A && codePoint <= 0x202E)
        || (codePoint >= 0x2066 && codePoint <= 0x2069);
  }

  /** Truncates by code point so a multi-scalar character is never split into invalid halves. */
  private static String truncateToScalars(String name) {
    if (name.codePointCount(0, name.length()) <= MAX_SCALARS) {
      return name;
    }
    return name.substring(0, name.offsetByCodePoints(0, MAX_SCALARS));
  }
}
