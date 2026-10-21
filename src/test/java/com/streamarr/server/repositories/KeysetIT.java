package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.values;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.fakes.InMemoryKeyset;
import com.streamarr.server.services.pagination.AbsentValuePlacement;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
  private static final UUID ABSENT_ID = UUID.fromString("00000000-0000-4000-8000-000000000004");
  private static final UUID OTHER_ABSENT_ID =
      UUID.fromString("00000000-0000-4000-8000-000000000005");

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

  static Stream<Arguments> positionsOutsideTheColumnRange() {
    var aboveEveryInteger = Integer.MAX_VALUE + 1L;
    var belowEveryInteger = Integer.MIN_VALUE - 1L;
    return Stream.of(
        Arguments.of(aboveEveryInteger, SortDirection.ASC, PaginationDirection.FORWARD, List.of()),
        Arguments.of(
            aboveEveryInteger,
            SortDirection.DESC,
            PaginationDirection.FORWARD,
            List.of(HIGH_ID, MIDDLE_ID, LOW_ID)),
        Arguments.of(
            belowEveryInteger,
            SortDirection.ASC,
            PaginationDirection.FORWARD,
            List.of(LOW_ID, MIDDLE_ID, HIGH_ID)),
        Arguments.of(
            belowEveryInteger, SortDirection.DESC, PaginationDirection.FORWARD, List.of()));
  }

  @ParameterizedTest(name = "{0} {1} {2}")
  @MethodSource("positionsOutsideTheColumnRange")
  @DisplayName(
      "Should seek past a number the column cannot hold when a position is outside its range")
  void shouldSeekPastANumberTheColumnCannotHoldWhenAPositionIsOutsideItsRange(
      long value, SortDirection order, PaginationDirection direction, List<UUID> expectedIds) {
    var values = new Values<>(SortValueKind.NUMBER, Integer.class, 89, 90, 91);
    var seek =
        Seek.builder()
            .order(order(values.kind(), order))
            .position(Optional.of(new Position(new SortValue.OfNumber(value), MIDDLE_ID)))
            .direction(direction)
            .limit(3)
            .build();

    assertThat(read(values, seek)).extracting(Row::item).containsExactlyElementsOf(expectedIds);
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

  /** A list order over two dates and two items without one, and the order it lists them in. */
  static Stream<Arguments> absentValueOrders() {
    return Stream.of(
        Arguments.of(
            AbsentValuePlacement.LAST,
            SortDirection.ASC,
            List.of(LOW_ID, HIGH_ID, ABSENT_ID, OTHER_ABSENT_ID)),
        Arguments.of(
            AbsentValuePlacement.LAST,
            SortDirection.DESC,
            List.of(HIGH_ID, LOW_ID, OTHER_ABSENT_ID, ABSENT_ID)),
        Arguments.of(
            AbsentValuePlacement.AS_LARGEST,
            SortDirection.ASC,
            List.of(LOW_ID, HIGH_ID, ABSENT_ID, OTHER_ABSENT_ID)),
        Arguments.of(
            AbsentValuePlacement.AS_LARGEST,
            SortDirection.DESC,
            List.of(OTHER_ABSENT_ID, ABSENT_ID, HIGH_ID, LOW_ID)));
  }

  @ParameterizedTest(name = "absent values {0}, {1}")
  @MethodSource("absentValueOrders")
  @DisplayName("Should read the rows beyond every position when some items have no sort value")
  void shouldReadTheRowsBeyondEveryPositionWhenSomeItemsHaveNoSortValue(
      AbsentValuePlacement placement, SortDirection direction, List<UUID> listed) {
    var order =
        ListOrder.builder()
            .valueKind(SortValueKind.DATE)
            .direction(direction)
            .absentValues(placement)
            .build();
    var positions =
        Map.of(
            LOW_ID, new Position(new SortValue.OfDate(LocalDate.of(2011, 12, 29)), LOW_ID),
            HIGH_ID, new Position(new SortValue.OfDate(LocalDate.of(2011, 12, 31)), HIGH_ID),
            ABSENT_ID, new Position(new SortValue.Absent(), ABSENT_ID),
            OTHER_ABSENT_ID, new Position(new SortValue.Absent(), OTHER_ABSENT_ID));

    assertThat(readDates(seekFrom(order, Optional.empty(), PaginationDirection.FORWARD, false)))
        .extracting(Row::item)
        .containsExactlyElementsOf(listed);
    for (var at : listed) {
      var index = listed.indexOf(at);
      for (var including : List.of(false, true)) {
        var forward = new ArrayList<>(listed.subList(including ? index : index + 1, listed.size()));
        var reverse = new ArrayList<>(listed.subList(0, including ? index + 1 : index).reversed());
        var position = Optional.of(positions.get(at));

        assertThat(readDates(seekFrom(order, position, PaginationDirection.FORWARD, including)))
            .as("forward from %s, including %s", at, including)
            .extracting(Row::item)
            .containsExactlyElementsOf(forward);
        assertThat(readDates(seekFrom(order, position, PaginationDirection.REVERSE, including)))
            .as("reverse from %s, including %s", at, including)
            .extracting(Row::item)
            .containsExactlyElementsOf(reverse);
      }
    }
  }

  // Reads the rows in PostgreSQL and checks the in-memory keyset the fakes use reads the same.
  private List<Row<UUID>> readDates(Seek seek) {
    var table =
        values(
                row(LocalDate.of(2011, 12, 29), LOW_ID),
                row(LocalDate.of(2011, 12, 31), HIGH_ID),
                row((LocalDate) null, ABSENT_ID),
                row((LocalDate) null, OTHER_ABSENT_ID))
            .as("listed", "sort_value", "item_id");
    var keyset =
        Keyset.<LocalDate>builder()
            .sortValue(table.field("sort_value", LocalDate.class))
            .itemId(table.field("item_id", UUID.class))
            .build();
    var rows =
        keyset
            .rowsBeyond(dsl.select(keyset.sortValue(), keyset.itemId()).from(table), seek)
            .fetch(keyset::rowOf);
    var inMemory = InMemoryKeyset.rowsBeyond(allDateRows(), seek);
    assertThat(inMemory).as("in-memory keyset").isEqualTo(rows);
    return rows;
  }

  private static List<Row<UUID>> allDateRows() {
    return List.of(
        new Row<>(LOW_ID, new Position(new SortValue.OfDate(LocalDate.of(2011, 12, 29)), LOW_ID)),
        new Row<>(HIGH_ID, new Position(new SortValue.OfDate(LocalDate.of(2011, 12, 31)), HIGH_ID)),
        new Row<>(ABSENT_ID, new Position(new SortValue.Absent(), ABSENT_ID)),
        new Row<>(OTHER_ABSENT_ID, new Position(new SortValue.Absent(), OTHER_ABSENT_ID)));
  }

  private static Seek seekFrom(
      ListOrder order,
      Optional<Position> position,
      PaginationDirection direction,
      boolean including) {
    return Seek.builder()
        .order(order)
        .position(position)
        .direction(direction)
        .limit(10)
        .includingPosition(including)
        .build();
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
