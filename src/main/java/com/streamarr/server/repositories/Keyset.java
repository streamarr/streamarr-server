package com.streamarr.server.repositories;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.rowNumber;
import static org.jooq.impl.DSL.val;

import com.streamarr.server.services.pagination.AbsentValuePlacement;
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
import org.jooq.Comparator;
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
    return select
        .where(scope)
        .and(beyond(seek))
        .orderBy(readingOrder(seek.order(), seek.direction()))
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
    var rank =
        rowNumber()
            .over()
            .partitionBy(scope)
            .orderBy(readingOrder(shape.order(), shape.direction()))
            .as(SCOPE_RANK);
    var beyondEach =
        seeks.entrySet().stream()
            .map(entry -> scope.eq(entry.getKey()).and(beyond(entry.getValue())))
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
  }

  public Row<UUID> rowOf(Record fetched) {
    var id = fetched.get(itemId);
    return new Row<>(id, new Position(SortValue.of(fetched.get(sortValue)), id));
  }

  private List<SortField<?>> readingOrder(ListOrder order, PaginationDirection direction) {
    var ascending = isAscending(order, direction);
    if (order.absentValues() == AbsentValuePlacement.NOT_ALLOWED) {
      return List.of(sort(sortValue, ascending), sort(itemId, ascending));
    }

    return List.of(
        sort(placement(order, direction), ascending),
        sort(sortValue, ascending),
        sort(itemId, ascending));
  }

  private Condition beyond(Seek seek) {
    if (seek.position().isEmpty()) {
      return noCondition();
    }

    var position = seek.position().orElseThrow();
    if (seek.order().absentValues() == AbsentValuePlacement.NOT_ALLOWED) {
      return row(List.of(sortValue, itemId))
          .compare(beyondComparator(seek), row(List.of(boundValue(position), boundId(position))));
    }

    if (position.value() instanceof SortValue.Absent) {
      return beyondAnAbsentValue(position, seek);
    }

    return beyondAPresentValue(position, seek);
  }

  // Absent values sit together at one end of the list, ordered among themselves by item ID.
  private Condition beyondAnAbsentValue(Position position, Seek seek) {
    var amongAbsent =
        sortValue.isNull().and(itemId.compare(beyondComparator(seek), boundId(position)));
    if (isAbsentAscending(seek.order(), seek.direction())) {
      return amongAbsent;
    }

    return sortValue.isNotNull().or(amongAbsent);
  }

  private Condition beyondAPresentValue(Position position, Seek seek) {
    var presentPlacement = inline(!isFlaggingAbsence(seek.order(), seek.direction()));
    return row(List.of(placement(seek.order(), seek.direction()), sortValue, itemId))
        .compare(
            beyondComparator(seek),
            row(List.of(presentPlacement, boundValue(position), boundId(position))));
  }

  // A number binds as bigint whatever the column's width: coerced to an integer column it would
  // wrap, so a position beyond the column's range would seek from the wrong end. PostgreSQL
  // compares integer with bigint exactly, and an index on the column still serves the seek.
  private Field<?> boundValue(Position position) {
    if (position.value() instanceof SortValue.OfNumber(var number)) {
      return val(number, SQLDataType.BIGINT);
    }

    return val(bindValue(position.value()), sortValue);
  }

  private Field<UUID> boundId(Position position) {
    return val(position.itemId(), itemId);
  }

  // Absent values sit together at one end of the list. The flag placing them sorts in the value's
  // direction, so rows beyond a present value are one comparison over (flag, value, id), which an
  // index on those three expressions serves.
  private Field<Boolean> placement(ListOrder order, PaginationDirection direction) {
    if (isFlaggingAbsence(order, direction)) {
      return field(sortValue.isNull());
    }

    return field(sortValue.isNotNull());
  }

  private static boolean isFlaggingAbsence(ListOrder order, PaginationDirection direction) {
    return isAbsentAscending(order, direction) == isAscending(order, direction);
  }

  // Rows beyond a position compare greater in ascending reading order and less in descending.
  private static Comparator beyondComparator(Seek seek) {
    if (isAscending(seek)) {
      return seek.includingPosition() ? Comparator.GREATER_OR_EQUAL : Comparator.GREATER;
    }

    return seek.includingPosition() ? Comparator.LESS_OR_EQUAL : Comparator.LESS;
  }

  // Absent values sort as the largest values, or after every present value in list order.
  private static boolean isAbsentAscending(ListOrder order, PaginationDirection direction) {
    return switch (order.absentValues()) {
      case NOT_ALLOWED, AS_LARGEST -> isAscending(order, direction);
      case LAST -> direction == PaginationDirection.FORWARD;
    };
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
