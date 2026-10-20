package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.values;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.services.pagination.ListOrder;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
import com.streamarr.server.services.pagination.SortValueKind;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Keyset reads")
class KeysetIT extends AbstractIntegrationTest {

  private static final UUID LOW_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID MIDDLE_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
  private static final UUID HIGH_ID = UUID.fromString("00000000-0000-4000-8000-000000000003");

  @Autowired private DSLContext dsl;

  /** Three values of one kind, lowest first, in the Java type PostgreSQL returns for them. */
  private record Values<V>(SortValueKind kind, Class<V> type, V low, V middle, V high) {}

  static Stream<Arguments> seeks() {
    var kinds =
        List.<Values<?>>of(
            new Values<>(SortValueKind.TEXT, String.class, "alpha", "bravo", "charlie"),
            new Values<>(
                SortValueKind.INSTANT,
                OffsetDateTime.class,
                OffsetDateTime.parse("1969-12-31T23:59:59.5Z"),
                OffsetDateTime.parse("1970-01-01T00:00:00.5Z"),
                OffsetDateTime.parse("2026-10-20T12:00:00Z")),
            new Values<>(
                SortValueKind.DATE,
                LocalDate.class,
                LocalDate.of(2011, 12, 29),
                LocalDate.of(2011, 12, 30),
                LocalDate.of(2011, 12, 31)),
            new Values<>(SortValueKind.NUMBER, Integer.class, 89, 90, 91));
    return kinds.stream()
        .flatMap(
            values ->
                Stream.of(
                    Arguments.of(values, SortDirection.ASC, PaginationDirection.FORWARD, HIGH_ID),
                    Arguments.of(values, SortDirection.ASC, PaginationDirection.REVERSE, LOW_ID),
                    Arguments.of(values, SortDirection.DESC, PaginationDirection.FORWARD, LOW_ID),
                    Arguments.of(
                        values, SortDirection.DESC, PaginationDirection.REVERSE, HIGH_ID)));
  }

  @ParameterizedTest(name = "{0} {1} {2}")
  @MethodSource("seeks")
  @DisplayName("Should read only the row beyond the middle when a seek starts at the middle row")
  <V> void shouldReadOnlyTheRowBeyondTheMiddleWhenASeekStartsAtTheMiddleRow(
      Values<V> values, SortDirection order, PaginationDirection direction, UUID expectedId) {
    var rows = read(values, seek(values, order, direction, false));

    assertThat(rows).extracting(Row::item).containsExactly(expectedId);
  }

  @ParameterizedTest(name = "{0} {1} {2}")
  @MethodSource("seeks")
  @DisplayName("Should read the middle row first when a seek includes its position")
  <V> void shouldReadTheMiddleRowFirstWhenASeekIncludesItsPosition(
      Values<V> values, SortDirection order, PaginationDirection direction, UUID expectedId) {
    var rows = read(values, seek(values, order, direction, true));

    assertThat(rows).extracting(Row::item).containsExactly(MIDDLE_ID, expectedId);
    assertThat(rows.getFirst().position())
        .isEqualTo(new Position(SortValue.of(values.middle()), MIDDLE_ID));
  }

  @Test
  @DisplayName("Should refuse to seek when the position has no value")
  void shouldRefuseToSeekWhenThePositionHasNoValue() {
    var values = new Values<>(SortValueKind.NUMBER, Integer.class, 89, 90, 91);
    var seek =
        Seek.builder()
            .order(order(values.kind(), SortDirection.ASC))
            .position(Optional.of(new Position(new SortValue.Absent(), MIDDLE_ID)))
            .direction(PaginationDirection.FORWARD)
            .limit(3)
            .build();

    assertThatThrownBy(() -> read(values, seek)).isInstanceOf(IllegalStateException.class);
  }

  private <V> List<Row<UUID>> read(Values<V> values, Seek seek) {
    var table =
        values(
                row(values.low(), LOW_ID),
                row(values.middle(), MIDDLE_ID),
                row(values.high(), HIGH_ID))
            .as("listed", "sort_value", "item_id");
    var keyset =
        Keyset.<V>builder()
            .sortValue(table.field("sort_value", values.type()))
            .itemId(table.field("item_id", UUID.class))
            .build();
    return keyset
        .rowsBeyond(dsl.select(keyset.sortValue(), keyset.itemId()).from(table), seek)
        .fetch(keyset::rowOf);
  }

  private static Seek seek(
      Values<?> values, SortDirection order, PaginationDirection direction, boolean including) {
    return Seek.builder()
        .order(order(values.kind(), order))
        .position(Optional.of(new Position(SortValue.of(values.middle()), MIDDLE_ID)))
        .direction(direction)
        .limit(3)
        .includingPosition(including)
        .build();
  }

  private static ListOrder order(SortValueKind kind, SortDirection direction) {
    return ListOrder.builder().valueKind(kind).direction(direction).build();
  }
}
