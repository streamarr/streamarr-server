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
@DisplayName("Keyframe Interval Bytes Tests")
class KeyframeIntervalBytesTest {

  @ParameterizedTest
  @ValueSource(longs = {-1L, 0x1_0000_0000L})
  @DisplayName("Should refuse a count when it does not fit 32 unsigned bits")
  void shouldRefuseCountWhenItDoesNotFit32UnsignedBits(long count) {
    assertThatIllegalArgumentException().isThrownBy(() -> KeyframeIntervalBytes.of(0, count));
  }

  @Test
  @DisplayName("Should read every count as unsigned when a count uses the top bit")
  void shouldReadEveryCountAsUnsignedWhenCountUsesTopBit() {
    var bytes = KeyframeIntervalBytes.ofUnsigned(new int[] {-1, 7});

    assertThat(bytes.intervalCount()).isEqualTo(2);
    assertThat(bytes.bytesOf(0)).isEqualTo(0xFFFF_FFFFL);
    assertThat(bytes).isEqualTo(KeyframeIntervalBytes.of(0xFFFF_FFFFL, 7));
    assertThat(bytes).hasSameHashCodeAs(KeyframeIntervalBytes.of(0xFFFF_FFFFL, 7));
    assertThat(bytes).isNotEqualTo(KeyframeIntervalBytes.of(7, 0xFFFF_FFFFL)).isNotEqualTo("7");
    assertThat(bytes).hasToString("KeyframeIntervalBytes[intervals=2]");
  }

  @Test
  @DisplayName("Should keep its counts when a caller changes an array it gave or received")
  void shouldKeepItsCountsWhenCallerChangesArrayItGaveOrReceived() {
    var given = new int[] {1, 2};
    var bytes = KeyframeIntervalBytes.ofUnsigned(given);

    given[0] = 9;
    bytes.toUnsignedArray()[1] = 9;

    assertThat(bytes.toUnsignedArray()).containsExactly(1, 2);
  }

  @Test
  @DisplayName("Should refuse to build the counts when the array is missing")
  void shouldRefuseToBuildCountsWhenArrayIsMissing() {
    assertThatNullPointerException().isThrownBy(() -> KeyframeIntervalBytes.ofUnsigned(null));
    assertThatNullPointerException().isThrownBy(() -> KeyframeIntervalBytes.of((long[]) null));
  }
}
