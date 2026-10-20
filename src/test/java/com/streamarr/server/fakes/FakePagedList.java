package com.streamarr.server.fakes;

import com.streamarr.server.services.pagination.ListOrder;
import com.streamarr.server.services.pagination.PagedList;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
import com.streamarr.server.services.pagination.SortValueKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** A list of named items ordered by a number, then by ID, held in memory. */
public final class FakePagedList implements PagedList<String, String> {

  private static final Comparator<Position> ORDER =
      Comparator.comparingLong((Position position) -> number(position.value()))
          .thenComparing(Position::itemId);

  private final List<Row<String>> rows = new ArrayList<>();
  private int rowsRead;

  /** Adds an item sorted by {@code number}; equal numbers fall back to item ID order. */
  public Row<String> add(String item, long number) {
    var row = new Row<>(item, new Position(new SortValue.OfNumber(number), UUID.randomUUID()));
    rows.add(row);
    rows.sort(Comparator.comparing(Row::position, ORDER));
    return row;
  }

  public void remove(Row<String> row) {
    rows.remove(row);
  }

  /** How many rows every read so far has returned, as a database would load them. */
  public int rowsRead() {
    return rowsRead;
  }

  @Override
  public ListOrder order(String scope) {
    return ListOrder.builder().valueKind(SortValueKind.NUMBER).direction(SortDirection.ASC).build();
  }

  @Override
  public List<Row<String>> rowsBeyond(String scope, Seek seek) {
    var forward = seek.direction() == PaginationDirection.FORWARD;
    var ordered = new ArrayList<>(rows);
    if (!forward) {
      ordered = new ArrayList<>(ordered.reversed());
    }

    var read =
        ordered.stream()
            .filter(row -> isBeyond(row.position(), seek, forward))
            .limit(seek.limit())
            .toList();
    rowsRead += read.size();
    return read;
  }

  private static boolean isBeyond(Position candidate, Seek seek, boolean forward) {
    if (seek.position().isEmpty()) {
      return true;
    }

    var comparison = ORDER.compare(candidate, seek.position().get());
    if (comparison == 0) {
      return seek.includingPosition();
    }

    return forward == comparison > 0;
  }

  private static long number(SortValue value) {
    return ((SortValue.OfNumber) value).value();
  }
}
