package com.streamarr.server.fakes;

import com.streamarr.server.domain.BaseAuditableEntity;
import com.streamarr.server.services.pagination.AbsentValuePlacement;
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

  private static final Comparator<Position> PRESENT_FIRST =
      Comparator.comparing(position -> position.value() instanceof SortValue.Absent);

  // PostgreSQL orders uuid values byte by byte, which matches their text order. Absent values
  // compare only with each other, so their order falls to item ID.
  private static final Comparator<Position> ASCENDING =
      Comparator.comparing(Position::value, InMemoryKeyset::compareValues)
          .thenComparing(Position::itemId, Comparator.comparing(UUID::toString));

  private InMemoryKeyset() {}

  /** The entities beyond the seek, ordered by creation time, then ID. */
  public static <E extends BaseAuditableEntity<?>> List<Row<E>> byCreationTime(
      Collection<E> entities, Seek seek) {
    return rowsBeyond(
        entities.stream()
            .map(
                entity ->
                    new Row<>(
                        entity,
                        new Position(
                            new SortValue.OfInstant(entity.getCreatedOn()), entity.getId())))
            .toList(),
        seek);
  }

  public static <T> List<Row<T>> rowsBeyond(Collection<Row<T>> rows, Seek seek) {
    var absence = isAbsentAscending(seek) ? PRESENT_FIRST : PRESENT_FIRST.reversed();
    var order = absence.thenComparing(isAscending(seek) ? ASCENDING : ASCENDING.reversed());
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

    if (seek.position().orElseThrow().value() instanceof SortValue.Absent
        && seek.order().absentValues() == AbsentValuePlacement.NOT_ALLOWED) {
      throw new IllegalStateException("A seek past an absent value has no keyset bound.");
    }

    var comparison = order.compare(candidate, seek.position().orElseThrow());
    return comparison > 0 || (comparison == 0 && seek.includingPosition());
  }

  private static boolean isAbsentAscending(Seek seek) {
    return switch (seek.order().absentValues()) {
      case NOT_ALLOWED, AS_LARGEST -> isAscending(seek);
      case LAST -> seek.direction() == PaginationDirection.FORWARD;
    };
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
      case SortValue.Absent _ -> 0;
    };
  }
}
