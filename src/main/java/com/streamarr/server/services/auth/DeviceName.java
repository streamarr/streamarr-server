package com.streamarr.server.services.auth;

import java.text.Normalizer;

/**
 * Sanitizes the one free-text field a pre-auth caller controls. Unicode names survive intact —
 * "Salón TV" is a real device name — because output encoding, not an input whitelist, is the
 * defence against injection on the page that renders it. What is stripped is what a name can never
 * legitimately contain: control characters and line breaks that would forge log lines.
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
            .filter(codePoint -> !Character.isISOControl(codePoint))
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString()
            .strip();

    if (normalized.isEmpty()) {
      return FALLBACK;
    }

    return truncateToScalars(normalized);
  }

  /** Truncates by code point so a multi-scalar character is never split into invalid halves. */
  private static String truncateToScalars(String name) {
    if (name.codePointCount(0, name.length()) <= MAX_SCALARS) {
      return name;
    }
    return name.substring(0, name.offsetByCodePoints(0, MAX_SCALARS));
  }
}
