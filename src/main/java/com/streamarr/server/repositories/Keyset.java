package com.streamarr.server.repositories;

import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.rowNumber;
import static org.jooq.impl.DSL.val;

import com.streamarr.server.services.pagination.ListOrder;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Select;
import org.jooq.SelectJoinStep;
import org.jooq.SelectWhereStep;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.impl.SQLDataType;

/** The columns a list sorts by, and the query that reads its rows beyond a position. */
@Builder
public record Keyset<V>(@NonNull Field<V> sortValue, @NonNull Field<UUID> itemId) {

  /** The name a query gives the sort value it selects beside an entity's columns. */
  public static final String SELECTED_SORT_VALUE = "keyset_sort_value";

  private static final String SCOPE_RANK = "keyset_scope_rank";

  /**
   * The sort value, to select beside an entity's columns for {@code JooqQueryHelper.nativeRows}.
   */
  public Field<V> selectedSortValue() {
    return sortValue.as(SELECTED_SORT_VALUE);
  }

  /** Every column of {@code table} and the sort value, for {@code JooqQueryHelper.nativeRows}. */
  public SelectJoinStep<Record> selectFrom(DSLContext dsl, Table<?> table) {
    return dsl.select(table.asterisk(), selectedSortValue()).from(table);
  }

  /** Restricts, orders and limits {@code select} to the rows the seek reads, nearest first. */
  public <R extends Record> Select<R> rowsBeyond(SelectWhereStep<R> select, Seek seek) {
    return rowsBeyond(select, noCondition(), seek);
  }

  /** As {@link #rowsBeyond(SelectWhereStep, Seek)}, among the rows {@code scope} admits. */
  public <R extends Record> Select<R> rowsBeyond(
      SelectWhereStep<R> select, Condition scope, Seek seek) {
    var ascending = isAscending(seek);
    return select
        .where(scope)
        .and(beyond(seek, ascending))
        .orderBy(sort(sortValue, ascending), sort(itemId, ascending))
        .limit(seek.limit());
  }

  /**
   * Reads the rows beyond each scope's seek from {@code table}, whose {@code scope} column names
   * the scope a row belongs to. Seeks that differ only in position share one query, which ranks
   * each scope's rows apart; each query returns rows grouped by scope, nearest first.
   */
  public <K> List<Select<Record>> rowsBeyondEach(
      DSLContext dsl, Table<?> table, Field<K> scope, Map<K, Seek> seeks) {
    var byShape = new LinkedHashMap<SeekShape, Map<K, Seek>>();
    seeks.forEach(
        (key, seek) ->
            byShape.computeIfAbsent(SeekShape.of(seek), _ -> new LinkedHashMap<>()).put(key, seek));
    return byShape.entrySet().stream()
        .map(group -> rankedRowsBeyond(dsl, table, scope, group.getKey(), group.getValue()))
        .toList();
  }

  private <K> Select<Record> rankedRowsBeyond(
      DSLContext dsl, Table<?> table, Field<K> scope, SeekShape shape, Map<K, Seek> seeks) {
    var ascending = shape.isAscending();
    var rank =
        rowNumber()
            .over()
            .partitionBy(scope)
            .orderBy(sort(sortValue, ascending), sort(itemId, ascending))
            .as(SCOPE_RANK);
    var beyondEach =
        seeks.entrySet().stream()
            .map(entry -> scope.eq(entry.getKey()).and(beyond(entry.getValue(), ascending)))
            .reduce(noCondition(), Condition::or);
    var ranked =
        dsl.select(table.asterisk(), selectedSortValue(), rank)
            .from(table)
            .where(beyondEach)
            .asTable("keyset_ranked");
    return dsl.select(ranked.asterisk())
        .from(ranked)
        .where(ranked.field(rank).le(shape.limit()))
        .orderBy(ranked.field(scope), ranked.field(rank));
  }

  /** What a seek reads apart from its position: seeks of one shape can share a query. */
  private record SeekShape(
      ListOrder order, PaginationDirection direction, int limit, boolean includingPosition) {

    static SeekShape of(Seek seek) {
      return new SeekShape(seek.order(), seek.direction(), seek.limit(), seek.includingPosition());
    }

    boolean isAscending() {
      return Keyset.isAscending(order, direction);
    }
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
    return isAscending(seek.order(), seek.direction());
  }

  private static boolean isAscending(ListOrder order, PaginationDirection direction) {
    return (direction == PaginationDirection.FORWARD) == (order.direction() == SortDirection.ASC);
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
