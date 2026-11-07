package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.softwareEncoder;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.fromProto;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.TranscodeFailure;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.Fraction;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.JobAttemptFailure;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.TranscodeDecision;
import com.streamarr.transcode.v1.TranscodeExecution;
import com.streamarr.transcode.v1.TranscodeMode;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VariantSpec;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.VideoDecision;
import com.streamarr.transcode.v1.VideoMode;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

// ADR 0037: every replacement attempt of a copy on its keyframe-merged segment list cuts the same
// segments, so a segment over the cap ends the stream session's recovery. An encode on the plain
// grid keeps ordinary recovery, even when the grid's final slot merges into its last segment.
@Tag("UnitTest")
@DisplayName("Segment Cap Failure Registry Tests")
class SegmentCapFailureRegistryTest {

  private static final UUID WORKER_ID = UUID.randomUUID();
  private static final TranscodeExecution EXACT_STARTS =
      TranscodeExecution.newBuilder()
          .setMediaSegmentCount(3)
          .addAllSegmentStartTicks(List.of(0L, 6_006L, 18_018L))
          .setSegmentStartTimeBase(Fraction.newBuilder().setNumerator(1).setDenominator(1_000))
          .build();
  private static final TranscodeExecution MERGED_PLAIN_GRID_TICKS =
      TranscodeExecution.newBuilder()
          .setMediaSegmentCount(2)
          .addAllSegmentStartTicks(List.of(0L, 6L))
          .setSegmentStartTimeBase(Fraction.newBuilder().setNumerator(1).setDenominator(1))
          .build();

  private final LiveWorkerConnectionRegistry registry =
      LiveWorkerConnectionRegistryFixture.defaultRegistry();
  private UUID workerSessionId;

  @BeforeEach
  void connectH264Worker() {
    workerSessionId = registry.register(WORKER_ID, h264Worker(), new DiscardingObserver());
  }

  static Stream<Arguments> encodesOnAMergedPlainGrid() {
    return Stream.of(
        Arguments.of(TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE, MERGED_PLAIN_GRID_TICKS),
        Arguments.of(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE, MERGED_PLAIN_GRID_TICKS));
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"TRANSCODE_MODE_REMUX", "TRANSCODE_MODE_AUDIO_TRANSCODE"})
  @DisplayName("Should end the stream session's recovery when a copy passes the segment cap")
  void shouldEndTheStreamSessionsRecoveryWhenACopyPassesTheSegmentCap(TranscodeMode mode) {
    var job = jobRunning(mode, VideoMode.VIDEO_MODE_COPY, EXACT_STARTS);

    failOverTheCap(job);

    assertThat(registry.terminalFailure(fromProto(job.getStreamSessionId())))
        .contains(TranscodeFailure.SEGMENT_CAP_EXCEEDED);
  }

  @ParameterizedTest
  @MethodSource("encodesOnAMergedPlainGrid")
  @DisplayName(
      "Should keep ordinary recovery when an encode on a plain grid with a merged last segment"
          + " passes the segment cap")
  void shouldKeepOrdinaryRecoveryWhenAnEncodeOnAMergedPlainGridPassesTheSegmentCap(
      TranscodeMode mode, TranscodeExecution execution) {
    var job = jobRunning(mode, VideoMode.VIDEO_MODE_ENCODE, execution);

    failOverTheCap(job);

    assertThat(registry.terminalFailure(fromProto(job.getStreamSessionId()))).isEmpty();
  }

  private VariantJob jobRunning(
      TranscodeMode mode, VideoMode videoMode, TranscodeExecution execution) {
    var job =
        VariantJob.newBuilder()
            .setStreamSessionId(toProto(UUID.randomUUID()))
            .setJobId(toProto(UUID.randomUUID()))
            .setJobAttemptId(toProto(UUID.randomUUID()))
            .setSource(
                MediaSourceRef.newBuilder()
                    .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
                    .setRelativeKey("movie.mkv"))
            .setVariant(VariantSpec.newBuilder().setVariantLabel("720p"))
            .setDecision(
                TranscodeDecision.newBuilder()
                    .setMode(mode)
                    .setVideo(
                        VideoDecision.newBuilder()
                            .setMode(videoMode)
                            .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264)))
            .setExecution(execution)
            .build();
    assertThat(registry.dispatch(job)).isPresent();
    return job;
  }

  private void failOverTheCap(VariantJob job) {
    registry.failJobAttempt(
        WORKER_ID,
        workerSessionId,
        JobAttemptFailed.newBuilder()
            .setJobAttemptId(job.getJobAttemptId())
            .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_SEGMENT_CAP_EXCEEDED)
            .build());
  }

  private static WorkerRegistration h264Worker() {
    return WorkerRegistration.newBuilder()
        .setWorker(
            WorkerIdentity.newBuilder()
                .setWorkerId(toProto(WORKER_ID))
                .setBootId(toProto(UUID.randomUUID())))
        .setCapabilities(
            WorkerCapabilities.newBuilder()
                .addSourceNamespaceIds(toProto(SOURCE_NAMESPACE_ID))
                .addVideoEncoders(
                    softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")))
        .setAvailableSlots(1)
        .build();
  }

  private static final class DiscardingObserver
      implements StreamObserver<EstablishWorkerSessionResponse> {
    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
      // The tests read the outcome from the registry, not from the commands sent to the worker.
    }

    @Override
    public void onError(Throwable throwable) {
      throw new AssertionError("The worker should remain connected", throwable);
    }

    @Override
    public void onCompleted() {
      throw new AssertionError("The worker should remain connected");
    }
  }
}
