package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.keyframeVerifiedHardwareEncoder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.softwareEncoder;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.fromProto;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.server.services.streaming.events.HardwareEncoderFailedEvent;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.JobAttemptFailure;
import com.streamarr.transcode.v1.JobAttemptStarted;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.TranscodeDecision;
import com.streamarr.transcode.v1.TranscodeMode;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VariantSpec;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.VideoDecision;
import com.streamarr.transcode.v1.VideoEncodersChanged;
import com.streamarr.transcode.v1.VideoMode;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerVerifiedVideoEncoder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// ADR 0044: a hardware encoder that fails during a stream sends the requested variant to the
// software encoder of the same codec family, on the failed worker first.
@Tag("UnitTest")
@DisplayName("Hardware Encoder Failure Registry Tests")
class HardwareEncoderFailureRegistryTest {

  private static final WorkerVerifiedVideoEncoder H264_NVENC =
      keyframeVerifiedHardwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "h264_nvenc");
  private static final WorkerVerifiedVideoEncoder LIBX264 =
      softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264").toBuilder()
          .setKeyframeVerified(true)
          .build();
  private static final WorkerVerifiedVideoEncoder LIBSVTAV1 =
      softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "libsvtav1");
  private static final WorkerVerifiedEncoder PINNED_LIBX264 =
      WorkerVerifiedEncoder.builder()
          .backend(
              EncoderBackend.builder()
                  .codecFamily("h264")
                  .encoder("libx264")
                  .kind(EncoderBackend.Kind.SOFTWARE)
                  .build())
          .keyframeVerified(true)
          .build();

  private static final WorkerVerifiedEncoder PINNED_NVENC =
      WorkerVerifiedEncoder.builder()
          .backend(
              EncoderBackend.builder()
                  .codecFamily("h264")
                  .encoder("h264_nvenc")
                  .kind(EncoderBackend.Kind.HARDWARE)
                  .build())
          .keyframeVerified(true)
          .build();

  private final CapturingEventPublisher events = new CapturingEventPublisher();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final LiveWorkerConnectionRegistry registry =
      LiveWorkerConnectionRegistry.builder()
          .eventPublisher(events)
          .configuration(WorkerSessionServerConfiguration.builder().build())
          .meterRegistry(meterRegistry)
          .heldRequests(new HeldSegmentRequests(new SimpleMeterRegistry()))
          .build();

  @Test
  @DisplayName(
      "Should run a software encode on the software encoder of a worker that offers a hardware"
          + " encoder for the family")
  void shouldRunASoftwareEncodeOnTheSoftwareEncoderOfAWorkerThatOffersAHardwareEncoder() {
    var workerId = UUID.randomUUID();
    var responses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    registry.register(workerId, nvencWorker(workerId, 2), collecting(responses));

    var dispatched = registry.dispatch(h264Encode(true));

    assertThat(dispatched).map(VariantDispatch::encoder).contains(Optional.of(PINNED_LIBX264));
    assertThat(
            registry.availableSlots(
                SOURCE_NAMESPACE_ID, h264Encode(true).getDecision(), PINNED_LIBX264))
        .isOne();
  }

  @Test
  @DisplayName(
      "Should refuse a software encode when the worker lists no software encoder for the family")
  void shouldRefuseASoftwareEncodeWhenTheWorkerListsNoSoftwareEncoderForTheFamily() {
    var workerId = UUID.randomUUID();
    var registration = nvencWorker(workerId, 1).toBuilder();
    registration.getCapabilitiesBuilder().clearSoftwareVideoEncoders();
    registry.register(workerId, registration.build(), collecting(new CopyOnWriteArrayList<>()));

    assertThat(registry.dispatch(h264Encode(true))).isEmpty();
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID, h264Encode(true).getDecision()))
        .isZero();
  }

  @Test
  @DisplayName(
      "Should publish the hardware encoder failure and keep the stream session going when a"
          + " hardware encode fails")
  void
      shouldPublishTheHardwareEncoderFailureAndKeepTheStreamSessionGoingWhenAHardwareEncodeFails() {
    var workerId = UUID.randomUUID();
    var workerSessionId =
        registry.register(
            workerId, nvencWorker(workerId, 1), collecting(new CopyOnWriteArrayList<>()));
    var job = h264Encode(false);
    registry.dispatch(job);

    registry.failJobAttempt(workerId, workerSessionId, hardwareFailure(job));

    assertThat(events.getEventsOfType(HardwareEncoderFailedEvent.class))
        .containsExactly(
            HardwareEncoderFailedEvent.builder()
                .streamSessionId(fromProto(job.getStreamSessionId()))
                .jobAttemptId(fromProto(job.getJobAttemptId()))
                .variantLabel("720p")
                .build());
    assertThat(registry.terminalFailure(fromProto(job.getStreamSessionId()))).isEmpty();
    assertThat(registry.dispatch(h264Encode(false))).isPresent();
  }

  @Test
  @DisplayName("Should publish nothing when a software encode reports a hardware encoder failure")
  void shouldPublishNothingWhenASoftwareEncodeReportsAHardwareEncoderFailure() {
    var workerId = UUID.randomUUID();
    var workerSessionId =
        registry.register(
            workerId, nvencWorker(workerId, 1), collecting(new CopyOnWriteArrayList<>()));
    var job = h264Encode(true);
    registry.dispatch(job);

    registry.failJobAttempt(workerId, workerSessionId, hardwareFailure(job));

    assertThat(events.getEventsOfType(HardwareEncoderFailedEvent.class)).isEmpty();
    assertThat(registry.hasHardwareEncoderFailed(fromProto(job.getStreamSessionId()), "720p"))
        .isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  @DisplayName(
      "Should send the software continuation to the worker whose hardware encoder failed when"
          + " both workers have room")
  void shouldSendTheSoftwareContinuationToTheWorkerWhoseHardwareEncoderFailed(int failing) {
    var workerIds = List.of(UUID.randomUUID(), UUID.randomUUID());
    var responses =
        List.of(
            new CopyOnWriteArrayList<EstablishWorkerSessionResponse>(),
            new CopyOnWriteArrayList<EstablishWorkerSessionResponse>());
    var sessionIds =
        List.of(
            registry.register(
                workerIds.get(0), nvencWorker(workerIds.get(0), 2), collecting(responses.get(0))),
            registry.register(
                workerIds.get(1), nvencWorker(workerIds.get(1), 2), collecting(responses.get(1))));
    var failed = h264Encode(false);
    registry.dispatchTo(new ExecutionTargetId(sessionIds.get(failing).toString()), failed);
    registry.failJobAttempt(
        workerIds.get(failing), sessionIds.get(failing), hardwareFailure(failed));
    responses.forEach(List::clear);

    registry.dispatch(h264Encode(true));

    assertThat(responses.get(failing)).anyMatch(EstablishWorkerSessionResponse::hasStartVariant);
    assertThat(responses.get(1 - failing))
        .noneMatch(EstablishWorkerSessionResponse::hasStartVariant);
  }

  @Test
  @DisplayName(
      "Should stop offering the hardware encoder and offer it again as the worker's offered"
          + " encoders change")
  void shouldStopOfferingTheHardwareEncoderAndOfferItAgainAsTheWorkersOfferedEncodersChange() {
    var workerId = UUID.randomUUID();
    var workerSessionId =
        registry.register(
            workerId, nvencWorker(workerId, 1), collecting(new CopyOnWriteArrayList<>()));

    var withdrawn =
        registry.changeVideoEncoders(
            workerId,
            workerSessionId,
            VideoEncodersChanged.newBuilder().addVideoEncoders(LIBX264).build());

    assertThat(withdrawn).isTrue();
    assertThat(registry.hardwareEncodableVideoCodecFamilies(SOURCE_NAMESPACE_ID)).isEmpty();
    assertThat(registry.encodableVideoCodecFamilies(SOURCE_NAMESPACE_ID)).containsExactly("h264");

    registry.changeVideoEncoders(
        workerId,
        workerSessionId,
        VideoEncodersChanged.newBuilder().addVideoEncoders(H264_NVENC).build());

    assertThat(registry.hardwareEncodableVideoCodecFamilies(SOURCE_NAMESPACE_ID))
        .containsExactly("h264");
  }

  // No code runs between the replacement's removal and its additions, so no test can park a reader
  // there; the readers race many replacements instead, and every one must see the kept encoder.
  @Test
  @DisplayName(
      "Should keep offering an encoder that every replacement keeps while another family's encoder"
          + " changes")
  void shouldKeepOfferingAnEncoderThatEveryReplacementKeepsWhileAnotherFamilysEncoderChanges()
      throws Exception {
    var workerId = UUID.randomUUID();
    var workerSessionId =
        registry.register(
            workerId, nvencWorker(workerId, 1), collecting(new CopyOnWriteArrayList<>()));
    var changes =
        List.of(
            VideoEncodersChanged.newBuilder()
                .addVideoEncoders(H264_NVENC)
                .addVideoEncoders(LIBSVTAV1)
                .build(),
            VideoEncodersChanged.newBuilder().addVideoEncoders(H264_NVENC).build());
    var reading = new CountDownLatch(1);
    var reads = 0;
    var readsWithoutH264 = 0;

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var replacements =
          executor.submit(
              () -> {
                assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();
                for (var i = 0; i < 20_000; i++) {
                  registry.changeVideoEncoders(workerId, workerSessionId, changes.get(i % 2));
                }

                return null;
              });
      while (!replacements.isDone()) {
        if (!registry.verifiedHardwareVideoCodecFamilies(SOURCE_NAMESPACE_ID).contains("h264")) {
          readsWithoutH264++;
        }

        reads++;
        reading.countDown();
      }

      replacements.get(30, TimeUnit.SECONDS);
    }

    assertThat(reads).isPositive();
    assertThat(readsWithoutH264).isZero();
  }

  @Test
  @DisplayName(
      "Should keep the offered encoders when a change lists one codec family twice or names an"
          + " ended worker session")
  void shouldKeepTheOfferedEncodersWhenAChangeListsOneFamilyTwiceOrNamesAnEndedSession() {
    var workerId = UUID.randomUUID();
    var workerSessionId =
        registry.register(
            workerId, nvencWorker(workerId, 1), collecting(new CopyOnWriteArrayList<>()));

    var twice =
        registry.changeVideoEncoders(
            workerId,
            workerSessionId,
            VideoEncodersChanged.newBuilder()
                .addVideoEncoders(LIBX264)
                .addVideoEncoders(H264_NVENC)
                .build());
    var ended =
        registry.changeVideoEncoders(
            workerId,
            UUID.randomUUID(),
            VideoEncodersChanged.newBuilder().addVideoEncoders(LIBX264).build());

    assertThat(twice).isFalse();
    assertThat(ended).isFalse();
    assertThat(registry.hardwareEncodableVideoCodecFamilies(SOURCE_NAMESPACE_ID))
        .containsExactly("h264");
  }

  @Test
  @DisplayName(
      "Should keep a variant pinned to the software encoder on the worker once its hardware"
          + " encoder is offered again")
  void shouldKeepAVariantPinnedToTheSoftwareEncoderOnceTheHardwareEncoderIsOfferedAgain() {
    var workerId = UUID.randomUUID();
    var responses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    var workerSessionId =
        registry.register(workerId, nvencWorker(workerId, 2), collecting(responses));
    registry.changeVideoEncoders(
        workerId,
        workerSessionId,
        VideoEncodersChanged.newBuilder().addVideoEncoders(LIBX264).build());
    var admitted = registry.dispatch(h264Encode(false));
    registry.changeVideoEncoders(
        workerId,
        workerSessionId,
        VideoEncodersChanged.newBuilder().addVideoEncoders(H264_NVENC).build());
    responses.clear();

    var seek = registry.dispatch(h264Encode(false), PINNED_LIBX264);

    assertThat(admitted).map(VariantDispatch::encoder).contains(Optional.of(PINNED_LIBX264));
    assertThat(seek).map(VariantDispatch::encoder).contains(Optional.of(PINNED_LIBX264));
    assertThat(responses)
        .singleElement()
        .extracting(response -> response.getStartVariant().getJob().getDecision().getVideo())
        .extracting(VideoDecision::getSoftwareEncoder)
        .isEqualTo(true);
  }

  @Test
  @DisplayName(
      "Should ask for the software encoder when the worker counts it, so a hardware encoder that"
          + " returns before the worker reads the job does not run it")
  void shouldAskForTheSoftwareEncoderWhenTheWorkerCountsIt() {
    var workerId = UUID.randomUUID();
    var responses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    var workerSessionId =
        registry.register(workerId, nvencWorker(workerId, 1), collecting(responses));
    registry.changeVideoEncoders(
        workerId,
        workerSessionId,
        VideoEncodersChanged.newBuilder().addVideoEncoders(LIBX264).build());
    responses.clear();

    registry.dispatch(h264Encode(false));

    assertThat(responses)
        .singleElement()
        .extracting(response -> response.getStartVariant().getJob().getDecision().getVideo())
        .extracting(VideoDecision::getSoftwareEncoder)
        .isEqualTo(true);
  }

  @Test
  @DisplayName(
      "Should name the encoder that the start command was sent for when the worker's encoders"
          + " change once it is sent")
  void shouldNameTheEncoderThatTheStartCommandWasSentForWhenTheEncodersChangeOnceItIsSent() {
    var workerId = UUID.randomUUID();
    var onStart = new AtomicReference<Runnable>(() -> {});
    var workerSessionId =
        registry.register(workerId, nvencWorker(workerId, 1), runningOnStart(onStart));
    onStart.set(
        () ->
            registry.changeVideoEncoders(
                workerId,
                workerSessionId,
                VideoEncodersChanged.newBuilder().addVideoEncoders(LIBX264).build()));

    var dispatched = registry.dispatch(h264Encode(false));

    assertThat(dispatched).map(VariantDispatch::encoder).contains(Optional.of(PINNED_NVENC));
  }

  @Test
  @DisplayName(
      "Should hold the variant's hardware encoder failure until its stream session is destroyed")
  void shouldHoldTheVariantsHardwareEncoderFailureUntilItsStreamSessionIsDestroyed() {
    var workerId = UUID.randomUUID();
    var workerSessionId =
        registry.register(
            workerId, nvencWorker(workerId, 1), collecting(new CopyOnWriteArrayList<>()));
    var job = h264Encode(false);
    var streamSessionId = fromProto(job.getStreamSessionId());
    registry.dispatch(job);

    registry.failJobAttempt(workerId, workerSessionId, hardwareFailure(job));

    assertThat(registry.hasHardwareEncoderFailed(streamSessionId, "720p")).isTrue();
    assertThat(registry.hasHardwareEncoderFailed(streamSessionId, "480p")).isFalse();
    registry.destroyStreamSession(streamSessionId);
    assertThat(registry.hasHardwareEncoderFailed(streamSessionId, "720p")).isFalse();
  }

  // The worker withdrew NVENC before it read a job sent for NVENC without the software flag, so it
  // ran the job on its software encoder, as the contract lets it (ADR 0044).
  @Test
  @DisplayName(
      "Should mark and publish the hardware encoder failure when a worker that withdrew its"
          + " hardware encoder starts a job sent for it on the software encoder")
  void shouldMarkAndPublishTheHardwareEncoderFailureWhenAWithdrawnHardwareJobStartsInSoftware() {
    var workerId = UUID.randomUUID();
    var workerSessionId =
        registry.register(
            workerId, nvencWorker(workerId, 2), collecting(new CopyOnWriteArrayList<>()));
    var job = h264Encode(false);
    var streamSessionId = fromProto(job.getStreamSessionId());
    registry.dispatch(job);
    registry.changeVideoEncoders(
        workerId,
        workerSessionId,
        VideoEncodersChanged.newBuilder().addVideoEncoders(LIBX264).build());

    registry.checkReportedEncoderBackend(workerId, workerSessionId, startedOn(job, LIBX264));

    assertThat(registry.hasHardwareEncoderFailed(streamSessionId, "720p")).isTrue();
    assertThat(registry.isRunning(streamSessionId, "720p")).isFalse();
    assertThat(events.getEventsOfType(HardwareEncoderFailedEvent.class))
        .containsExactly(
            HardwareEncoderFailedEvent.builder()
                .streamSessionId(streamSessionId)
                .jobAttemptId(fromProto(job.getJobAttemptId()))
                .variantLabel("720p")
                .build());
    assertThat(
            meterRegistry.get("streamarr.streaming.encoder_backend_mismatches").counter().count())
        .isZero();
  }

  private static JobAttemptStarted startedOn(VariantJob job, WorkerVerifiedVideoEncoder encoder) {
    return JobAttemptStarted.newBuilder()
        .setJobAttemptId(job.getJobAttemptId())
        .setEncoderBackend(encoder.getBackend())
        .build();
  }

  private static WorkerRegistration nvencWorker(UUID workerId, int slots) {
    return WorkerRegistration.newBuilder()
        .setWorker(
            WorkerIdentity.newBuilder()
                .setWorkerId(toProto(workerId))
                .setBootId(toProto(UUID.randomUUID())))
        .setCapabilities(
            WorkerCapabilities.newBuilder()
                .addSourceNamespaceIds(toProto(SOURCE_NAMESPACE_ID))
                .addVideoEncoders(H264_NVENC)
                .addSoftwareVideoEncoders(LIBX264))
        .setAvailableSlots(slots)
        .build();
  }

  private static VariantJob h264Encode(boolean softwareEncoder) {
    return VariantJob.newBuilder()
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
                .setMode(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE)
                .setVideo(
                    VideoDecision.newBuilder()
                        .setMode(VideoMode.VIDEO_MODE_ENCODE)
                        .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264)
                        .setSoftwareEncoder(softwareEncoder)))
        .build();
  }

  private static JobAttemptFailed hardwareFailure(VariantJob job) {
    return JobAttemptFailed.newBuilder()
        .setJobAttemptId(job.getJobAttemptId())
        .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_HARDWARE_ENCODER_FAILED)
        .build();
  }

  // Runs the action as the start command reaches the worker, before the dispatch returns.
  private static StreamObserver<EstablishWorkerSessionResponse> runningOnStart(
      AtomicReference<Runnable> action) {
    return new StreamObserver<>() {
      @Override
      public void onNext(EstablishWorkerSessionResponse value) {
        if (value.hasStartVariant()) {
          action.get().run();
        }
      }

      @Override
      public void onError(Throwable throwable) {
        throw new AssertionError("The worker should remain connected", throwable);
      }

      @Override
      public void onCompleted() {
        throw new AssertionError("The worker should remain connected");
      }
    };
  }

  private static StreamObserver<EstablishWorkerSessionResponse> collecting(
      List<EstablishWorkerSessionResponse> responses) {
    return new StreamObserver<>() {
      @Override
      public void onNext(EstablishWorkerSessionResponse value) {
        responses.add(value);
      }

      @Override
      public void onError(Throwable throwable) {
        throw new AssertionError("The worker should remain connected", throwable);
      }

      @Override
      public void onCompleted() {
        throw new AssertionError("The worker should remain connected");
      }
    };
  }
}
