package com.streamarr.server.services.auth;

import java.util.Arrays;

/**
 * The one shape rule for an email address, applied wherever one is stored or looked up: one local
 * part, one @, a domain of dot-separated non-empty labels, no whitespace — the shape, not
 * deliverability. A valid address is returned with surrounding whitespace removed and its case
 * kept; every lookup already ignores case.
 */
public final class EmailAddressValidator {

  private EmailAddressValidator() {}

  public static Result validate(String candidate) {
    if (candidate == null || candidate.isBlank()) {
      return new Blank();
    }

    var address = candidate.strip();
    if (!isPlausible(address)) {
      return new Malformed();
    }

    return new Valid(address);
  }

  /** Iterative checks, so a long domain costs no stack. */
  private static boolean isPlausible(String address) {
    var at = address.indexOf('@');
    if (at < 1 || at != address.lastIndexOf('@')) {
      return false;
    }

    if (address.chars().anyMatch(Character::isWhitespace)) {
      return false;
    }

    var labels = address.substring(at + 1).split("\\.", -1);
    return labels.length > 1 && Arrays.stream(labels).noneMatch(String::isEmpty);
  }

  public sealed interface Result permits Valid, Blank, Malformed {}

  /** The address with surrounding whitespace removed; case is preserved. */
  public record Valid(String address) implements Result {}

  public record Blank() implements Result {}

  public record Malformed() implements Result {}
}
