package com.streamarr.server.services.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jooq.SortOrder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
class MediaOrderKeyTest {
  @Test
  void shouldOrderPrefixesUnicodeAndIdTiesInBothDirections() {
    var titles = List.of("a", "a", "a ", "aé", "a😀", "ä");
    for (var direction : List.of(SortOrder.ASC, SortOrder.DESC)) {
      var orders =
          IntStream.range(0, titles.size())
              .mapToObj(
                  index ->
                      MediaOrder.builder()
                          .by(OrderMediaBy.TITLE)
                          .direction(direction)
                          .itemId(new UUID(0, index + 1))
                          .value(titles.get(index))
                          .build())
              .toList();
      var sorted =
          orders.stream()
              .sorted(Comparator.comparing(MediaOrderKey::encode))
              .map(order -> order.itemId().getLeastSignificantBits())
              .toList();
      var expected = List.of(1L, 2L, 3L, 4L, 5L, 6L);
      assertThat(sorted).isEqualTo(direction == SortOrder.ASC ? expected : expected.reversed());
    }
  }

  @ParameterizedTest
  @CsvSource({
    "ADDED,1969-12-31T23:59:59.999999999Z,1970-01-01T00:00:00.000000001Z",
    "RELEASE_DATE,1969-12-31,1970-01-01",
    "RUNTIME,-1,0",
    "LAST_WATCHED,2026-01-01T00:00:00.000000001Z,2026-01-01T00:00:00.000000002Z"
  })
  void shouldOrderScalarsAndKeepNullableSortsNullsLast(
      OrderMediaBy by, String earlier, String later) {
    var id = new UUID(0, 1);
    for (var direction : List.of(SortOrder.ASC, SortOrder.DESC)) {
      var first =
          MediaOrderKey.encode(
              MediaOrder.builder().by(by).direction(direction).itemId(id).value(earlier).build());
      var second =
          MediaOrderKey.encode(
              MediaOrder.builder().by(by).direction(direction).itemId(id).value(later).build());
      var missing =
          MediaOrderKey.encode(
              MediaOrder.builder().by(by).direction(direction).itemId(id).value(null).build());
      assertThat(first.compareTo(second) < 0).isEqualTo(direction == SortOrder.ASC);
      assertThat(missing.compareTo(first) > 0)
          .isEqualTo(by != OrderMediaBy.ADDED || direction == SortOrder.ASC);
    }
  }
}
