package com.streamarr.server.fakes;

import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Reads rows beyond a seek's position the way {@code repositories.Keyset} does in PostgreSQL. */
public final class InMemoryKeyset {

  // PostgreSQL orders uuid values byte by byte, which matches their text order.
  private static final Comparator<Position> ASCENDING =
      Comparator.comparing(Position::value, InMemoryKeyset::compareValues)
          .thenComparing(Position::itemId, Comparator.comparing(UUID::toString));

  private InMemoryKeyset() {}

  public static <T> List<Row<T>> rowsBeyond(Collection<Row<T>> rows, Seek seek) {
    var order = isAscending(seek) ? ASCENDING : ASCENDING.reversed();
    return rows.stream()
        .filter(row -> isBeyond(row.position(), seek, order))
        .sorted(Comparator.comparing(Row::position, order))
        .limit(seek.limit())
        .toList();
  }

  private static boolean isBeyond(Position candidate, Seek seek, Comparator<Position> order) {
    if (seek.position().isEmpty()) {
      return true;
    }

    var comparison = order.compare(candidate, seek.position().orElseThrow());
    return comparison > 0 || (comparison == 0 && seek.includingPosition());
  }

  private static boolean isAscending(Seek seek) {
    var forward = seek.direction() == PaginationDirection.FORWARD;
    return forward == (seek.order().direction() == SortDirection.ASC);
  }

  private static int compareValues(SortValue left, SortValue right) {
    return switch (left) {
      case SortValue.OfText(var text) -> text.compareTo(((SortValue.OfText) right).value());
      case SortValue.OfInstant(var instant) ->
          instant.compareTo(((SortValue.OfInstant) right).value());
      case SortValue.OfDate(var date) -> date.compareTo(((SortValue.OfDate) right).value());
      case SortValue.OfNumber(var number) ->
          Long.compare(number, ((SortValue.OfNumber) right).value());
      case SortValue.Absent _ ->
          throw new IllegalArgumentException("A keyset list orders only present values.");
    };
  }
}
