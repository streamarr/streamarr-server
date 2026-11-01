package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Measured Stream Tests")
class MeasuredStreamTest {

  @Test
  @DisplayName("Should build a measured stream when every value is in range")
  void shouldBuildMeasuredStreamWhenEveryValueIsInRange() {
    assertThat(validStream().build().measuredBytes()).isEqualTo(3);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidStreams")
  @DisplayName("Should refuse a measured stream when one of its values is out of range")
  void shouldRefuseMeasuredStreamWhenOneOfItsValuesIsOutOfRange(
      String field, UnaryOperator<MeasuredStream.MeasuredStreamBuilder> change) {
    var builder = change.apply(validStream());

    assertThatIllegalArgumentException().isThrownBy(builder::build);
  }

  static Stream<Arguments> invalidStreams() {
    return Stream.of(
        Arguments.of("stream index", op(builder -> builder.streamIndex(-1))),
        Arguments.of("measured bytes", op(builder -> builder.measuredBytes(-1))),
        Arguments.of("packet count", op(builder -> builder.packetCount(-1))),
        Arguments.of("measured duration", op(builder -> builder.measuredDuration(-1))),
        Arguments.of("time base numerator", op(builder -> builder.timeBaseNumerator(0))),
        Arguments.of("time base denominator", op(builder -> builder.timeBaseDenominator(0))));
  }

  @Test
  @DisplayName("Should refuse a measured stream when its interval bytes are missing")
  void shouldRefuseMeasuredStreamWhenItsIntervalBytesAreMissing() {
    var builder = validStream();

    assertThatNullPointerException().isThrownBy(() -> builder.keyframeIntervalBytes(null));
  }

  @Test
  @DisplayName("Should refuse a measured stream when its interval packet counts are missing")
  void shouldRefuseMeasuredStreamWhenItsIntervalPacketCountsAreMissing() {
    var builder = validStream();

    assertThatNullPointerException().isThrownBy(() -> builder.keyframeIntervalPacketCounts(null));
  }

  @Test
  @DisplayName(
      "Should refuse a measured stream when its packet counts cover other intervals than its bytes")
  void shouldRefuseMeasuredStreamWhenItsPacketCountsCoverOtherIntervalsThanItsBytes() {
    var builder = validStream().keyframeIntervalPacketCounts(KeyframeIntervalCounts.of(1, 0));

    assertThatIllegalArgumentException().isThrownBy(builder::build);
  }

  private static UnaryOperator<MeasuredStream.MeasuredStreamBuilder> op(
      UnaryOperator<MeasuredStream.MeasuredStreamBuilder> change) {
    return change;
  }

  private static MeasuredStream.MeasuredStreamBuilder validStream() {
    return MeasuredStream.builder()
        .streamIndex(0)
        .measuredBytes(3)
        .packetCount(1)
        .timeBaseNumerator(1)
        .timeBaseDenominator(1_000)
        .measuredDuration(40)
        .keyframeIntervalBytes(KeyframeIntervalCounts.of(3))
        .keyframeIntervalPacketCounts(KeyframeIntervalCounts.of(1));
  }
}
