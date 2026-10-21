package com.streamarr.server.services.pagination;

import java.time.Instant;
import java.time.LocalDate;

public enum OrderMediaBy {
  TITLE,
  ADDED,
  RELEASE_DATE,
  RUNTIME,
  LAST_WATCHED;

  // Upper bounds are PostgreSQL's date and timestamptz limits. Entities read dates through
  // java.sql types, which use the Julian calendar before the 1582 Gregorian cutover, so earlier
  // values cannot round-trip; no stored value is that early.
  private static final LocalDate EARLIEST_DATE = LocalDate.of(1583, 1, 1);
  private static final LocalDate LATEST_DATE = LocalDate.of(5874897, 12, 31);
  private static final Instant EARLIEST_INSTANT = Instant.parse("1583-01-01T00:00:00Z");
  private static final Instant LATEST_INSTANT = Instant.parse("+294276-12-31T23:59:59.999999Z");

  /**
   * Reads a sort value carried by a cursor as this sort's own type: a String title, an Instant
   * time, a LocalDate release date or an Integer runtime. A null value stays null.
   *
   * @throws IllegalArgumentException when the value cannot be read as that type or lies outside the
   *     range the database stores
   */
  public Object readCursorValue(Object value) {
    if (value == null) {
      return null;
    }

    return switch (this) {
      case TITLE -> readTitle(value);
      case ADDED, LAST_WATCHED -> readInstant(value);
      case RELEASE_DATE -> readDate(value);
      case RUNTIME -> Integer.valueOf(value.toString());
    };
  }

  private static String readTitle(Object value) {
    if (value instanceof String title) {
      return title;
    }

    throw new IllegalArgumentException("A title sort value must be text.");
  }

  private static Instant readInstant(Object value) {
    var instant = Instant.parse(value.toString());
    if (instant.isBefore(EARLIEST_INSTANT) || instant.isAfter(LATEST_INSTANT)) {
      throw new IllegalArgumentException("A time sort value lies outside the stored range.");
    }

    return instant;
  }

  private static LocalDate readDate(Object value) {
    var date = LocalDate.parse(value.toString());
    if (date.isBefore(EARLIEST_DATE) || date.isAfter(LATEST_DATE)) {
      throw new IllegalArgumentException("A date sort value lies outside the stored range.");
    }

    return date;
  }
}
