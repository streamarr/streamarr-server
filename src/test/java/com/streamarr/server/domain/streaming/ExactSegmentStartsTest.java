package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Exact Segment Starts Tests")
class ExactSegmentStartsTest {

  private static final Fraction NINETY_KILOHERTZ = new Fraction(1, 90_000);

  @Test
  @DisplayName("Should keep its own copy of the ticks when the caller changes its list afterwards")
  void shouldKeepItsOwnCopyOfTheTicksWhenTheCallerChangesItsListAfterwards() {
    var ticks = new ArrayList<>(List.of(0L, 315_000L));

    var starts = new ExactSegmentStarts(ticks, NINETY_KILOHERTZ);
    ticks.add(585_000L);

    assertThat(starts.ticks()).containsExactly(0L, 315_000L);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("listsTheWorkerRefuses")
  @DisplayName("Should refuse a segment list when the worker contract refuses it")
  void shouldRefuseASegmentListWhenTheWorkerContractRefusesIt(
      String description, List<Long> ticks) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ExactSegmentStarts(ticks, NINETY_KILOHERTZ));
  }

  static List<Arguments> listsTheWorkerRefuses() {
    return List.of(
        Arguments.of("no segment", List.of()),
        Arguments.of("segment 0 after zero", List.of(3_600L, 315_000L)),
        Arguments.of("a repeated start", List.of(0L, 315_000L, 315_000L)),
        Arguments.of("a start before the one before it", List.of(0L, 585_000L, 315_000L)));
  }
}
