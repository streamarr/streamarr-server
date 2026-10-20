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
}
