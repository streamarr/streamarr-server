package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.keyframeVerifiedHardwareEncoder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.plaintextChannelBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.softwareEncoder;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.fromProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.streaming.BitstreamRewrite;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.WorkerVideoCopySupport;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.VideoCopyFeature;
import com.streamarr.transcode.v1.WorkerCapabilities;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Remote Transcode Executor Tests")
class RemoteTranscodeExecutorTest {

  private static final Path SOURCE_ROOT = Path.of("/media");

  @Test
  @DisplayName(
      "Should reject a pinned execution-target lookup when its decision or pinned encoder is"
          + " missing")
  void shouldRejectPinnedExecutionTargetLookupWhenItsDecisionOrPinnedEncoderIsMissing() {
    var executor = new RemoteTranscodeExecutor(server(), UUID.randomUUID(), SOURCE_ROOT);
    var decision = TranscodeDecision.builder().build();
    var pinnedEncoder = WorkerVerifiedEncoderFixture.libx264();

    assertThatNullPointerException()
        .isThrownBy(() -> executor.executionTargets(decision, null))
        .withMessageContaining("pinnedEncoder");
    assertThatNullPointerException()
        .isThrownBy(() -> executor.executionTargets(null, pinnedEncoder))
        .withMessageContaining("decision");
    assertThatNullPointerException()
        .isThrownBy(() -> executor.availableSlots(decision, null))
        .withMessageContaining("pinnedEncoder");
    assertThatNullPointerException()
        .isThrownBy(() -> executor.availableSlots(null, pinnedEncoder))
        .withMessageContaining("decision");
  }

