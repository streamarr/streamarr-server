package com.streamarr.server.services.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Sort value")
class SortValueTest {

  static Stream<Arguments> storedValues() {
    var instant = Instant.parse("2011-12-30T10:00:00Z");
    return Stream.of(
        Arguments.of("Élite", new SortValue.OfText("Élite")),
        Arguments.of(instant, new SortValue.OfInstant(instant)),
        Arguments.of(instant.atOffset(ZoneOffset.ofHours(14)), new SortValue.OfInstant(instant)),
        Arguments.of(LocalDate.of(2011, 12, 30), new SortValue.OfDate(LocalDate.of(2011, 12, 30))),
        Arguments.of(90, new SortValue.OfNumber(90)),
        Arguments.of(5_000_000_000L, new SortValue.OfNumber(5_000_000_000L)),
        Arguments.of(null, new SortValue.Absent()));
  }

  @ParameterizedTest
  @MethodSource("storedValues")
  @DisplayName("Should keep the value's type when it wraps a stored value")
  void shouldKeepTheValuesTypeWhenItWrapsAStoredValue(Object stored, SortValue expected) {
    assertThat(SortValue.of(stored)).isEqualTo(expected);
  }

  @Test
  @DisplayName("Should compare offset times as instants when their offsets differ")
  void shouldCompareOffsetTimesAsInstantsWhenTheirOffsetsDiffer() {
    var utc = OffsetDateTime.parse("2011-12-30T10:00:00Z");

    assertThat(SortValue.of(utc.withOffsetSameInstant(ZoneOffset.ofHours(-10))))
        .isEqualTo(SortValue.of(utc));
  }

  @Test
  @DisplayName("Should reject a stored value when lists cannot sort by its type")
  void shouldRejectAStoredValueWhenListsCannotSortByItsType() {
    var unsupported = UUID.randomUUID();

    assertThatThrownBy(() -> SortValue.of(unsupported))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
