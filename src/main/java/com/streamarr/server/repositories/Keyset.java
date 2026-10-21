package com.streamarr.server.repositories;

import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.val;

import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.ResultQuery;
import org.jooq.SelectWhereStep;
import org.jooq.SortField;
import org.jooq.impl.SQLDataType;

/** The columns a list sorts by, and the query that reads its rows beyond a position. */
@Builder
public record Keyset<V>(@NonNull Field<V> sortValue, @NonNull Field<UUID> itemId) {

  /** Restricts, orders and limits {@code select} to the rows the seek reads, nearest first. */
  public <R extends Record> ResultQuery<R> rowsBeyond(SelectWhereStep<R> select, Seek seek) {
    var ascending = isAscending(seek);
    return select
        .where(beyond(seek, ascending))
        .orderBy(sort(sortValue, ascending), sort(itemId, ascending))
        .limit(seek.limit());
  }

  public Row<UUID> rowOf(Record fetched) {
    var id = fetched.get(itemId);
    return new Row<>(id, new Position(SortValue.of(fetched.get(sortValue)), id));
  }

  private Condition beyond(Seek seek, boolean ascending) {
    if (seek.position().isEmpty()) {
      return noCondition();
    }

    var position = seek.position().orElseThrow();
    var current = row(List.of(sortValue, itemId));
    var at = row(List.of(boundValue(position.value()), val(position.itemId(), itemId)));
    if (ascending) {
      return seek.includingPosition() ? current.ge(at) : current.gt(at);
    }

    return seek.includingPosition() ? current.le(at) : current.lt(at);
  }

  // A number binds as bigint whatever the column's width: coerced to an integer column it would
  // wrap, so a position beyond the column's range would seek from the wrong end. PostgreSQL
  // compares integer with bigint exactly, and an index on the column still serves the seek.
  private Field<?> boundValue(SortValue value) {
    if (value instanceof SortValue.OfNumber(var number)) {
      return val(number, SQLDataType.BIGINT);
    }

    return val(bindValue(value), sortValue);
  }

  private static boolean isAscending(Seek seek) {
    var forward = seek.direction() == PaginationDirection.FORWARD;
    return forward == (seek.order().direction() == SortDirection.ASC);
  }

  private static SortField<?> sort(Field<?> field, boolean ascending) {
    return ascending ? field.asc() : field.desc();
  }

  // jOOQ converts an Instant through java.sql.Timestamp and local time, which shifts instants
  // before the Gregorian cutover and in a repeated daylight-saving hour.
  private static Object bindValue(SortValue value) {
    return switch (value) {
      case SortValue.OfText(var text) -> text;
      case SortValue.OfInstant(var instant) -> instant.atOffset(ZoneOffset.UTC);
      case SortValue.OfDate(var date) -> date;
      case SortValue.OfNumber(var number) -> number;
      case SortValue.Absent _ ->
          throw new IllegalStateException("A seek past an absent value has no keyset bound.");
    };
  }
}