  @Test
  @DisplayName(
      "Should report only the codec families that a connected worker encodes in hardware when the"
          + " worker also encodes another family in software")
  void
      shouldReportOnlyTheCodecFamiliesThatAConnectedWorkerEncodesInHardwareWhenTheWorkerAlsoEncodesAnotherFamilyInSoftware()
          throws Exception {
    try (var server = server()) {
      server.start();
      var channel = plaintextChannelBuilder(server.port(), WORKER_ID).build();
      try (var worker =
          LoopbackWorker.register(
              channel,
              WORKER_ID,
              keyframeVerifiedHardwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "av1_nvenc"),
              softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264"))) {
        assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();
        var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, SOURCE_ROOT);

        assertThat(executor.hardwareEncodableVideoCodecFamilies()).containsExactly("av1");
        assertThat(executor.verifiedHardwareVideoCodecFamilies()).containsExactly("av1");
      } finally {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  @DisplayName(
      "Should start an AV1 variant on the worker that encodes AV1 in hardware when a worker that"
          + " encodes AV1 in software registered first and also has a free slot")
  void
      shouldStartAnAv1VariantOnTheWorkerThatEncodesAv1InHardwareWhenAWorkerThatEncodesAv1InSoftwareRegisteredFirstAndAlsoHasAFreeSlot()
          throws Exception {
    var softwareWorkerId = WORKER_ID;
    var hardwareWorkerId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    try (var server = server()) {
      server.start();
      var softwareChannel = plaintextChannelBuilder(server.port(), softwareWorkerId).build();
      var hardwareChannel = plaintextChannelBuilder(server.port(), hardwareWorkerId).build();
      try (var softwareWorker =
              LoopbackWorker.register(
                  softwareChannel,
                  softwareWorkerId,
                  softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "libsvtav1"));
          var hardwareWorker =
              LoopbackWorker.register(
                  hardwareChannel,
                  hardwareWorkerId,
                  keyframeVerifiedHardwareEncoder(
                      VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "av1_nvenc"))) {
        assertThat(softwareWorker.nextResponse().hasSessionAccepted()).isTrue();
        assertThat(hardwareWorker.nextResponse().hasSessionAccepted()).isTrue();
        var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, SOURCE_ROOT);

        var handle = executor.start(av1EncodeRequest());

        assertThat(handle.encoder())
            .map(encoder -> encoder.backend().encoder())
            .contains("av1_nvenc");
        assertThat(hardwareWorker.nextJobs(1))
            .singleElement()
            .extracting(job -> fromProto(job.getJobAttemptId()))
            .isEqualTo(handle.attemptId());
      } finally {
        softwareChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        hardwareChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  // ADR 0036: a worker that does not list the planned output receives no copy that carries one.
  // Admission and capacity reclamation count the same workers that dispatch would use.
  @Test
  @DisplayName(
      "Should leave a copy's slots unbounded and offer targets only on workers that list the planned"
          + " output when a decision copies a PQ source")
  void
      shouldLeaveACopysSlotsUnboundedAndOfferTargetsOnlyOnWorkersThatListThePlannedOutputWhenCopyingAPqSource()
          throws Exception {
    var plannedOutputWorkerId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    try (var server = server()) {
      server.start();
      var olderChannel = plaintextChannelBuilder(server.port(), WORKER_ID).build();
      var plannedOutputChannel =
          plaintextChannelBuilder(server.port(), plannedOutputWorkerId).build();
      try (var olderWorker =
              LoopbackWorker.register(olderChannel, WORKER_ID, WorkerCapabilities.newBuilder());
          var plannedOutputWorker =
              LoopbackWorker.register(
                  plannedOutputChannel,
                  plannedOutputWorkerId,
                  WorkerCapabilities.newBuilder()
                      .addVideoCopyFeatures(VideoCopyFeature.VIDEO_COPY_FEATURE_PLANNED_OUTPUT))) {
        assertThat(olderWorker.nextResponse().hasSessionAccepted()).isTrue();
        assertThat(plannedOutputWorker.nextResponse().hasSessionAccepted()).isTrue();
        var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, SOURCE_ROOT);
        var pqCopy = copyDecision(CompatibleBase.PQ);
        var sdrCopy = copyDecision(CompatibleBase.SDR);

        assertThat(executor.availableSlots(pqCopy)).isEqualTo(TranscodeExecutor.UNBOUNDED_SLOTS);
        assertThat(executor.executionTargets(pqCopy)).hasSize(1);
        assertThat(executor.availableSlots(sdrCopy)).isEqualTo(TranscodeExecutor.UNBOUNDED_SLOTS);
        assertThat(executor.executionTargets(sdrCopy))
            .hasSize(2)
            .containsAll(executor.executionTargets(pqCopy));
      } finally {
        olderChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        plannedOutputChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  // ADR 0036: the decision plans a copy that needs the planned output or a rewrite only when a
  // connected worker lists them, so the executor reports what each worker lists.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName(
      "Should report each connected worker's planned output and rewrites when the workers register")
  void shouldReportEachConnectedWorkersPlannedOutputAndRewritesWhenTheWorkersRegister()
      throws Exception {
    var strippingWorkerId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    try (var server = server()) {
      server.start();
      var olderChannel = plaintextChannelBuilder(server.port(), WORKER_ID).build();
      var strippingChannel = plaintextChannelBuilder(server.port(), strippingWorkerId).build();
      try (var olderWorker =
              LoopbackWorker.register(olderChannel, WORKER_ID, WorkerCapabilities.newBuilder());
          var strippingWorker =
              LoopbackWorker.register(
                  strippingChannel,
                  strippingWorkerId,
                  WorkerCapabilities.newBuilder()
                      .addVideoCopyFeatures(VideoCopyFeature.VIDEO_COPY_FEATURE_PLANNED_OUTPUT)
                      .addBitstreamRewrites(
                          com.streamarr.transcode.v1.BitstreamRewrite
                              .BITSTREAM_REWRITE_STRIP_HDR10_PLUS)
                      .addBitstreamRewrites(
                          com.streamarr.transcode.v1.BitstreamRewrite
                              .BITSTREAM_REWRITE_CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1))) {
        assertThat(olderWorker.nextResponse().hasSessionAccepted()).isTrue();
        assertThat(strippingWorker.nextResponse().hasSessionAccepted()).isTrue();
        var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, SOURCE_ROOT);

        assertThat(executor.videoCopySupportByWorker())
            .containsExactlyInAnyOrder(
                WorkerVideoCopySupport.builder()
                    .plannedOutput(false)
                    .bitstreamRewrites(Set.of())
                    .build(),
                WorkerVideoCopySupport.builder()
                    .plannedOutput(true)
                    .bitstreamRewrites(
                        Set.of(
                            BitstreamRewrite.STRIP_HDR10_PLUS,
                            BitstreamRewrite.CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1))
                    .build());
      } finally {
        olderChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        strippingChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  private static TranscodeDecision copyDecision(CompatibleBase base) {
    return StreamSessionFixture.remuxDecisionBuilder()
        .videoSource(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .dynamicRange(VideoPlaybackFactsFixture.rangeWithoutEnhancement(base))
                    .build()))
        .build();
  }

  private static TranscodeRequest av1EncodeRequest() {
    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .sourcePath(SOURCE_ROOT.resolve("movie.mkv"))
        .framerate(OptionalDouble.of(24))
        .transcodeDecision(StreamSessionFixture.fullTranscodeDecision("av1"))
        .encoderPreset(EncoderPreset.FASTER)
        .build();
  }

  private static WorkerSessionServer server() {
    return WorkerSessionServer.builder()
        .eventPublisher(new CapturingEventPublisher())
        .configuration(serverConfigurationBuilder().build())
        .segmentStore(new FakeSegmentStore())
        .meterRegistry(new SimpleMeterRegistry())
        .build();
  }
}
