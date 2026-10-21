package com.streamarr.server.services.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Media order")
class MediaOrderTest {

  static Stream<Arguments> positions() {
    var instant = Instant.parse("2026-10-20T12:00:00Z");
    var date = LocalDate.of(2011, 12, 30);
    return Stream.of(
        Arguments.of(OrderMediaBy.TITLE, new SortValue.OfText("Alpha"), "Alpha"),
        Arguments.of(OrderMediaBy.LAST_WATCHED, new SortValue.OfInstant(instant), instant),
        Arguments.of(OrderMediaBy.RELEASE_DATE, new SortValue.OfDate(date), date),
        Arguments.of(OrderMediaBy.RUNTIME, new SortValue.OfNumber(90), 90L),
        Arguments.of(OrderMediaBy.RUNTIME, new SortValue.Absent(), null));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("positions")
  @DisplayName("Should order a position by its value when the list sorts by that value")
  void shouldOrderAPositionByItsValueWhenTheListSortsByThatValue(
      OrderMediaBy by, SortValue value, Object expected) {
    var itemId = UUID.randomUUID();

    var order = MediaOrder.at(by, SortDirection.DESC, new Position(value, itemId));

    assertThat(order)
        .isEqualTo(
            MediaOrder.builder()
                .by(by)
                .direction(SortDirection.DESC)
                .itemId(itemId)
                .value(expected)
                .build());
  }
}
