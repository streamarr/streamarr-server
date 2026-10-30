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

import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.transcode.v1.VideoCodecFamily;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.OptionalDouble;
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
  @DisplayName("Should reject a pinned execution-target lookup when its pinned encoder is missing")
  void shouldRejectPinnedExecutionTargetLookupWhenItsPinnedEncoderIsMissing() {
    var executor = new RemoteTranscodeExecutor(server(), UUID.randomUUID(), SOURCE_ROOT);

    assertThatNullPointerException()
        .isThrownBy(() -> executor.executionTargets((WorkerVerifiedEncoder) null))
        .withMessageContaining("pinnedEncoder");
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
