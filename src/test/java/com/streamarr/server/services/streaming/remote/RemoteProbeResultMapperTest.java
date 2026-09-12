package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeContainerInfo;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Remote Probe Result Mapper Tests")
class RemoteProbeResultMapperTest {

  @Test
  @DisplayName(
      "Should preserve absent properties when a successful probe has incomplete optional metadata")
  void shouldPreserveAbsentPropertiesWhenSuccessfulProbeHasIncompleteOptionalMetadata() {
    var result =
        ProbeAttemptResult.newBuilder()
            .setMedia(
                ProbeMediaInfo.newBuilder()
                    .addStreams(ProbeStreamInfo.newBuilder().setCodecType("video")))
            .build();

    var outcome = new RemoteProbeResultMapper().map(result);

    var video = StreamInfo.builder().index(0).codecType("video").build();
    assertThat(outcome)
        .isEqualTo(new ProbeOutcome.Success(ProbeContainer.builder().build(), List.of(video)));
  }

  @Test
  @DisplayName("Should retain a terminal media error when the worker rejects invalid media")
  void shouldRetainTerminalMediaErrorWhenWorkerRejectsInvalidMedia() {
    var result =
        ProbeAttemptResult.newBuilder()
            .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
            .build();

    var outcome = new RemoteProbeResultMapper().map(result);

    assertThat(outcome).isEqualTo(new ProbeOutcome.Failure(ProbeError.INVALID_MEDIA));
  }

  @Test
  @DisplayName("Should retain a terminal missing-video error when the worker finds no video stream")
  void shouldRetainTerminalMissingVideoErrorWhenWorkerFindsNoVideoStream() {
    var result =
        ProbeAttemptResult.newBuilder()
            .setFailure(ProbeFailure.PROBE_FAILURE_NO_VIDEO_STREAM)
            .build();

    var outcome = new RemoteProbeResultMapper().map(result);

    assertThat(outcome).isEqualTo(new ProbeOutcome.Failure(ProbeError.NO_VIDEO_STREAM));
  }

  @ParameterizedTest
  @MethodSource("transientFailures")
  @DisplayName("Should leave execution failures retryable when the worker cannot complete a probe")
  void shouldLeaveExecutionFailuresRetryableWhenWorkerCannotCompleteProbe(ProbeFailure failure) {
    var mapper = new RemoteProbeResultMapper();
    var result = ProbeAttemptResult.newBuilder().setFailure(failure).build();

    assertThatThrownBy(() -> mapper.map(result)).isInstanceOf(ProbeExecutionException.class);
  }

  private static Stream<ProbeFailure> transientFailures() {
    return Stream.of(
        ProbeFailure.PROBE_FAILURE_SOURCE_UNAVAILABLE,
        ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED,
        ProbeFailure.PROBE_FAILURE_UNSUPPORTED_VERSION,
        ProbeFailure.PROBE_FAILURE_CANCELLED);
  }

  @ParameterizedTest
  @MethodSource("transientFailures")
  @DisplayName("Should retain the reported failure reason when a worker probe remains retryable")
  void shouldRetainReportedFailureReasonWhenWorkerProbeRemainsRetryable(ProbeFailure failure) {
    var result = ProbeAttemptResult.newBuilder().setFailure(failure).build();

    assertThatThrownBy(() -> new RemoteProbeResultMapper().map(result))
        .isInstanceOf(ProbeExecutionException.class)
        .hasStackTraceContaining(failure.name());
  }

  @ParameterizedTest
  @MethodSource("unknownOutcomes")
  @DisplayName("Should leave an unknown or missing worker outcome retryable")
  void shouldLeaveUnknownOrMissingWorkerOutcomeRetryable(ProbeAttemptResult result) {
    var mapper = new RemoteProbeResultMapper();

    assertThatThrownBy(() -> mapper.map(result)).isInstanceOf(ProbeExecutionException.class);
  }

