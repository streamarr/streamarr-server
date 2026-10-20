package com.streamarr.server.services.pagination;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record MediaOrder(
    @NonNull OrderMediaBy by,
    @NonNull SortDirection direction,
    @NonNull UUID itemId,
    Object value) {
  public static MediaOrder from(MediaFilter filter, UUID itemId, Object value) {
    return builder()
        .by(filter.getSortBy())
        .direction(filter.getSortDirection())
        .itemId(itemId)
        .value(value)
        .build();
  }

  /** The order of a list position, for a list sorted {@code by} in {@code direction}. */
  public static MediaOrder at(OrderMediaBy by, SortDirection direction, Position position) {
    return builder()
        .by(by)
        .direction(direction)
        .itemId(position.itemId())
        .value(valueOf(position.value()))
        .build();
  }

  private static Object valueOf(SortValue value) {
    return switch (value) {
      case SortValue.OfText(var text) -> text;
      case SortValue.OfInstant(var instant) -> instant;
      case SortValue.OfDate(var date) -> date;
      case SortValue.OfNumber(var number) -> number;
      case SortValue.Absent _ -> null;
    };
  }
}
