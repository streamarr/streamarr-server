package com.streamarr.server.services.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("List order")
class ListOrderTest {

  private static final Map<SortValueKind, SortValue> VALUE_OF_KIND =
      Map.of(
          SortValueKind.TEXT, new SortValue.OfText("Alpha"),
          SortValueKind.INSTANT, new SortValue.OfInstant(Instant.EPOCH),
          SortValueKind.DATE, new SortValue.OfDate(LocalDate.EPOCH),
          SortValueKind.NUMBER, new SortValue.OfNumber(90));

  static Stream<Arguments> kindsAndValues() {
    return Arrays.stream(SortValueKind.values())
        .flatMap(
            kind ->
                VALUE_OF_KIND.entrySet().stream()
                    .map(value -> Arguments.of(kind, value.getValue(), value.getKey() == kind)));
  }

  @ParameterizedTest(name = "{0} admits {1}: {2}")
  @MethodSource("kindsAndValues")
  @DisplayName("Should admit a value only when it is of the kind the list sorts by")
  void shouldAdmitAValueOnlyWhenItIsOfTheKindTheListSortsBy(
      SortValueKind kind, SortValue value, boolean admitted) {
    var order = ListOrder.builder().valueKind(kind).direction(SortDirection.ASC).build();

    assertThat(order.admits(value)).isEqualTo(admitted);
    assertThat(order.admits(new SortValue.Absent())).isFalse();
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource({"NOT_ALLOWED, false", "LAST, true", "AS_LARGEST, true"})
  @DisplayName("Should admit an absent value only when the list places absent values")
  void shouldAdmitAnAbsentValueOnlyWhenTheListPlacesAbsentValues(
      AbsentValuePlacement placement, boolean admitted) {
    var order =
        ListOrder.builder()
            .valueKind(SortValueKind.DATE)
            .direction(SortDirection.ASC)
            .absentValues(placement)
            .build();

    assertThat(order.admits(new SortValue.Absent())).isEqualTo(admitted);
    assertThat(order.admits(new SortValue.OfDate(LocalDate.EPOCH))).isTrue();
  }
}
