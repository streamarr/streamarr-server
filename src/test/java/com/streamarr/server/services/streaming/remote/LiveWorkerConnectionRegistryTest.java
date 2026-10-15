package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.hardwareEncoder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.keyframeVerifiedHardwareEncoder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.softwareEncoder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.streamCopyDecision;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.fromProto;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.JobAttemptStarted;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.TranscodeDecision;
import com.streamarr.transcode.v1.TranscodeMode;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VariantSpec;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerVerifiedVideoEncoder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Live Worker Connection Registry Tests")
class LiveWorkerConnectionRegistryTest {

  private static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  @Test
  @DisplayName("Should reject a registry when its configuration is missing")
  void shouldRejectRegistryWhenItsConfigurationIsMissing() {
    var meterRegistry = new SimpleMeterRegistry();
    var heldRequests = new HeldSegmentRequests(new SimpleMeterRegistry());

    assertThatNullPointerException()
        .isThrownBy(() -> new LiveWorkerConnectionRegistry(null, meterRegistry, heldRequests))
        .withMessageContaining("configuration");
  }

  @Test
  @DisplayName("Should reject a registry when its meter registry is missing")
  void shouldRejectRegistryWhenItsMeterRegistryIsMissing() {
    var configuration = WorkerSessionServerConfiguration.builder().build();
    var heldRequests = new HeldSegmentRequests(new SimpleMeterRegistry());

    assertThatNullPointerException()
        .isThrownBy(() -> new LiveWorkerConnectionRegistry(configuration, null, heldRequests))
        .withMessageContaining("meterRegistry");
  }

  @Test
  @DisplayName("Should reject a registry when its held segment requests are missing")
  void shouldRejectRegistryWhenItsHeldSegmentRequestsAreMissing() {
    var configuration = WorkerSessionServerConfiguration.builder().build();
    var meterRegistry = new SimpleMeterRegistry();

    assertThatNullPointerException()
        .isThrownBy(() -> new LiveWorkerConnectionRegistry(configuration, meterRegistry, null))
        .withMessageContaining("heldRequests");
  }

  @Test
  @DisplayName("Should wake the requests held on a variant when a segment of it is published")
  void shouldWakeRequestsHeldOnVariantWhenSegmentOfItIsPublished() throws Exception {
    var held = HeldVariant.dispatch();

    held.publish(() -> SegmentPublication.PUBLISHED);

    assertThat(held.wake().await(Duration.ZERO)).isTrue();
  }

  @Test
  @DisplayName(
      "Should wake the requests held on a variant when its job attempt's initialization segment is refused")
  void shouldWakeRequestsHeldOnVariantWhenItsJobAttemptsInitializationSegmentIsRefused()
      throws Exception {
    var held = HeldVariant.dispatch();

    held.publish(() -> SegmentPublication.INITIALIZATION_SEGMENT_DIFFERS);

    assertThat(held.wake().await(Duration.ZERO)).isTrue();
    assertThat(held.isRunning()).isFalse();
  }

  @Test
  @DisplayName(
      "Should leave the requests held on a variant waiting when an upload from another worker session is refused")
  void shouldLeaveRequestsHeldOnVariantWaitingWhenUploadFromAnotherWorkerSessionIsRefused()
      throws Exception {
    var held = HeldVariant.dispatch();
    var otherSessionUpload = uploadMetadata(UUID.randomUUID(), held.worker(), held.job());

    var outcome =
        held.registry()
            .publishIfAuthorized(WORKER_ID, otherSessionUpload, () -> SegmentPublication.PUBLISHED);

    assertThat(outcome).isEmpty();
    assertThat(held.wake().await(Duration.ZERO)).isFalse();
  }

  @Test
  @DisplayName(
      "Should wake the requests held on a variant once its job attempt ends and no longer runs")
  void shouldWakeRequestsHeldOnVariantOnceItsJobAttemptEndsAndNoLongerRuns() throws Exception {
    var held = HeldVariant.dispatch();

    held.registry()
        .releaseJobAttempt(
            WORKER_ID, held.workerSessionId(), fromProto(held.job().getJobAttemptId()));

    assertThat(held.wake().await(Duration.ZERO)).isTrue();
    assertThat(held.isRunning()).isFalse();
  }

  @Test
  @DisplayName("Should wake the requests held on a variant when the server stops its job attempt")
  void shouldWakeRequestsHeldOnVariantWhenServerStopsItsJobAttempt() throws Exception {
    var held = HeldVariant.dispatch();

    held.registry().stopVariant(held.sessionId(), held.job().getVariant().getVariantLabel());

    assertThat(held.wake().await(Duration.ZERO)).isTrue();
    assertThat(held.isRunning()).isFalse();
  }

  @Test
  @DisplayName("Should wake the requests held on a variant when its worker disconnects")
  void shouldWakeRequestsHeldOnVariantWhenItsWorkerDisconnects() throws Exception {
    var held = HeldVariant.dispatch();

    held.registry().disconnect(WORKER_ID, held.workerSessionId());

    assertThat(held.wake().await(Duration.ZERO)).isTrue();
    assertThat(held.isRunning()).isFalse();
  }

  // A variant job running on a registered worker, with the wake that its held requests took.
  @Builder
  private record HeldVariant(
      LiveWorkerConnectionRegistry registry,
      UUID workerSessionId,
      WorkerIdentity worker,
      VariantJob job,
      HeldSegmentRequests.Wake wake) {

    private static HeldVariant dispatch() {
      var heldRequests = new HeldSegmentRequests(new SimpleMeterRegistry());
      var registry = LiveWorkerConnectionRegistryFixture.registryWaking(heldRequests);
      var workerRegistration = registration();
      var workerSessionId =
          registry.register(WORKER_ID, workerRegistration, new CancellableObserver());
      var job = variantJob();
      assertThat(registry.dispatch(job)).isPresent();
      return HeldVariant.builder()
          .registry(registry)
          .workerSessionId(workerSessionId)
          .worker(workerRegistration.getWorker())
          .job(job)
          .wake(
              heldRequests.nextWake(
                  fromProto(job.getStreamSessionId()), job.getVariant().getVariantLabel()))
          .build();
    }

    private UUID sessionId() {
      return fromProto(job.getStreamSessionId());
    }

    private boolean isRunning() {
      return registry.isRunning(sessionId(), job.getVariant().getVariantLabel());
    }

    private void publish(Supplier<SegmentPublication> publication) {
      assertThat(
              registry.publishIfAuthorized(
                  WORKER_ID, uploadMetadata(workerSessionId, worker, job), publication))
          .isPresent();
    }
  }

