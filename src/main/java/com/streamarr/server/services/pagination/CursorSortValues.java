package com.streamarr.server.services.pagination;

import java.time.Instant;
import java.time.LocalDate;

/** Reads the time and date sort values clients send back in cursors. */
public final class CursorSortValues {

  private static final LocalDate EARLIEST_DATE = LocalDate.of(1583, 1, 1);
  private static final LocalDate LATEST_DATE = LocalDate.of(5874897, 12, 31);
  private static final Instant EARLIEST_INSTANT = Instant.parse("1583-01-01T00:00:00Z");
  private static final Instant LATEST_INSTANT = Instant.parse("+294276-12-31T23:59:59.999999Z");
  private static final int NANOS_PER_MICROSECOND = 1_000;

  private CursorSortValues() {}

  /**
   * Reads an instant a client sent back. Upper bounds are PostgreSQL's timestamptz limit. Entities
   * read dates through java.sql types, which use the Julian calendar before the 1582 Gregorian
   * cutover, so earlier values cannot round-trip; no stored value is that early. PostgreSQL stores
   * microseconds and rounds finer values when binding them, which would move the position.
   *
   * @throws IllegalArgumentException when the text is not a stored-precision instant inside that
   *     range
   */
  public static Instant readInstant(String text) {
    var instant = Instant.parse(text);
    if (instant.isBefore(EARLIEST_INSTANT) || instant.isAfter(LATEST_INSTANT)) {
      throw new IllegalArgumentException("A time sort value lies outside the stored range.");
    }

    if (instant.getNano() % NANOS_PER_MICROSECOND != 0) {
      throw new IllegalArgumentException("A time sort value is finer than PostgreSQL stores.");
    }

    return instant;
  }

  /**
   * Reads a date a client sent back, from 1583 through PostgreSQL's date limit.
   *
   * @throws IllegalArgumentException when the text is not a date inside that range
   */
  public static LocalDate readDate(String text) {
    var date = LocalDate.parse(text);
    if (date.isBefore(EARLIEST_DATE) || date.isAfter(LATEST_DATE)) {
      throw new IllegalArgumentException("A date sort value lies outside the stored range.");
    }

    return date;
  }
}
