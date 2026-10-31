package com.streamarr.server.repositories.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.KeyframeIntervalBytes;
import com.streamarr.server.exceptions.MalformedStreamMeasurementException;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Keyframe Interval Bytes Codec Tests")
class KeyframeIntervalBytesCodecTest {

  @Test
  @DisplayName("Should round-trip every count when the counts span the unsigned 32-bit range")
  void shouldRoundTripEveryCountWhenCountsSpanUnsigned32BitRange() {
    var intervals = KeyframeIntervalBytes.of(0, 1, 0x7FFF_FFFFL, 0x8000_0000L, 0xFFFF_FFFFL);

    var decoded = KeyframeIntervalBytesCodec.decode(KeyframeIntervalBytesCodec.encode(intervals));

    assertThat(decoded).isEqualTo(intervals);
  }

  @Test
  @DisplayName(
      "Should take four bytes for each interval when a three-hour source has a keyframe every"
          + " half second")
  void shouldTakeFourBytesForEachIntervalWhenThreeHourSourceHasKeyframeEveryHalfSecond() {
    var intervals = KeyframeIntervalBytes.of(LongStream.range(0, 21_600).toArray());

    var encoded = KeyframeIntervalBytesCodec.encode(intervals);

    assertThat(encoded).hasSize(1 + 4 * 21_600);
    assertThat(KeyframeIntervalBytesCodec.decode(encoded)).isEqualTo(intervals);
  }

  @Test
  @DisplayName("Should reject the stored bytes when they name an unknown format")
  void shouldRejectStoredBytesWhenTheyNameUnknownFormat() {
    var encoded = new byte[] {2, 0, 0, 0, 1};

    assertThatThrownBy(() -> KeyframeIntervalBytesCodec.decode(encoded))
        .isInstanceOf(MalformedStreamMeasurementException.class)
        .hasMessage("Unknown keyframe interval bytes format");
  }

  @Test
  @DisplayName("Should reject the stored bytes when they are empty")
  void shouldRejectStoredBytesWhenTheyAreEmpty() {
    var encoded = new byte[0];

    assertThatThrownBy(() -> KeyframeIntervalBytesCodec.decode(encoded))
        .isInstanceOf(MalformedStreamMeasurementException.class)
        .hasMessage("Unknown keyframe interval bytes format");
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 3, 4, 6})
  @DisplayName("Should reject the stored bytes when they end inside a count")
  void shouldRejectStoredBytesWhenTheyEndInsideCount(int length) {
    var encoded = new byte[length];
    encoded[0] = 1;

    assertThatThrownBy(() -> KeyframeIntervalBytesCodec.decode(encoded))
        .isInstanceOf(MalformedStreamMeasurementException.class)
        .hasMessage("Keyframe interval bytes end inside a count");
  }
}
