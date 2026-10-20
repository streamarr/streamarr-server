package com.streamarr.server.services.pagination;

import lombok.Builder;
import lombok.NonNull;

/**
 * How a list orders its rows: by one sort value, then by item ID, both in one direction. Items
 * without a value sit where {@code absentValues} places them.
 */
@Builder
public record ListOrder(
    @NonNull SortValueKind valueKind,
    @NonNull SortDirection direction,
    @NonNull AbsentValuePlacement absentValues) {

  public static class ListOrderBuilder {
    private AbsentValuePlacement absentValues = AbsentValuePlacement.NOT_ALLOWED;
  }

  /** Whether a row of this list can sort by {@code value}. */
  public boolean admits(SortValue value) {
    return switch (value) {
      case SortValue.OfText _ -> valueKind == SortValueKind.TEXT;
      case SortValue.OfInstant _ -> valueKind == SortValueKind.INSTANT;
      case SortValue.OfDate _ -> valueKind == SortValueKind.DATE;
      case SortValue.OfNumber _ -> valueKind == SortValueKind.NUMBER;
      case SortValue.Absent _ -> absentValues != AbsentValuePlacement.NOT_ALLOWED;
    };
  }
}