  @Test
  @DisplayName("Should expose the connection when acknowledgement is in progress")
  void shouldExposeConnectionWhenAcknowledgementIsInProgress() throws Exception {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var observer = new BlockingAcceptanceObserver();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var registration =
          executor.submit(() -> registry.register(WORKER_ID, registration(), observer));
      try {
        assertThat(observer.acceptanceReached.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(registry.hasConnectedWorker(SOURCE_NAMESPACE_ID)).isTrue();
      } finally {
        observer.acceptanceRelease.countDown();
      }
      registration.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  @DisplayName("Should remove the connection when its initial acknowledgement fails")
  void shouldRemoveConnectionWhenInitialAcknowledgementFails() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var workerRegistration = registration();
    var observer = new RejectingAcceptanceObserver();

    assertThatThrownBy(() -> registry.register(WORKER_ID, workerRegistration, observer))
        .isInstanceOf(RuntimeException.class);

    assertThat(registry.hasConnectedWorker(SOURCE_NAMESPACE_ID)).isFalse();
    assertThat(registry.dispatch(variantJob())).isEmpty();
  }

  @Test
  @DisplayName("Should restore the previous connection when replacement acknowledgement fails")
  void shouldRestorePreviousConnectionWhenReplacementAcknowledgementFails() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var originalResponses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    registry.register(WORKER_ID, registration(), collecting(originalResponses));
    var workerRegistration = registration();
    var observer = new RejectingAcceptanceObserver();

    assertThatThrownBy(() -> registry.register(WORKER_ID, workerRegistration, observer))
        .isInstanceOf(RuntimeException.class);

    assertThat(registry.hasConnectedWorker(SOURCE_NAMESPACE_ID)).isTrue();
    assertThat(registry.dispatch(variantJob())).isPresent();
    assertThat(originalResponses)
        .extracting(EstablishWorkerSessionResponse::getCommandCase)
        .containsExactly(
            EstablishWorkerSessionResponse.CommandCase.SESSION_ACCEPTED,
            EstablishWorkerSessionResponse.CommandCase.START_VARIANT);
  }

  @Test
  @DisplayName(
      "Should accept a replacement worker before dispatching work to it when managing a connection")
  void shouldAcceptReplacementWorkerBeforeDispatchingWorkToItWhenManagingConnection()
      throws Exception {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var replacementClosing = new CountDownLatch(1);
    var continueReplacement = new CountDownLatch(1);
    var original = new BlockingCloseObserver(replacementClosing, continueReplacement);
    registry.register(WORKER_ID, registration(), original);
    var replacementResponses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var replacement =
          executor.submit(
              () -> registry.register(WORKER_ID, registration(), collecting(replacementResponses)));
      assertThat(replacementClosing.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(replacement).isNotDone();

      assertThat(registry.dispatch(variantJob())).isPresent();
      continueReplacement.countDown();
      replacement.get(5, TimeUnit.SECONDS);
    } finally {
      continueReplacement.countDown();
    }

    assertThat(replacementResponses)
        .extracting(EstablishWorkerSessionResponse::getCommandCase)
        .containsExactly(
            EstablishWorkerSessionResponse.CommandCase.SESSION_ACCEPTED,
            EstablishWorkerSessionResponse.CommandCase.START_VARIANT);
  }

