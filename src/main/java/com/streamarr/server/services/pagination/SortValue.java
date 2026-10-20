package com.streamarr.server.services.pagination;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.NonNull;

/** The value a list sorts an item by, typed so cursors and seeks never parse it again. */
public sealed interface SortValue
    permits SortValue.OfText,
        SortValue.OfInstant,
        SortValue.OfDate,
        SortValue.OfNumber,
        SortValue.Absent {

  record OfText(@NonNull String value) implements SortValue {}

  record OfInstant(@NonNull Instant value) implements SortValue {}

  record OfDate(@NonNull LocalDate value) implements SortValue {}

  record OfNumber(long value) implements SortValue {}

  /** The item has no value for a nullable sort. */
  record Absent() implements SortValue {}

  /**
   * Reads an instant a client sent back. Upper bounds are PostgreSQL's timestamptz limit. Entities
   * read dates through java.sql types, which use the Julian calendar before the 1582 Gregorian
   * cutover, so earlier values cannot round-trip; no stored value is that early.
   *
   * @throws IllegalArgumentException when the text is not an instant inside that range
   */
  static Instant readInstant(String text) {
    var instant = Instant.parse(text);
    if (instant.isBefore(Bounds.EARLIEST_INSTANT) || instant.isAfter(Bounds.LATEST_INSTANT)) {
      throw new IllegalArgumentException("A time sort value lies outside the stored range.");
    }

    return instant;
  }

  /**
   * Reads a date a client sent back, from 1583 through PostgreSQL's date limit.
   *
   * @throws IllegalArgumentException when the text is not a date inside that range
   */
  static LocalDate readDate(String text) {
    var date = LocalDate.parse(text);
    if (date.isBefore(Bounds.EARLIEST_DATE) || date.isAfter(Bounds.LATEST_DATE)) {
      throw new IllegalArgumentException("A date sort value lies outside the stored range.");
    }

    return date;
  }

  /** Wraps a value read from the database or an entity. */
  static SortValue of(Object value) {
    return switch (value) {
      case null -> new Absent();
      case String text -> new OfText(text);
      case Instant instant -> new OfInstant(instant);
      case OffsetDateTime time -> new OfInstant(time.toInstant());
      case LocalDate date -> new OfDate(date);
      case Integer number -> new OfNumber(number);
      case Long number -> new OfNumber(number);
      default ->
          throw new IllegalArgumentException(
              "Unsupported sort value type: " + value.getClass().getName());
    };
  }

  final class Bounds {
    private static final LocalDate EARLIEST_DATE = LocalDate.of(1583, 1, 1);
    private static final LocalDate LATEST_DATE = LocalDate.of(5874897, 12, 31);
    private static final Instant EARLIEST_INSTANT = Instant.parse("1583-01-01T00:00:00Z");
    private static final Instant LATEST_INSTANT = Instant.parse("+294276-12-31T23:59:59.999999Z");

    private Bounds() {}
  }
}
