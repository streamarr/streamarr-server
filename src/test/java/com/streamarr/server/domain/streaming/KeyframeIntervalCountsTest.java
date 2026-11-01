package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Keyframe Interval Counts Tests")
class KeyframeIntervalCountsTest {

  @ParameterizedTest
  @ValueSource(longs = {-1L, 0x1_0000_0000L})
  @DisplayName("Should refuse a count when it does not fit 32 unsigned bits")
  void shouldRefuseCountWhenItDoesNotFit32UnsignedBits(long count) {
    assertThatIllegalArgumentException().isThrownBy(() -> KeyframeIntervalCounts.of(0, count));
  }

  @Test
  @DisplayName("Should read every count as unsigned when a count uses the top bit")
  void shouldReadEveryCountAsUnsignedWhenCountUsesTopBit() {
    var counts = KeyframeIntervalCounts.ofUnsigned(new int[] {-1, 7});

    assertThat(counts.intervalCount()).isEqualTo(2);
    assertThat(counts.countOf(0)).isEqualTo(0xFFFF_FFFFL);
    assertThat(counts)
        .isEqualTo(KeyframeIntervalCounts.of(0xFFFF_FFFFL, 7))
        .hasSameHashCodeAs(KeyframeIntervalCounts.of(0xFFFF_FFFFL, 7))
        .isNotEqualTo(KeyframeIntervalCounts.of(7, 0xFFFF_FFFFL))
        .hasToString("KeyframeIntervalCounts[intervals=2]");
  }

  @Test
  @DisplayName("Should keep its counts when a caller changes an array it gave or received")
  void shouldKeepItsCountsWhenCallerChangesArrayItGaveOrReceived() {
    var given = new int[] {1, 2};
    var counts = KeyframeIntervalCounts.ofUnsigned(given);

    given[0] = 9;
    counts.toUnsignedArray()[1] = 9;

    assertThat(counts.toUnsignedArray()).containsExactly(1, 2);
  }

  @Test
  @DisplayName("Should refuse to build the counts when the array is missing")
  void shouldRefuseToBuildCountsWhenArrayIsMissing() {
    assertThatNullPointerException().isThrownBy(() -> KeyframeIntervalCounts.ofUnsigned(null));
    assertThatNullPointerException().isThrownBy(() -> KeyframeIntervalCounts.of((long[]) null));
  }
}