  @Test
  @DisplayName("Should report dispatch failure when a disconnect completes mid-dispatch")
  void shouldReportDispatchFailureWhenDisconnectCompletesMidDispatch() throws Exception {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var observer = new GatedDispatchObserver();
    var workerSessionId = registry.register(WORKER_ID, registration(), observer);
    var job = variantJob();
    var streamSessionId = fromProto(job.getStreamSessionId());

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var dispatching = executor.submit(() -> registry.dispatch(job));
      // The dispatcher is inside tryDispatch, mid-send, before its active-attempt put.
      assertThat(observer.dispatchReached.await(5, TimeUnit.SECONDS)).isTrue();

      registry.disconnect(WORKER_ID, workerSessionId);
      observer.dispatchRelease.countDown();

      assertThat(dispatching.get(5, TimeUnit.SECONDS)).isEmpty();
      assertThat(registry.isRunning(streamSessionId, job.getVariant().getVariantLabel())).isFalse();
    }
  }

  @Test
  @DisplayName(
      "Should report dispatch failure when the worker call is cancelled but not yet reaped")
  void shouldReportDispatchFailureWhenWorkerCallIsCancelledButNotYetReaped() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var observer = new CancellableObserver();
    registry.register(WORKER_ID, registration(), observer);
    observer.cancel();
    var job = variantJob();

    var dispatched = registry.dispatch(job);

    assertThat(dispatched).isEmpty();
    assertThat(
            registry.isRunning(
                fromProto(job.getStreamSessionId()), job.getVariant().getVariantLabel()))
        .isFalse();
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should report health and capacity only for the requested source namespace when managing a connection")
  void shouldReportHealthAndCapacityOnlyForRequestedSourceNamespaceWhenManagingConnection() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    registry.register(WORKER_ID, registration(), new CancellableObserver());
    var unavailableNamespace = UUID.randomUUID();

    assertThat(registry.hasConnectedWorker(SOURCE_NAMESPACE_ID)).isTrue();
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
    assertThat(registry.eligibleWorkers(SOURCE_NAMESPACE_ID, streamCopyDecision())).hasSize(1);
    assertThat(registry.hasConnectedWorker(unavailableNamespace)).isFalse();
    assertThat(registry.availableSlots(unavailableNamespace)).isZero();
    assertThat(registry.eligibleWorkers(unavailableNamespace, streamCopyDecision())).isEmpty();
  }

  @Test
  @DisplayName("Should total available capacity across distinct workers when managing a connection")
  void shouldTotalAvailableCapacityAcrossDistinctWorkersWhenManagingConnection() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var secondWorkerId = UUID.randomUUID();
    registry.register(WORKER_ID, registration(WORKER_ID, 1), new CancellableObserver());
    registry.register(secondWorkerId, registration(secondWorkerId, 2), new CancellableObserver());

    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(3);
    assertThat(registry.eligibleWorkers(SOURCE_NAMESPACE_ID, streamCopyDecision())).hasSize(2);

    assertThat(registry.dispatch(variantJob())).isPresent();
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Should dispatch an encode only to a worker that advertises an encoder for its codec family")
  void shouldDispatchAnEncodeOnlyToAWorkerThatAdvertisesAnEncoderForItsCodecFamily() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var h264OnlyResponses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    var av1Responses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    var av1WorkerId = UUID.randomUUID();
    registry.register(
        WORKER_ID,
        registration(
            WORKER_ID, softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")),
        collecting(h264OnlyResponses));
    var av1Job = encodeJob("av1");

    assertThat(registry.dispatch(av1Job)).isEmpty();

    registry.register(
        av1WorkerId,
        registration(
            av1WorkerId, softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "libsvtav1")),
        collecting(av1Responses));

    assertThat(registry.dispatch(av1Job)).isPresent();
    assertThat(h264OnlyResponses)
        .extracting(EstablishWorkerSessionResponse::getCommandCase)
        .containsExactly(EstablishWorkerSessionResponse.CommandCase.SESSION_ACCEPTED);
    assertThat(av1Responses)
        .filteredOn(EstablishWorkerSessionResponse::hasStartVariant)
        .singleElement()
        .extracting(response -> response.getStartVariant().getJob())
        .isEqualTo(av1Job);
  }

  @Test
  @DisplayName("Should dispatch a stream copy to a worker that advertises no encoder")
  void shouldDispatchAStreamCopyToAWorkerThatAdvertisesNoEncoder() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    registry.register(WORKER_ID, registration(), new CancellableObserver());
    var copyJob =
        variantJob().toBuilder()
            .setDecision(
                TranscodeDecision.newBuilder()
                    .setMode(TranscodeMode.TRANSCODE_MODE_REMUX)
                    .setVideoCodecFamily("av1"))
            .build();

    assertThat(registry.dispatch(copyJob)).isPresent();
  }

  @Test
  @DisplayName(
      "Should count only software and keyframe-verified encoders when reporting the codec families that workers encode")
  void shouldCountOnlySoftwareAndKeyframeVerifiedEncodersWhenReportingEncodableCodecFamilies() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var otherWorkerId = UUID.randomUUID();
    registry.register(
        WORKER_ID,
        registration(
            WORKER_ID,
            hardwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "h264_nvenc"),
            hardwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "av1_nvenc")),
        new CancellableObserver());
    var otherWorker = registration(otherWorkerId).toBuilder();
    otherWorker
        .getCapabilitiesBuilder()
        .addVideoEncoders(softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "libsvtav1"))
        .addVideoEncoders(
            softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_UNSPECIFIED, "libvpx-vp9"))
        .addVideoEncoders(
            hardwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC, "hevc_nvenc").toBuilder()
                .setKeyframeVerified(true));
    var unreachableNamespace = UUID.randomUUID();

    assertThat(registry.encodableVideoCodecFamilies(SOURCE_NAMESPACE_ID)).isEmpty();

    registry.register(otherWorkerId, otherWorker.build(), new CancellableObserver());

    assertThat(registry.encodableVideoCodecFamilies(SOURCE_NAMESPACE_ID))
        .containsExactlyInAnyOrder("hevc", "av1");
    assertThat(registry.encodableVideoCodecFamilies(unreachableNamespace)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should count an encode's free slots only on workers that encode its codec family and a copy's on every worker")
  void shouldCountAnEncodesFreeSlotsOnlyOnWorkersThatEncodeItsCodecFamily() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var av1WorkerId = UUID.randomUUID();
    registry.register(
        WORKER_ID,
        registration(
            WORKER_ID, softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")),
        new CancellableObserver());
    registry.register(
        av1WorkerId,
        registration(
            av1WorkerId, softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "libsvtav1")),
        new CancellableObserver());
    var hevcCopy =
        encodeJob("hevc").getDecision().toBuilder()
            .setMode(TranscodeMode.TRANSCODE_MODE_REMUX)
            .build();

    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID, encodeJob("av1").getDecision()))
        .isOne();
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID, encodeJob("hevc").getDecision()))
        .isZero();
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID, hevcCopy)).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Should offer an encode only the workers that encode its codec family as execution targets")
  void shouldOfferAnEncodeOnlyTheWorkersThatEncodeItsCodecFamilyAsExecutionTargets() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var av1WorkerId = UUID.randomUUID();
    registry.register(
        WORKER_ID,
        registration(
            WORKER_ID, softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")),
        new CancellableObserver());
    registry.register(
        av1WorkerId,
        registration(
            av1WorkerId, softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "libsvtav1")),
        new CancellableObserver());
    var allWorkers = registry.eligibleWorkers(SOURCE_NAMESPACE_ID, streamCopyDecision());

    var av1Targets = registry.eligibleWorkers(SOURCE_NAMESPACE_ID, encodeJob("av1").getDecision());

    assertThat(allWorkers).hasSize(2);
    assertThat(av1Targets).singleElement().isIn(allWorkers);
    assertThat(registry.eligibleWorkers(SOURCE_NAMESPACE_ID, encodeJob("h264").getDecision()))
        .singleElement()
        .isIn(allWorkers)
        .isNotEqualTo(av1Targets.iterator().next());
  }

  @Test
  @DisplayName("Should leave out a codec family when every worker that encodes it has no free slot")
  void shouldLeaveOutACodecFamilyWhenEveryWorkerThatEncodesItHasNoFreeSlot() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var av1WorkerId = UUID.randomUUID();
    registry.register(
        WORKER_ID,
        registration(
            WORKER_ID, softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")),
        new CancellableObserver());
    registry.register(
        av1WorkerId,
        registration(
            av1WorkerId, softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "libsvtav1")),
        new CancellableObserver());

    assertThat(registry.dispatch(encodeJob("av1"))).isPresent();

    assertThat(registry.encodableVideoCodecFamilies(SOURCE_NAMESPACE_ID)).containsOnly("h264");
  }

  @ParameterizedTest(name = "{2} encode with only {1}")
  @CsvSource({
    "VIDEO_CODEC_FAMILY_AV1, av1_nvenc, av1",
    "VIDEO_CODEC_FAMILY_H264, h264_nvenc, h264"
  })
  @DisplayName(
      "Should refuse an encode for a worker whose only encoder for the codec family is unverified hardware")
  void shouldRefuseAnEncodeForAWorkerWhoseOnlyEncoderForTheCodecFamilyIsUnverifiedHardware(
      VideoCodecFamily family, String encoder, String codecFamilyName) {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    registry.register(
        WORKER_ID,
        registration(WORKER_ID, hardwareEncoder(family, encoder)),
        new CancellableObserver());

    assertThat(registry.dispatch(encodeJob(codecFamilyName))).isEmpty();
  }

  @Test
  @DisplayName(
      "Should survive stopping a session whose worker call is cancelled but not yet reaped when managing a connection")
  void
      shouldSurviveStoppingSessionWhoseWorkerCallIsCancelledButNotYetReapedWhenManagingConnection() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var observer = new CancellableObserver();
    registry.register(WORKER_ID, registration(), observer);
    var job = variantJob();
    assertThat(registry.dispatch(job)).isPresent();
    observer.cancel();
    var streamSessionId = fromProto(job.getStreamSessionId());

    registry.stopStreamSession(streamSessionId);

    assertThat(registry.isRunning(streamSessionId, job.getVariant().getVariantLabel())).isFalse();
  }

  @Test
  @DisplayName(
      "Should not block worker disconnect while a segment publish is in progress when managing a connection")
  void shouldNotBlockDisconnectWhileSegmentPublishInProgressWhenManagingConnection()
      throws Exception {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var worker =
        WorkerIdentity.newBuilder()
            .setWorkerId(toProto(WORKER_ID))
            .setBootId(toProto(UUID.randomUUID()))
            .build();
    var registration =
        WorkerRegistration.newBuilder()
            .setWorker(worker)
            .setCapabilities(
                WorkerCapabilities.newBuilder().addSourceNamespaceIds(toProto(SOURCE_NAMESPACE_ID)))
            .setAvailableSlots(1)
            .build();
    var workerSessionId =
        registry.register(WORKER_ID, registration, collecting(new CopyOnWriteArrayList<>()));
    var job = variantJob();
    assertThat(registry.dispatch(job)).isPresent();

    var metadata = uploadMetadata(workerSessionId, worker, job);

    var inPublish = new CountDownLatch(1);
    var releasePublish = new CountDownLatch(1);
    Supplier<SegmentPublication> blockingPublish =
        () -> {
          inPublish.countDown();
          try {
            if (!releasePublish.await(5, TimeUnit.SECONDS)) {
              throw new AssertionError("Timed out waiting to release segment publication");
            }
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
          }

          return SegmentPublication.PUBLISHED;
        };

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var publishing =
          executor.submit(() -> registry.publishIfAuthorized(WORKER_ID, metadata, blockingPublish));
      assertThat(inPublish.await(5, TimeUnit.SECONDS)).isTrue();

      var disconnected = new CountDownLatch(1);
      executor.submit(
          () -> {
            registry.disconnect(WORKER_ID, workerSessionId);
            disconnected.countDown();
          });
      var disconnectedPromptly = disconnected.await(2, TimeUnit.SECONDS);
      releasePublish.countDown();
      publishing.get(5, TimeUnit.SECONDS);

      assertThat(disconnectedPromptly)
          .as("disconnect must not be blocked by an in-progress segment publish")
          .isTrue();
    }
  }

  @Test
  @DisplayName(
      "Should count a refused initialization segment when its worker disconnects during the publication")
  void shouldCountRefusedInitializationSegmentWhenItsWorkerDisconnectsDuringPublication() {
    var meterRegistry = new SimpleMeterRegistry();
    var registry =
        new LiveWorkerConnectionRegistry(
            WorkerSessionServerConfiguration.builder().build(),
            meterRegistry,
            new HeldSegmentRequests(new SimpleMeterRegistry()));
    var responses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    var workerRegistration = registration();
    var workerSessionId = registry.register(WORKER_ID, workerRegistration, collecting(responses));
    var job = variantJob();
    assertThat(registry.dispatch(job)).isPresent();

    // The disconnect drains the attempt while the refused upload holds the connection monitor.
    var outcome =
        registry.publishIfAuthorized(
            WORKER_ID,
            uploadMetadata(workerSessionId, workerRegistration.getWorker(), job),
            () -> {
              registry.disconnect(WORKER_ID, workerSessionId);
              return SegmentPublication.INITIALIZATION_SEGMENT_DIFFERS;
            });

    assertThat(outcome).contains(SegmentPublication.INITIALIZATION_SEGMENT_DIFFERS);
    assertThat(InitializationSegmentMismatchMetric.count(meterRegistry)).isEqualTo(1);
    assertThat(responses)
        .extracting(EstablishWorkerSessionResponse::getCommandCase)
        .containsExactly(
            EstablishWorkerSessionResponse.CommandCase.SESSION_ACCEPTED,
            EstablishWorkerSessionResponse.CommandCase.START_VARIANT);
    assertThat(registry.hasConnectedWorker(SOURCE_NAMESPACE_ID)).isFalse();
  }

  @Test
  @DisplayName(
      "Should ignore a stale disconnect after the worker connection was replaced when managing a connection")
  void shouldIgnoreAStaleDisconnectAfterTheWorkerConnectionWasReplacedWhenManagingConnection() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var staleObserver = new CancellableObserver();
    var staleSessionId = registry.register(WORKER_ID, registration(), staleObserver);
    var freshResponses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    registry.register(WORKER_ID, registration(), collecting(freshResponses));

    // The stale connection's server-side cancellation races the replacement registration; its
    // late disconnect must not evict the fresh connection or its jobs.
    registry.disconnect(WORKER_ID, staleSessionId);

    assertThat(registry.hasConnectedWorker(SOURCE_NAMESPACE_ID)).isTrue();
    assertThat(registry.dispatch(variantJob())).isPresent();
  }

  @Test
  @DisplayName(
      "Should ignore a stale result after the worker connection was replaced when managing a connection")
  void shouldIgnoreAStaleResultAfterTheWorkerConnectionWasReplacedWhenManagingConnection() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var staleSessionId = registry.register(WORKER_ID, registration(), new CancellableObserver());
    registry.register(WORKER_ID, registration(), collecting(new CopyOnWriteArrayList<>()));
    var replacementJob = variantJob();
    assertThat(registry.dispatch(replacementJob)).isPresent();

    var released =
        registry.releaseJobAttempt(
            WORKER_ID, staleSessionId, fromProto(replacementJob.getJobAttemptId()));

    assertThat(released).isEmpty();
    assertThat(
            registry.isRunning(
                fromProto(replacementJob.getStreamSessionId()),
                replacementJob.getVariant().getVariantLabel()))
        .isTrue();
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID)).isZero();
  }

  @Test
  @DisplayName("Should report the accepting worker's encoder when dispatching an encode")
  void shouldReportTheAcceptingWorkersEncoderWhenDispatchingAnEncode() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    registry.register(
        WORKER_ID, registration(WORKER_ID, libx264Encoder()), new CancellableObserver());

    var dispatched = registry.dispatch(encodeJob("h264"));

    assertThat(dispatched)
        .map(VariantDispatch::encoder)
        .contains(Optional.of(WorkerVerifiedEncoderFixture.libx264()));
  }

  @Test
  @DisplayName("Should report no encoder when dispatching a stream copy")
  void shouldReportNoEncoderWhenDispatchingAStreamCopy() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    registry.register(
        WORKER_ID, registration(WORKER_ID, libx264Encoder()), new CancellableObserver());

    var dispatched =
        registry.dispatch(variantJob().toBuilder().setDecision(streamCopyDecision()).build());

    assertThat(dispatched).map(VariantDispatch::encoder).contains(Optional.empty());
  }

  @Test
  @DisplayName(
      "Should dispatch a pinned encode only to a worker that advertises the pinned encoder backend")
  void shouldDispatchPinnedEncodeOnlyToWorkerThatAdvertisesPinnedEncoderBackend() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var nvencResponses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    var libx264WorkerId = UUID.randomUUID();
    registry.register(
        WORKER_ID,
        registration(
            WORKER_ID,
            keyframeVerifiedHardwareEncoder(
                VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "h264_nvenc")),
        collecting(nvencResponses));
    var pinned = WorkerVerifiedEncoderFixture.libx264();
    var job = encodeJob("h264");

    assertThat(registry.dispatch(job, pinned)).isEmpty();

    registry.register(
        libx264WorkerId,
        registration(libx264WorkerId, libx264Encoder()),
        new CancellableObserver());

    assertThat(registry.dispatch(job, pinned))
        .map(VariantDispatch::encoder)
        .contains(Optional.of(WorkerVerifiedEncoderFixture.libx264()));
    assertThat(nvencResponses).noneMatch(EstablishWorkerSessionResponse::hasStartVariant);
  }

  @Test
  @DisplayName(
      "Should send a keyframe-verified pin only to a worker that advertises the encoder as keyframe-verified")
  void shouldSendKeyframeVerifiedPinOnlyToWorkerThatAdvertisesEncoderAsKeyframeVerified() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var unverifiedResponses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    var verifiedWorkerId = UUID.randomUUID();
    registry.register(
        WORKER_ID,
        registration(
            WORKER_ID, softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")),
        collecting(unverifiedResponses));
    var pinned = WorkerVerifiedEncoderFixture.libx264();
    var job = encodeJob("h264");

    assertThat(registry.eligibleWorkers(SOURCE_NAMESPACE_ID, pinned)).isEmpty();
    assertThat(registry.dispatch(job, pinned)).isEmpty();

    var verifiedWorkerSessionId =
        registry.register(
            verifiedWorkerId,
            registration(verifiedWorkerId, libx264Encoder()),
            new CancellableObserver());

    assertThat(registry.eligibleWorkers(SOURCE_NAMESPACE_ID, pinned))
        .containsExactly(new ExecutionTargetId(verifiedWorkerSessionId.toString()));
    assertThat(registry.dispatch(job, pinned))
        .map(VariantDispatch::encoder)
        .contains(Optional.of(WorkerVerifiedEncoderFixture.libx264()));
    assertThat(unverifiedResponses).noneMatch(EstablishWorkerSessionResponse::hasStartVariant);
  }

  @Test
  @DisplayName(
      "Should send a pin without keyframe verification only to a worker that advertises the same flag")
  void shouldSendPinWithoutKeyframeVerificationOnlyToWorkerThatAdvertisesTheSameFlag() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    registry.register(
        WORKER_ID, registration(WORKER_ID, libx264Encoder()), new CancellableObserver());

    var pinned = WorkerVerifiedEncoderFixture.libx264NotKeyframeVerified();

    assertThat(registry.eligibleWorkers(SOURCE_NAMESPACE_ID, pinned)).isEmpty();
    assertThat(registry.dispatch(encodeJob("h264"), pinned)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should send a pinned encode to a named target only when the target advertises the pinned encoder")
  void shouldSendPinnedEncodeToNamedTargetOnlyWhenTargetAdvertisesPinnedEncoder() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var nvencResponses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    var libx264WorkerId = UUID.randomUUID();
    var nvencTarget =
        new ExecutionTargetId(
            registry
                .register(
                    WORKER_ID,
                    registration(
                        WORKER_ID,
                        keyframeVerifiedHardwareEncoder(
                            VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "h264_nvenc")),
                    collecting(nvencResponses))
                .toString());
    var libx264Target =
        new ExecutionTargetId(
            registry
                .register(
                    libx264WorkerId,
                    registration(libx264WorkerId, libx264Encoder()),
                    new CancellableObserver())
                .toString());
    var pinned = WorkerVerifiedEncoderFixture.libx264();

    assertThat(registry.dispatchTo(nvencTarget, encodeJob("h264"), pinned)).isEmpty();
    assertThat(registry.dispatchTo(libx264Target, encodeJob("h264"), pinned))
        .map(VariantDispatch::encoder)
        .contains(Optional.of(pinned));
    assertThat(nvencResponses).noneMatch(EstablishWorkerSessionResponse::hasStartVariant);
  }

  @Test
  @DisplayName(
      "Should list only workers that advertise the pinned encoder backend when finding execution targets")
  void shouldListOnlyWorkersThatAdvertisePinnedEncoderBackendWhenFindingExecutionTargets() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var libx264WorkerId = UUID.randomUUID();
    registry.register(
        WORKER_ID,
        registration(
            WORKER_ID,
            keyframeVerifiedHardwareEncoder(
                VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "h264_nvenc")),
        new CancellableObserver());
    var libx264WorkerSessionId =
        registry.register(
            libx264WorkerId,
            registration(libx264WorkerId, libx264Encoder()),
            new CancellableObserver());

    var targets =
        registry.eligibleWorkers(SOURCE_NAMESPACE_ID, WorkerVerifiedEncoderFixture.libx264());

    assertThat(targets).containsExactly(new ExecutionTargetId(libx264WorkerSessionId.toString()));
    assertThat(registry.eligibleWorkers(UUID.randomUUID(), WorkerVerifiedEncoderFixture.libx264()))
        .isEmpty();
  }

  @Test
  @DisplayName(
      "Should withdraw the worker's encoder for the codec family when a start report names another backend")
  void shouldWithdrawWorkersEncoderForCodecFamilyWhenStartReportNamesAnotherBackend() {
    var encode = RunningEncode.dispatch();

    encode
        .registry()
        .checkReportedEncoderBackend(
            WORKER_ID, encode.workerSessionId(), nvencStarted(encode.job().getJobAttemptId()));

    assertThat(
            encode
                .registry()
                .eligibleWorkers(SOURCE_NAMESPACE_ID, WorkerVerifiedEncoderFixture.libx264()))
        .isEmpty();
    assertThat(encode.registry().encodableVideoCodecFamilies(SOURCE_NAMESPACE_ID))
        .doesNotContain("h264");
    assertThat(encode.registry().eligibleWorkers(SOURCE_NAMESPACE_ID, streamCopyDecision()))
        .containsExactly(new ExecutionTargetId(encode.workerSessionId().toString()));
  }

  @Test
  @DisplayName(
      "Should stop every encode whose start report names another backend after the worker's encoder was withdrawn")
  void shouldStopEveryEncodeWhoseStartReportNamesAnotherBackendAfterWorkersEncoderWasWithdrawn() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var registration = registration(WORKER_ID, 2).toBuilder();
    registration.getCapabilitiesBuilder().addVideoEncoders(libx264Encoder());
    var workerSessionId =
        registry.register(WORKER_ID, registration.build(), new CancellableObserver());
    var first = encodeJob("h264");
    var second = encodeJob("h264");
    assertThat(registry.dispatch(first)).isPresent();
    assertThat(registry.dispatch(second)).isPresent();

    registry.checkReportedEncoderBackend(
        WORKER_ID, workerSessionId, nvencStarted(first.getJobAttemptId()));
    registry.checkReportedEncoderBackend(
        WORKER_ID, workerSessionId, nvencStarted(second.getJobAttemptId()));

    assertThat(isRunning(registry, first)).isFalse();
    assertThat(isRunning(registry, second)).isFalse();
  }

  private static boolean isRunning(LiveWorkerConnectionRegistry registry, VariantJob job) {
    return registry.isRunning(
        fromProto(job.getStreamSessionId()), job.getVariant().getVariantLabel());
  }

  @Test
  @DisplayName(
      "Should keep a job attempt running when a start report naming another backend comes from an unknown worker")
  void shouldKeepJobAttemptRunningWhenStartReportNamingAnotherBackendComesFromUnknownWorker() {
    var encode = RunningEncode.dispatch();

    encode
        .registry()
        .checkReportedEncoderBackend(
            UUID.randomUUID(),
            encode.workerSessionId(),
            nvencStarted(encode.job().getJobAttemptId()));

    assertThat(encode.isRunning()).isTrue();
  }

  @Test
  @DisplayName(
      "Should keep a job attempt running when a start report naming another backend comes from a replaced worker session")
  void shouldKeepJobAttemptRunningWhenStartReportNamingAnotherBackendComesFromReplacedSession() {
    var encode = RunningEncode.dispatch();

    encode
        .registry()
        .checkReportedEncoderBackend(
            WORKER_ID, UUID.randomUUID(), nvencStarted(encode.job().getJobAttemptId()));

    assertThat(encode.isRunning()).isTrue();
  }

  @Test
  @DisplayName(
      "Should keep a job attempt running when a start report naming another backend names an unknown job attempt")
  void shouldKeepJobAttemptRunningWhenStartReportNamingAnotherBackendNamesUnknownJobAttempt() {
    var encode = RunningEncode.dispatch();

    encode
        .registry()
        .checkReportedEncoderBackend(
            WORKER_ID, encode.workerSessionId(), nvencStarted(toProto(UUID.randomUUID())));

    assertThat(encode.isRunning()).isTrue();
  }

  @Test
  @DisplayName(
      "Should publish an encode attempt's upload once its start report names the dispatched encoder backend")
  void shouldPublishEncodeAttemptsUploadOnceItsStartReportNamesDispatchedEncoderBackend()
      throws Exception {
    var encode = RunningEncode.dispatch();

    var published = new AtomicBoolean();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var publication = executor.submit(() -> encode.publishInitializationSegment(published));
      encode
          .registry()
          .checkReportedEncoderBackend(
              WORKER_ID, encode.workerSessionId(), libx264Started(encode.job().getJobAttemptId()));

      assertThat(publication.get(5, TimeUnit.SECONDS)).contains(SegmentPublication.PUBLISHED);
    }

    assertThat(published).isTrue();
    assertThat(encode.isRunning()).isTrue();
  }

  @Test
  @DisplayName(
      "Should refuse an encode attempt's upload that overtakes a start report naming another backend")
  void shouldRefuseEncodeAttemptsUploadThatOvertakesStartReportNamingAnotherBackend()
      throws Exception {
    var encode = RunningEncode.dispatch();
    var published = new AtomicBoolean();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var publication = executor.submit(() -> encode.publishInitializationSegment(published));
      encode
          .registry()
          .checkReportedEncoderBackend(
              WORKER_ID, encode.workerSessionId(), nvencStarted(encode.job().getJobAttemptId()));

      assertThat(publication.get(5, TimeUnit.SECONDS)).isEmpty();
    }

    assertThat(published).isFalse();
    assertThat(encode.isRunning()).isFalse();
  }

  @Test
  @DisplayName(
      "Should refuse an encode attempt's upload when its start report does not arrive in time")
  void shouldRefuseEncodeAttemptsUploadWhenItsStartReportDoesNotArriveInTime() {
    var encode =
        RunningEncode.dispatch(
            WorkerSessionServerConfiguration.builder()
                .startReportTimeout(Duration.ofMillis(50))
                .build());
    var published = new AtomicBoolean();

    assertThat(encode.publishInitializationSegment(published)).isEmpty();

    assertThat(published).isFalse();
    assertThat(encode.isRunning()).isTrue();
  }

  @Test
  @DisplayName(
      "Should release a held upload without waiting for the start report when the encode attempt stops")
  void shouldReleaseHeldUploadWithoutWaitingForStartReportWhenEncodeAttemptStops()
      throws Exception {
    var encode =
        RunningEncode.dispatch(
            WorkerSessionServerConfiguration.builder()
                .startReportTimeout(Duration.ofMinutes(5))
                .build());

    var published = new AtomicBoolean();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var publication = executor.submit(() -> encode.publishInitializationSegment(published));
      encode
          .registry()
          .stopVariant(
              fromProto(encode.job().getStreamSessionId()),
              encode.job().getVariant().getVariantLabel());

      assertThat(publication.get(5, TimeUnit.SECONDS)).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "Should release a held upload without waiting for the start report when the worker disconnects")
  void shouldReleaseHeldUploadWithoutWaitingForStartReportWhenWorkerDisconnects() throws Exception {
    var encode =
        RunningEncode.dispatch(
            WorkerSessionServerConfiguration.builder()
                .startReportTimeout(Duration.ofMinutes(5))
                .build());

    var published = new AtomicBoolean();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var publication = executor.submit(() -> encode.publishInitializationSegment(published));
      encode.registry().disconnect(WORKER_ID, encode.workerSessionId());

      assertThat(publication.get(5, TimeUnit.SECONDS)).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "Should release a held upload without waiting for the start report when the worker finishes the attempt")
  void shouldReleaseHeldUploadWithoutWaitingForStartReportWhenWorkerFinishesAttempt()
      throws Exception {
    var encode =
        RunningEncode.dispatch(
            WorkerSessionServerConfiguration.builder()
                .startReportTimeout(Duration.ofMinutes(5))
                .build());

    var published = new AtomicBoolean();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var publication = executor.submit(() -> encode.publishInitializationSegment(published));
      encode
          .registry()
          .releaseJobAttempt(
              WORKER_ID, encode.workerSessionId(), fromProto(encode.job().getJobAttemptId()));

      assertThat(publication.get(5, TimeUnit.SECONDS)).isEmpty();
    }
  }

  private static JobAttemptStarted libx264Started(Uuid jobAttemptId) {
    return JobAttemptStarted.newBuilder()
        .setJobAttemptId(jobAttemptId)
        .setEncoderBackend(libx264Encoder().getBackend())
        .build();
  }

  private static JobAttemptStarted nvencStarted(Uuid jobAttemptId) {
    return JobAttemptStarted.newBuilder()
        .setJobAttemptId(jobAttemptId)
        .setEncoderBackend(
            hardwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "h264_nvenc").getBackend())
        .build();
  }

  @Builder
  private record RunningEncode(
      LiveWorkerConnectionRegistry registry,
      UUID workerSessionId,
      WorkerIdentity worker,
      VariantJob job) {

    private static RunningEncode dispatch() {
      return dispatch(WorkerSessionServerConfiguration.builder().build());
    }

    private static RunningEncode dispatch(WorkerSessionServerConfiguration configuration) {
      var registry =
          new LiveWorkerConnectionRegistry(
              configuration,
              new SimpleMeterRegistry(),
              new HeldSegmentRequests(new SimpleMeterRegistry()));
      var workerRegistration = registration(WORKER_ID, libx264Encoder());
      var workerSessionId =
          registry.register(WORKER_ID, workerRegistration, new CancellableObserver());
      var job = encodeJob("h264");
      assertThat(registry.dispatch(job)).isPresent();
      return RunningEncode.builder()
          .registry(registry)
          .workerSessionId(workerSessionId)
          .worker(workerRegistration.getWorker())
          .job(job)
          .build();
    }

    private boolean isRunning() {
      return registry.isRunning(
          fromProto(job.getStreamSessionId()), job.getVariant().getVariantLabel());
    }

    private Optional<SegmentPublication> publishInitializationSegment(AtomicBoolean published) {
      return registry.publishIfAuthorized(
          WORKER_ID,
          uploadMetadata(workerSessionId, worker, job),
          () -> {
            published.set(true);
            return SegmentPublication.PUBLISHED;
          });
    }
  }

  private static WorkerVerifiedVideoEncoder libx264Encoder() {
    return softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264").toBuilder()
        .setKeyframeVerified(true)
        .build();
  }

  private static WorkerRegistration registration() {
    return registration(WORKER_ID, 1);
  }

  private static WorkerRegistration registration(UUID workerId, int availableSlots) {
    return WorkerRegistration.newBuilder()
        .setWorker(
            WorkerIdentity.newBuilder()
                .setWorkerId(toProto(workerId))
                .setBootId(toProto(UUID.randomUUID())))
        .setCapabilities(
            WorkerCapabilities.newBuilder().addSourceNamespaceIds(toProto(SOURCE_NAMESPACE_ID)))
        .setAvailableSlots(availableSlots)
        .build();
  }

  private static WorkerRegistration registration(
      UUID workerId, WorkerVerifiedVideoEncoder... encoders) {
    var registration = registration(workerId, 1).toBuilder();
    registration.getCapabilitiesBuilder().addAllVideoEncoders(List.of(encoders));
    return registration.build();
  }

  private static VariantJob encodeJob(String videoCodecFamily) {
    return variantJob().toBuilder()
        .setDecision(
            TranscodeDecision.newBuilder()
                .setMode(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE)
                .setVideoCodecFamily(videoCodecFamily))
        .build();
  }

  private static VariantJob variantJob() {
    return VariantJob.newBuilder()
        .setStreamSessionId(toProto(UUID.randomUUID()))
        .setJobId(toProto(UUID.randomUUID()))
        .setJobAttemptId(toProto(UUID.randomUUID()))
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
                .setRelativeKey("movie.mkv"))
        .setVariant(VariantSpec.newBuilder().setVariantLabel("720p"))
        .build();
  }

  private static SegmentUploadMetadata uploadMetadata(
      UUID workerSessionId, WorkerIdentity worker, VariantJob job) {
    return SegmentUploadMetadata.newBuilder()
        .setWorkerSessionId(toProto(workerSessionId))
        .setWorker(worker)
        .setJobAttemptId(job.getJobAttemptId())
        .setStreamSessionId(job.getStreamSessionId())
        .setJobId(job.getJobId())
        .setVariantLabel(job.getVariant().getVariantLabel())
        .build();
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
        throw new AssertionError("Replacement worker should remain connected", throwable);
      }

      @Override
      public void onCompleted() {
        throw new AssertionError("Replacement worker should remain connected");
      }
    };
  }

  /** Holds the dispatcher inside its StartVariant send until the test releases it. */
  private static final class GatedDispatchObserver
      implements StreamObserver<EstablishWorkerSessionResponse> {

    private final CountDownLatch dispatchReached = new CountDownLatch(1);
    private final CountDownLatch dispatchRelease = new CountDownLatch(1);

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
      if (!value.hasStartVariant()) {
        return;
      }
      dispatchReached.countDown();
      try {
        dispatchRelease.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void onError(Throwable throwable) {
      // The connection under test is closed by the registry; nothing to assert here.
    }

    @Override
    public void onCompleted() {
      // The connection under test is closed by the registry; nothing to assert here.
    }
  }

  private static final class CancellableObserver
      implements StreamObserver<EstablishWorkerSessionResponse> {

    private boolean cancelled;

    private void cancel() {
      cancelled = true;
    }

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
      if (cancelled) {
        throw Status.CANCELLED.withDescription("call already cancelled").asRuntimeException();
      }
    }

    @Override
    public void onError(Throwable throwable) {
      cancelled = true;
    }

    @Override
    public void onCompleted() {
      cancelled = true;
    }
  }

  private static final class BlockingAcceptanceObserver
      implements StreamObserver<EstablishWorkerSessionResponse> {

    private final CountDownLatch acceptanceReached = new CountDownLatch(1);
    private final CountDownLatch acceptanceRelease = new CountDownLatch(1);

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
      if (!value.hasSessionAccepted()) {
        return;
      }
      acceptanceReached.countDown();
      try {
        acceptanceRelease.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void onError(Throwable throwable) {
      throw new AssertionError("Worker registration should remain connected", throwable);
    }

    @Override
    public void onCompleted() {
      throw new AssertionError("Worker registration should remain connected");
    }
  }

  private static final class RejectingAcceptanceObserver
      implements StreamObserver<EstablishWorkerSessionResponse> {

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
      if (value.hasSessionAccepted()) {
        throw Status.CANCELLED.withDescription("acknowledgement rejected").asRuntimeException();
      }
    }

    @Override
    public void onError(Throwable ignored) {
      // Rejection is expressed synchronously from onNext.
    }

    @Override
    public void onCompleted() {
      // Rejection is expressed synchronously from onNext.
    }
  }

  private record BlockingCloseObserver(CountDownLatch closing, CountDownLatch continueClosing)
      implements StreamObserver<EstablishWorkerSessionResponse> {

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
      assertThat(value.hasSessionAccepted()).isTrue();
    }

    @Override
    public void onError(Throwable throwable) {
      closing.countDown();
      try {
        if (!continueClosing.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("Timed out waiting to finish replacement close");
        }
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void onCompleted() {
      throw new AssertionError("Replaced worker should fail instead of completing");
    }
  }
}