  private static Stream<ProbeAttemptResult> unknownOutcomes() {
    return Stream.of(
        ProbeAttemptResult.getDefaultInstance(),
        ProbeAttemptResult.newBuilder().setFailureValue(0).build(),
        ProbeAttemptResult.newBuilder().setFailureValue(999).build());
  }

  @Test
  @DisplayName("Should reject an unusable success when its stream list contains no video")
  void shouldRejectUnusableSuccessWhenItsStreamListContainsNoVideo() {
    var mapper = new RemoteProbeResultMapper();
    var result =
        ProbeAttemptResult.newBuilder()
            .setMedia(
                ProbeMediaInfo.newBuilder()
                    .addStreams(ProbeStreamInfo.newBuilder().setCodecType("audio")))
            .build();

    assertThatThrownBy(() -> mapper.map(result)).isInstanceOf(ProbeExecutionException.class);
  }

  @Test
  @DisplayName("Should reject a malformed stream before selecting a later video stream")
  void shouldRejectMalformedStreamBeforeSelectingLaterVideoStream() {
    var mapper = new RemoteProbeResultMapper();
    var result =
        ProbeAttemptResult.newBuilder()
            .setMedia(
                ProbeMediaInfo.newBuilder()
                    .addStreams(ProbeStreamInfo.getDefaultInstance())
                    .addStreams(ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("video")))
            .build();

    assertThatThrownBy(() -> mapper.map(result)).isInstanceOf(ProbeExecutionException.class);
  }

  @Test
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @DisplayName("Should retain complete ordered media properties when the worker returns a success")
  void shouldRetainCompleteOrderedMediaPropertiesWhenWorkerReturnsSuccess() {
    var media =
        ProbeMediaInfo.newBuilder()
            .setContainer(
                ProbeContainerInfo.newBuilder()
                    .setFormat("matroska,webm")
                    .setDuration(
                        com.google.protobuf.Duration.newBuilder()
                            .setSeconds(125)
                            .setNanos(123456789))
                    .setBitrateBitsPerSecond(9_000_000))
            .addStreams(
                ProbeStreamInfo.newBuilder()
                    .setIndex(2)
                    .setCodecType("video")
                    .setCodec("h264")
                    .setWidth(1920)
                    .setHeight(1080)
                    .setFramerate(23.976)
                    .setBitrateBitsPerSecond(8_000_000))
            .addStreams(
                ProbeStreamInfo.newBuilder()
                    .setIndex(4)
                    .setCodecType("audio")
                    .setCodec("aac")
                    .setChannels(6)
                    .setLanguage("eng")
                    .setBitrateBitsPerSecond(512_000)
                    .setIsDefault(true)
                    .setIsForced(true))
            .addStreams(ProbeStreamInfo.newBuilder().setIndex(11).setCodecType("unknown"))
            .build();
    var result = ProbeAttemptResult.newBuilder().setMedia(media).build();

    var outcome = new RemoteProbeResultMapper().map(result);

    var container =
        ProbeContainer.builder()
            .format(Optional.of("matroska,webm"))
            .duration(Optional.of(Duration.ofSeconds(125, 123456789)))
            .bitrate(OptionalLong.of(9_000_000))
            .build();
    var video =
        StreamInfo.builder()
            .index(2)
            .codecType("video")
            .codec(Optional.of("h264"))
            .width(OptionalInt.of(1920))
            .height(OptionalInt.of(1080))
            .framerate(OptionalDouble.of(23.976))
            .bitrate(OptionalLong.of(8_000_000))
            .build();
    var audio =
        StreamInfo.builder()
            .index(4)
            .codecType("audio")
            .codec(Optional.of("aac"))
            .channels(OptionalInt.of(6))
            .language(Optional.of("eng"))
            .bitrate(OptionalLong.of(512_000))
            .isDefault(true)
            .isForced(true)
            .build();
    var unknown = StreamInfo.builder().index(11).codecType("unknown").build();
    assertThat(outcome)
        .isEqualTo(new ProbeOutcome.Success(container, List.of(video, audio, unknown)));
  }
}
