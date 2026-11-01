package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.streamarr.server.domain.streaming.KeyframeIntervalCounts;
import com.streamarr.server.domain.streaming.MeasuredChunkStream;
import com.streamarr.server.domain.streaming.MeasuredStream;
import com.streamarr.server.domain.streaming.StreamMeasurementSource;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.task.StoredBitrateScanChunk;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Bitrate Scan Assembly Tests")
class BitrateScanAssemblyTest {

  private static final int VIDEO = 0;
  private static final int AUDIO = 1;

  @Test
  @DisplayName(
      "Should join each stream's chunks in interval order when the chunks tile every interval")
  void shouldJoinEachStreamsChunksInIntervalOrderWhenTheChunksTileEveryInterval() {
    var first =
        chunk(
            0,
            stream(VIDEO).build(),
            audio().packetTimes(Optional.of(new MeasuredChunkStream.PacketTimes(10, 900))).build());
    var second =
        chunk(
            2,
            stream(VIDEO, 700, 300)
                .packetTimes(Optional.of(new MeasuredChunkStream.PacketTimes(180_000, 360_000)))
                .build(),
            audio()
                .packetTimes(Optional.of(new MeasuredChunkStream.PacketTimes(950, 1_920)))
                .build());

    assertThat(BitrateScanAssembly.assemble(List.of(first, second), 4))
        .contains(
            new StreamMeasurements(
                StreamMeasurementSource.BITRATE_SCAN,
                List.of(
                    MeasuredStream.builder()
                        .streamIndex(VIDEO)
                        .measuredBytes(2_000 + 1_000)
                        .packetCount(4)
                        .timeBaseNumerator(1)
                        .timeBaseDenominator(90_000)
                        .measuredDuration(360_000)
                        .keyframeIntervalBytes(KeyframeIntervalCounts.of(1_200, 800, 700, 300))
                        .keyframeIntervalPacketCounts(KeyframeIntervalCounts.of(1, 1, 1, 1))
                        .build(),
                    MeasuredStream.builder()
                        .streamIndex(AUDIO)
                        .measuredBytes(200)
                        .packetCount(4)
                        .timeBaseNumerator(1)
                        .timeBaseDenominator(1_000)
                        .measuredDuration(1_910)
                        .keyframeIntervalBytes(KeyframeIntervalCounts.of(50, 50, 50, 50))
                        .keyframeIntervalPacketCounts(KeyframeIntervalCounts.of(1, 1, 1, 1))
                        .build())));
  }

  @Test
  @DisplayName(
      "Should measure no duration for a stream when no chunk holds a packet with known times")
  void shouldMeasureNoDurationForAStreamWhenNoChunkHoldsAPacketWithKnownTimes() {
    var chunks =
        List.of(
            chunk(0, audio().packetTimes(Optional.empty()).build()),
            chunk(2, audio().packetTimes(Optional.empty()).build()));

    assertThat(BitrateScanAssembly.assemble(chunks, 4))
        .hasValueSatisfying(
            measurement ->
                assertThat(measurement.streams().getFirst().measuredDuration()).isZero());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("disagreeingChunks")
  @DisplayName("Should assemble no measurement when the stored chunks disagree")
  void shouldAssembleNoMeasurementWhenTheStoredChunksDisagree(
      String disagreement, List<StoredBitrateScanChunk> chunks) {
    assertThat(BitrateScanAssembly.assemble(chunks, 4)).isEmpty();
  }

  private static Stream<Arguments> disagreeingChunks() {
    var video = stream(VIDEO).build();
    return Stream.of(
        arguments("no chunk", List.of()),
        arguments("a gap", List.of(chunk(0, video), chunk(3, video))),
        arguments("too few intervals", List.of(chunk(0, video))),
        arguments(
            "too many intervals",
            List.of(chunk(0, video), chunk(2, video), chunk(4, stream(VIDEO).build()))),
        arguments(
            "a stream missing from a later chunk",
            List.of(chunk(0, video, audio().build()), chunk(2, video))),
        arguments(
            "a stream only in a later chunk",
            List.of(chunk(0, video), chunk(2, video, audio().build()))),
        arguments(
            "another time base",
            List.of(chunk(0, video), chunk(2, stream(VIDEO).timeBaseDenominator(1_000).build()))),
        arguments(
            "another interval count",
            List.of(chunk(0, video), chunk(2, stream(VIDEO, 700, 300, 1).build()))));
  }

  private static StoredBitrateScanChunk chunk(
      int firstKeyframeInterval, MeasuredChunkStream... streams) {
    return StoredBitrateScanChunk.builder()
        .firstKeyframeInterval(firstKeyframeInterval)
        .keyframeIntervalCount(2)
        .streams(List.of(streams))
        .build();
  }

  // Two keyframe intervals of 1,200 and 800 bytes from 0 to two seconds, one packet in each.
  private static MeasuredChunkStream.MeasuredChunkStreamBuilder stream(int index) {
    return stream(index, 1_200, 800)
        .packetTimes(Optional.of(new MeasuredChunkStream.PacketTimes(0, 180_000)));
  }

  private static MeasuredChunkStream.MeasuredChunkStreamBuilder stream(
      int index, long... intervalBytes) {
    var packets = new long[intervalBytes.length];
    Arrays.fill(packets, 1);
    return MeasuredChunkStream.builder()
        .streamIndex(index)
        .measuredBytes(Arrays.stream(intervalBytes).sum())
        .packetCount(packets.length)
        .timeBaseNumerator(1)
        .timeBaseDenominator(90_000)
        .packetTimes(Optional.empty())
        .keyframeIntervalBytes(KeyframeIntervalCounts.of(intervalBytes))
        .keyframeIntervalPacketCounts(KeyframeIntervalCounts.of(packets));
  }

  private static MeasuredChunkStream.MeasuredChunkStreamBuilder audio() {
    return stream(AUDIO, 50, 50).timeBaseDenominator(1_000);
  }
}
