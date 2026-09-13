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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Remote Probe Result Mapper Tests")
class RemoteProbeResultMapperTest {

  @ParameterizedTest
  @ValueSource(ints = {-1, Integer.MIN_VALUE})
  @DisplayName("Should reject a stream when its required index exceeds the supported integer range")
  void shouldRejectStreamWhenItsRequiredIndexExceedsSupportedIntegerRange(int index)
      throws Exception {
    var mapper = new RemoteProbeResultMapper();
    var result = resultWithStream(ProbeStreamInfo.newBuilder().setIndex(index));

    assertThatThrownBy(() -> mapper.map(result)).isInstanceOf(ProbeExecutionException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, Integer.MIN_VALUE})
  @DisplayName(
      "Should leave optional integers unknown when unsigned worker values exceed Java range")
  void shouldLeaveOptionalIntegersUnknownWhenUnsignedWorkerValuesExceedJavaRange(int value)
      throws Exception {
    var result =
        resultWithStream(
            ProbeStreamInfo.newBuilder().setWidth(value).setHeight(value).setChannels(value));
    var outcome = (ProbeOutcome.Success) new RemoteProbeResultMapper().map(result);
    var stream = outcome.streams().getFirst();

    assertThat(stream.width()).isEmpty();
    assertThat(stream.height()).isEmpty();
    assertThat(stream.channels()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
  @DisplayName("Should leave framerate unknown when a worker reports an invalid rate")
  void shouldLeaveFramerateUnknownWhenWorkerReportsInvalidRate(double rate) throws Exception {
    var result = resultWithStream(ProbeStreamInfo.newBuilder().setFramerate(rate));
    var outcome = (ProbeOutcome.Success) new RemoteProbeResultMapper().map(result);

    assertThat(outcome.streams().getFirst().framerate()).isEmpty();
  }

  private ProbeAttemptResult resultWithStream(ProbeStreamInfo.Builder stream) throws Exception {
    return ProbeAttemptResult.parseFrom(
        ProbeAttemptResult.newBuilder()
            .setMedia(ProbeMediaInfo.newBuilder().addStreams(stream.setCodecType("video")))
            .build()
            .toByteArray());
  }

  @ParameterizedTest
  @CsvSource({
    "9223372036854775807,1000000000",
    "-9223372036854775808,0",
    "315576000001,0",
    "-315576000001,0",
    "0,1000000000",
    "0,-1000000000",
    "1,-1",
    "-1,1"
  })
  @DisplayName("Should reject a malformed duration when a worker violates the protobuf contract")
  void shouldRejectMalformedDurationWhenWorkerViolatesProtobufContract(long seconds, int nanos)
      throws Exception {
    var mapper = new RemoteProbeResultMapper();
    var result = resultWithDuration(seconds, nanos);

    assertThatThrownBy(() -> mapper.map(result)).isInstanceOf(ProbeExecutionException.class);
  }

  private ProbeAttemptResult resultWithDuration(long seconds, int nanos) throws Exception {
    var media =
        ProbeMediaInfo.newBuilder().addStreams(ProbeStreamInfo.newBuilder().setCodecType("video"));
    media.getContainerBuilder().getDurationBuilder().setSeconds(seconds).setNanos(nanos);
    return ProbeAttemptResult.parseFrom(
        ProbeAttemptResult.newBuilder().setMedia(media).build().toByteArray());
  }

  @ParameterizedTest
  @CsvSource({
    "-1,0,false",
    "0,-1,false",
    "-315576000000,-999999999,false",
    "0,0,true",
    "315576000000,999999999,true"
  })
  @DisplayName("Should retain a valid duration only when the worker reports nonnegative media time")
  void shouldRetainValidDurationOnlyWhenWorkerReportsNonnegativeMediaTime(
      long seconds, int nanos, boolean known) throws Exception {
    var result = resultWithDuration(seconds, nanos);
    var outcome = (ProbeOutcome.Success) new RemoteProbeResultMapper().map(result);

    assertThat(outcome.container().duration())
        .isEqualTo(known ? Optional.of(Duration.ofSeconds(seconds, nanos)) : Optional.empty());
  }

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
    var mapper = new RemoteProbeResultMapper();
    var result = ProbeAttemptResult.newBuilder().setFailure(failure).build();

    assertThatThrownBy(() -> mapper.map(result))
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
