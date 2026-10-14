package com.streamarr.server.services.metadata;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import lombok.NonNull;

/** Reads an HTTP {@code Retry-After} header, which holds either delay seconds or an HTTP date. */
final class RetryAfter {

  private RetryAfter() {}

  static Optional<Duration> parse(@NonNull Optional<String> header, @NonNull Instant now) {
    return header.map(String::trim).flatMap(value -> parseValue(value, now));
  }

  private static Optional<Duration> parseValue(String value, Instant now) {
    if (isDelaySeconds(value)) {
      return parseDelaySeconds(value);
    }

    return parseDate(value, now);
  }

  private static boolean isDelaySeconds(String value) {
    return !value.isEmpty() && value.chars().allMatch(Character::isDigit);
  }

  private static Optional<Duration> parseDelaySeconds(String value) {
    try {
      return Optional.of(Duration.ofSeconds(Long.parseLong(value)));
    } catch (NumberFormatException _) {
      return Optional.empty();
    }
  }

  private static Optional<Duration> parseDate(String value, Instant now) {
    try {
      var until = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      if (until.isBefore(now)) {
        return Optional.of(Duration.ZERO);
      }

      return Optional.of(Duration.between(now, until));
    } catch (DateTimeParseException _) {
      return Optional.empty();
    }
  }
}
