package com.streamarr.server.repositories.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.exceptions.MalformedKeyframePositionsException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Keyframe Positions Codec Tests")
class KeyframePositionsCodecTest {

  // A 90 kHz time base, as MPEG-TS sources use.
  private static final long TICKS_PER_SECOND = 90_000;
  // Two frames at 24 fps: the presentation delay that B-frame reordering gives a keyframe.
  private static final long B_FRAME_PRESENTATION_DELAY = 7_500;

  @Test
  @DisplayName(
      "Should round-trip presentation and decode times when B-frames put a presentation time after its decode time")
  void shouldRoundTripPresentationAndDecodeTimesWhenBFramesPutPresentationTimeAfterItsDecodeTime() {
    var keyframes =
        List.of(
            new KeyframePosition(B_FRAME_PRESENTATION_DELAY, 0),
            new KeyframePosition(
                2 * TICKS_PER_SECOND + B_FRAME_PRESENTATION_DELAY, 2 * TICKS_PER_SECOND),
            new KeyframePosition(5 * TICKS_PER_SECOND, 5 * TICKS_PER_SECOND - 3_750));

    var decoded = KeyframePositionsCodec.decode(KeyframePositionsCodec.encode(keyframes));

    assertThat(decoded).isEqualTo(keyframes);
  }

  @Test
  @DisplayName(
      "Should round-trip a negative decode time when the first keyframe decodes before zero")
  void shouldRoundTripNegativeDecodeTimeWhenFirstKeyframeDecodesBeforeZero() {
    var keyframes =
        List.of(
            new KeyframePosition(0, -B_FRAME_PRESENTATION_DELAY),
            new KeyframePosition(2 * TICKS_PER_SECOND, 2 * TICKS_PER_SECOND - 3_750));

    var decoded = KeyframePositionsCodec.decode(KeyframePositionsCodec.encode(keyframes));

    assertThat(decoded).isEqualTo(keyframes);
  }

  @Test
  @DisplayName("Should round-trip extreme times when the differences overflow a long")
  void shouldRoundTripExtremeTimesWhenDifferencesOverflowLong() {
    var keyframes =
        List.of(
            new KeyframePosition(Long.MAX_VALUE, Long.MIN_VALUE),
            new KeyframePosition(Long.MIN_VALUE, Long.MAX_VALUE));

    var decoded = KeyframePositionsCodec.decode(KeyframePositionsCodec.encode(keyframes));

    assertThat(decoded).isEqualTo(keyframes);
  }

  @Test
  @DisplayName("Should encode 3,600 keyframes (a 2-hour source with a 2 s GOP) in under 24 KB")
  void shouldEncode3600KeyframesOfTwoHourSourceWithTwoSecondGopInUnder24Kb() {
    // Two BIGINT arrays would take 3,600 × 2 × 8 bytes = 57,600 bytes before any array header.
    var keyframes =
        LongStream.range(0, 3_600)
            .mapToObj(
                keyframe -> {
                  var decodeTime = keyframe * 2 * TICKS_PER_SECOND;
                  return new KeyframePosition(decodeTime + B_FRAME_PRESENTATION_DELAY, decodeTime);
                })
            .toList();

    var encoded = KeyframePositionsCodec.encode(keyframes);

    assertThat(encoded).hasSizeLessThan(24 * 1_024);
    assertThat(KeyframePositionsCodec.decode(encoded)).isEqualTo(keyframes);
  }

  @Test
  @DisplayName("Should reject an encoding when its format byte is unknown")
  void shouldRejectEncodingWhenItsFormatByteIsUnknown() {
    var encoded = KeyframePositionsCodec.encode(List.of(new KeyframePosition(0, 0)));
    encoded[0] = 2;

    assertThatThrownBy(() -> KeyframePositionsCodec.decode(encoded))
        .isInstanceOf(MalformedKeyframePositionsException.class);
  }

  @Test
  @DisplayName("Should reject an encoding when it holds no format byte")
  void shouldRejectEncodingWhenItHoldsNoFormatByte() {
    var encoded = new byte[0];

    assertThatThrownBy(() -> KeyframePositionsCodec.decode(encoded))
        .isInstanceOf(MalformedKeyframePositionsException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  @DisplayName("Should reject an encoding when it ends inside a keyframe")
  void shouldRejectEncodingWhenItEndsInsideKeyframe(int removedBytes) {
    var encoded =
        KeyframePositionsCodec.encode(
            List.of(new KeyframePosition(TICKS_PER_SECOND, TICKS_PER_SECOND)));
    var truncated = Arrays.copyOf(encoded, encoded.length - removedBytes);

    assertThatThrownBy(() -> KeyframePositionsCodec.decode(truncated))
        .isInstanceOf(MalformedKeyframePositionsException.class);
  }

  @Test
  @DisplayName("Should reject an encoding when a number runs past 64 bits")
  void shouldRejectEncodingWhenNumberRunsPast64Bits() {
    var encoded = new byte[12];
    encoded[0] = 1;
    Arrays.fill(encoded, 1, 12, (byte) 0x80);

    assertThatThrownBy(() -> KeyframePositionsCodec.decode(encoded))
        .isInstanceOf(MalformedKeyframePositionsException.class);
  }
}
