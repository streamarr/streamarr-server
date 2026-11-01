package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.streamarr.server.domain.streaming.BitrateScanChunkOutcome;
import com.streamarr.server.domain.streaming.BitrateScanChunkRequest;
import com.streamarr.server.domain.streaming.KeyframeIntervalCounts;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.MeasuredChunkStream;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.transcode.v1.BitrateScanChunk;
import com.streamarr.transcode.v1.BitrateScanChunkResult;
import com.streamarr.transcode.v1.BitrateScanChunkStream;
import com.streamarr.transcode.v1.BitrateScanChunkStreams;
import com.streamarr.transcode.v1.BitrateScanCopyChangesPackets;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamMeasurementPart;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Remote Bitrate Scan Chunk Mapper Tests")
class RemoteBitrateScanChunkMapperTest {

  private static final int VIDEO = 0;
  private static final int AUDIO = 1;

  private final RemoteBitrateScanChunkMapper mapper = new RemoteBitrateScanChunkMapper();

  @Test
  @DisplayName(
      "Should send the first chunk's keyframes delta-coded and no end times when it is also the"
          + " last chunk")
  void shouldSendTheFirstChunksKeyframesDeltaCodedAndNoEndTimesWhenItIsAlsoTheLastChunk() {
    var request =
        BitrateScanChunkRequest.builder()
            .sourcePath(Path.of("/media/movie.mkv"))
            .attemptId(UUID.randomUUID())
            .measurementVersion(2)
            .chosenVideoStreamIndex(VIDEO)
            .measuredStreamIndexes(List.of(VIDEO, AUDIO))
            .timeBaseNumerator(1)
            .timeBaseDenominator(1_000)
            .keyframes(
                List.of(
                    new KeyframePosition(-80, -120),
                    new KeyframePosition(1_960, 1_920),
                    new KeyframePosition(4_000, 3_960)))
            .end(Optional.empty())
            .firstChunk(true)
            .build();

    assertThat(mapper.chunk(request))
        .isEqualTo(
            BitrateScanChunk.newBuilder()
                .setMeasurementVersion(2)
                .setChosenVideoStreamIndex(VIDEO)
                .addAllMeasuredStreamIndexes(List.of(VIDEO, AUDIO))
                .setTimeBaseNumerator(1)
                .setTimeBaseDenominator(1_000)
                .addAllPresentationTimeDeltas(List.of(-80L, 2_040L, 2_040L))
                .addAllDecodeTimeDeltas(List.of(-120L, 2_040L, 2_040L))
                .setFirstChunk(true)
                .build());
  }

  @Test
  @DisplayName(
      "Should report a stream without packets with no packet times when the chunk holds none of"
          + " its packets")
  void shouldReportAStreamWithoutPacketsWithNoPacketTimesWhenTheChunkHoldsNoneOfItsPackets() {
    var silentAudio =
        BitrateScanChunkStream.newBuilder()
            .setStreamIndex(AUDIO)
            .setTimeBaseNumerator(1)
            .setTimeBaseDenominator(48_000);

    var outcome =
        mapper.map(
            chunk(),
            report(
                measured(video(), silentAudio),
                List.of(intervals(VIDEO, 600, 400), intervals(AUDIO, 0, 0))));

    assertThat(outcome)
        .isEqualTo(
            new BitrateScanChunkOutcome.Measured(
                List.of(
                    MeasuredChunkStream.builder()
                        .streamIndex(VIDEO)
                        .measuredBytes(1_000)
                        .packetCount(2)
                        .timeBaseNumerator(1)
                        .timeBaseDenominator(90_000)
                        .packetTimes(Optional.of(new MeasuredChunkStream.PacketTimes(0, 180_000)))
                        .keyframeIntervalBytes(KeyframeIntervalCounts.of(600, 400))
                        .keyframeIntervalPacketCounts(KeyframeIntervalCounts.of(1, 1))
                        .build(),
                    MeasuredChunkStream.builder()
                        .streamIndex(AUDIO)
                        .timeBaseNumerator(1)
                        .timeBaseDenominator(48_000)
                        .packetTimes(Optional.empty())
                        .keyframeIntervalBytes(KeyframeIntervalCounts.of(0, 0))
                        .keyframeIntervalPacketCounts(KeyframeIntervalCounts.of(0, 0))
                        .build())));
  }

  @Test
  @DisplayName(
      "Should report the stream that a copy changes without parts when the worker finds one")
  void shouldReportTheStreamThatACopyChangesWithoutPartsWhenTheWorkerFindsOne() {
    var result =
        BitrateScanChunkResult.newBuilder()
            .setCopyChangesPackets(BitrateScanCopyChangesPackets.newBuilder().setStreamIndex(AUDIO))
            .build();

    assertThat(mapper.map(chunk(), report(result, List.of())))
        .isEqualTo(new BitrateScanChunkOutcome.CopyChangesPackets(AUDIO));
  }

  @Test
  @DisplayName("Should report a media error when the worker cannot read the chunk to its end")
  void shouldReportAMediaErrorWhenTheWorkerCannotReadTheChunkToItsEnd() {
    var report =
        new ProbeAttemptReport(
            ProbeAttemptResult.newBuilder()
                .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
                .build(),
            List.of());

    assertThat(mapper.map(chunk(), report))
        .isEqualTo(new BitrateScanChunkOutcome.MediaError(ProbeError.INVALID_MEDIA));
  }

  @Test
  @DisplayName(
      "Should leave the chunk waiting for a worker when the worker had no free unit for it")
  void shouldLeaveTheChunkWaitingForAWorkerWhenTheWorkerHadNoFreeUnitForIt() {
    var report =
        new ProbeAttemptReport(
            ProbeAttemptResult.newBuilder()
                .setFailure(ProbeFailure.PROBE_FAILURE_NO_FREE_CAPACITY)
                .build(),
            List.of());
    var chunk = chunk();

    assertThatThrownBy(() -> mapper.map(chunk, report))
        .isInstanceOf(ProbeWorkersBusyException.class);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("contractBreakingReports")
  @DisplayName("Should fail the chunk as a contract violation when the worker's reply breaks it")
  void shouldFailTheChunkAsAContractViolationWhenTheWorkersReplyBreaksIt(
      String ignoredName, ProbeAttemptReport report) {
    var chunk = chunk();

    assertThatThrownBy(() -> mapper.map(chunk, report))
        .isInstanceOf(ProbeContractViolationException.class);
  }

  private static Stream<Arguments> contractBreakingReports() {
    var bothParts = List.of(intervals(VIDEO, 600, 400), intervals(AUDIO, 30, 70));
    return Stream.of(
        arguments(
            "a probe result",
            new ProbeAttemptReport(
                ProbeAttemptResult.newBuilder().setMedia(ProbeMediaInfo.newBuilder()).build(),
                List.of())),
        arguments("no result", report(BitrateScanChunkResult.getDefaultInstance(), List.of())),
        arguments(
            "a copy change in a stream it was not asked to measure",
            report(
                BitrateScanChunkResult.newBuilder()
                    .setCopyChangesPackets(
                        BitrateScanCopyChangesPackets.newBuilder().setStreamIndex(7))
                    .build(),
                List.of())),
        arguments(
            "a stream it was not asked to measure",
            report(
                measured(video(), audio(), audio().setStreamIndex(7)),
                List.of(
                    intervals(VIDEO, 600, 400), intervals(AUDIO, 30, 70), intervals(7, 30, 70)))),
        arguments("one stream twice", report(measured(video(), audio(), audio()), bothParts)),
        arguments(
            "a requested stream left out",
            report(measured(video()), List.of(intervals(VIDEO, 600, 400)))),
        arguments(
            "parts for a stream it did not measure",
            report(
                measured(video(), audio()),
                List.of(
                    intervals(VIDEO, 600, 400), intervals(AUDIO, 30, 70), intervals(7, 30, 70)))),
        arguments(
            "fewer intervals than keyframes",
            report(
                measured(video().setMeasuredBytes(600).setPacketCount(1), audio()),
                List.of(intervals(VIDEO, 600), intervals(AUDIO, 30, 70)))),
        arguments(
            "no parts for a measured stream",
            report(measured(video(), audio()), List.of(intervals(VIDEO, 600, 400)))),
        arguments(
            "bytes that differ from the intervals' sum",
            report(measured(video().setMeasuredBytes(999), audio()), bothParts)),
        arguments(
            "packets that differ from the intervals' sum",
            report(measured(video().setPacketCount(3), audio()), bothParts)),
        arguments(
            "bytes beyond the signed 64-bit range",
            report(measured(video().setMeasuredBytes(-1), audio()), bothParts)),
        arguments(
            "a time base that is not positive",
            report(measured(video().setTimeBaseNumerator(0), audio()), bothParts)),
        arguments(
            "a first presentation time without an end",
            report(measured(video().clearEndTime(), audio()), bothParts)),
        arguments(
            "no times for a stream with packets",
            report(
                measured(video().clearFirstPresentationTime().clearEndTime(), audio()), bothParts)),
        arguments(
            "times for a stream without packets",
            report(
                measured(video(), audio().setMeasuredBytes(0).setPacketCount(0)),
                List.of(intervals(VIDEO, 600, 400), intervals(AUDIO, 0, 0)))),
        arguments(
            "an end before the first presentation time",
            report(measured(video().setEndTime(-1), audio()), bothParts)));
  }

  private static BitrateScanChunk chunk() {
    return BitrateScanChunk.newBuilder()
        .setMeasurementVersion(2)
        .setChosenVideoStreamIndex(VIDEO)
        .addAllMeasuredStreamIndexes(List.of(VIDEO, AUDIO))
        .setTimeBaseNumerator(1)
        .setTimeBaseDenominator(90_000)
        .addAllPresentationTimeDeltas(List.of(0L, 90_000L))
        .addAllDecodeTimeDeltas(List.of(0L, 90_000L))
        .setFirstChunk(true)
        .build();
  }

  private static BitrateScanChunkStream.Builder video() {
    return BitrateScanChunkStream.newBuilder()
        .setStreamIndex(VIDEO)
        .setMeasuredBytes(1_000)
        .setPacketCount(2)
        .setTimeBaseNumerator(1)
        .setTimeBaseDenominator(90_000)
        .setFirstPresentationTime(0)
        .setEndTime(180_000);
  }

  private static BitrateScanChunkStream.Builder audio() {
    return BitrateScanChunkStream.newBuilder()
        .setStreamIndex(AUDIO)
        .setMeasuredBytes(100)
        .setPacketCount(2)
        .setTimeBaseNumerator(1)
        .setTimeBaseDenominator(48_000)
        .setFirstPresentationTime(0)
        .setEndTime(96_000);
  }

  private static BitrateScanChunkResult measured(BitrateScanChunkStream.Builder... streams) {
    var measured = BitrateScanChunkStreams.newBuilder();
    Stream.of(streams).forEach(measured::addStreams);
    return BitrateScanChunkResult.newBuilder().setMeasured(measured).build();
  }

  /**
   * One part with these bytes in consecutive intervals, and one packet in each interval that holds
   * bytes.
   */
  private static ProbeStreamMeasurementPart intervals(int streamIndex, int... bytes) {
    var part = ProbeStreamMeasurementPart.newBuilder().setStreamIndex(streamIndex);
    for (var intervalBytes : bytes) {
      part.addKeyframeIntervalBytes(intervalBytes)
          .addKeyframeIntervalPacketCounts(Math.min(intervalBytes, 1));
    }

    return part.build();
  }

  private static ProbeAttemptReport report(
      BitrateScanChunkResult result, List<ProbeStreamMeasurementPart> parts) {
    return new ProbeAttemptReport(
        ProbeAttemptResult.newBuilder().setBitrateScanChunk(result).build(), List.of(), parts);
  }
}
