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

import com.streamarr.server.domain.streaming.VideoSampleEntry;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.server.services.streaming.WorkerVideoCopySupport;
import com.streamarr.server.services.streaming.events.JobAttemptProgressedEvent;
import com.streamarr.server.services.streaming.events.MediaSegmentPublishedEvent;
import com.streamarr.transcode.v1.AudioChannelLayout;
import com.streamarr.transcode.v1.AudioDecision;
import com.streamarr.transcode.v1.AudioMode;
import com.streamarr.transcode.v1.BitstreamRewrite;
import com.streamarr.transcode.v1.CompatibleBase;
import com.streamarr.transcode.v1.ContentLightLevel;
import com.streamarr.transcode.v1.DolbyVisionAbsent;
import com.streamarr.transcode.v1.DolbyVisionOutput;
import com.streamarr.transcode.v1.DolbyVisionPresent;
import com.streamarr.transcode.v1.DolbyVisionSampleEntry;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.JobAttemptProgress;
import com.streamarr.transcode.v1.JobAttemptStarted;
import com.streamarr.transcode.v1.MasteringDisplayColorVolume;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.Presence;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.TranscodeDecision;
import com.streamarr.transcode.v1.TranscodeMode;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VariantSpec;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.VideoCopyFeature;
import com.streamarr.transcode.v1.VideoDecision;
import com.streamarr.transcode.v1.VideoDynamicRange;
import com.streamarr.transcode.v1.VideoEncodeFeature;
import com.streamarr.transcode.v1.VideoMode;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerVerifiedVideoEncoder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Live Worker Connection Registry Tests")
class LiveWorkerConnectionRegistryTest {

  private static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  @Test
  @DisplayName("Should reject a registry when its event publisher is missing")
  void shouldRejectRegistryWhenItsEventPublisherIsMissing() {
    var configuration = WorkerSessionServerConfiguration.builder().build();
    var meters = new SimpleMeterRegistry();
    var held = new HeldSegmentRequests(meters);

    assertThatNullPointerException()
        .isThrownBy(
            () ->
                LiveWorkerConnectionRegistry.builder()
                    .configuration(configuration)
                    .meterRegistry(meters)
                    .heldRequests(held)
                    .build())
        .withMessageContaining("eventPublisher");
  }

  @Test
  @DisplayName("Should reject a registry when its configuration is missing")
  void shouldRejectRegistryWhenItsConfigurationIsMissing() {
    var meterRegistry = new SimpleMeterRegistry();
    var heldRequests = new HeldSegmentRequests(new SimpleMeterRegistry());

    assertThatNullPointerException()
        .isThrownBy(
            () ->
                LiveWorkerConnectionRegistry.builder()
                    .eventPublisher(new CapturingEventPublisher())
                    .configuration(null)
                    .meterRegistry(meterRegistry)
                    .heldRequests(heldRequests)
                    .build())
        .withMessageContaining("configuration");
  }

  @Test
  @DisplayName("Should reject a registry when its meter registry is missing")
  void shouldRejectRegistryWhenItsMeterRegistryIsMissing() {
    var configuration = WorkerSessionServerConfiguration.builder().build();
    var heldRequests = new HeldSegmentRequests(new SimpleMeterRegistry());

    assertThatNullPointerException()
        .isThrownBy(
            () ->
                LiveWorkerConnectionRegistry.builder()
                    .eventPublisher(new CapturingEventPublisher())
                    .configuration(configuration)
                    .meterRegistry(null)
                    .heldRequests(heldRequests)
                    .build())
        .withMessageContaining("meterRegistry");
  }

  @Test
  @DisplayName("Should reject a registry when its held segment requests are missing")
  void shouldRejectRegistryWhenItsHeldSegmentRequestsAreMissing() {
    var configuration = WorkerSessionServerConfiguration.builder().build();
    var meterRegistry = new SimpleMeterRegistry();

    assertThatNullPointerException()
        .isThrownBy(
            () ->
                LiveWorkerConnectionRegistry.builder()
                    .eventPublisher(new CapturingEventPublisher())
                    .configuration(configuration)
                    .meterRegistry(meterRegistry)
                    .heldRequests(null)
                    .build())
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

  // A held request re-checks its stall deadline and its stream session's access on each pass, so
  // progress on a long merged segment must start a pass before the deadline or idle timeout.
  @Test
  @DisplayName("Should wake the requests held on a variant when its job attempt reports progress")
  void shouldWakeRequestsHeldOnVariantWhenItsJobAttemptReportsProgress() throws Exception {
    var held = HeldVariant.dispatch();

    held.registry()
        .reportProgress(
            WORKER_ID,
            held.workerSessionId(),
            JobAttemptProgress.newBuilder().setJobAttemptId(held.job().getJobAttemptId()).build());

    assertThat(held.wake().await(Duration.ZERO)).isTrue();
  }

  // A woken request takes the session lock and measures the stall deadline at once, so the
  // progress must reach the stall clock before the wake releases the request.
  @Test
  @DisplayName(
      "Should publish a job attempt's progress before it wakes the requests held on its variant")
  void shouldPublishAJobAttemptsProgressBeforeItWakesTheRequestsHeldOnItsVariant() {
    var heldRequests = new HeldSegmentRequests(new SimpleMeterRegistry());
    var job = variantJob();
    var wake =
        heldRequests.nextWake(
            fromProto(job.getStreamSessionId()), job.getVariant().getVariantLabel());
    var wokenAtPublish = new ArrayList<Boolean>();
    var registry =
        LiveWorkerConnectionRegistry.builder()
            .eventPublisher(
                event -> {
                  if (event instanceof JobAttemptProgressedEvent) {
                    wokenAtPublish.add(hasFired(wake));
                  }
                })
            .configuration(WorkerSessionServerConfiguration.builder().build())
            .meterRegistry(new SimpleMeterRegistry())
            .heldRequests(heldRequests)
            .build();
    var workerSessionId = registry.register(WORKER_ID, registration(), new CancellableObserver());
    assertThat(registry.dispatch(job)).isPresent();

    registry.reportProgress(
        WORKER_ID,
        workerSessionId,
        JobAttemptProgress.newBuilder().setJobAttemptId(job.getJobAttemptId()).build());

    assertThat(wokenAtPublish).containsExactly(false);
  }

  private static boolean hasFired(HeldSegmentRequests.Wake wake) {
    try {
      return wake.await(Duration.ZERO);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
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

  @Test
  @DisplayName(
      "Should name the worker that runs a variant's job attempt until the job attempt stops")
  void shouldNameTheWorkerThatRunsAVariantsJobAttemptUntilTheJobAttemptStops() {
    var held = HeldVariant.dispatch();
    var variantLabel = held.job().getVariant().getVariantLabel();
    var runningTarget = held.registry().runningExecutionTarget(held.sessionId(), variantLabel);

    held.registry().stopVariant(held.sessionId(), variantLabel);

    assertThat(runningTarget).contains(new ExecutionTargetId(held.workerSessionId().toString()));
    assertThat(held.registry().runningExecutionTarget(held.sessionId(), variantLabel)).isEmpty();
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
    assertThat(registry.videoCopySupportByWorker(SOURCE_NAMESPACE_ID)).hasSize(1);
    assertThat(registry.videoCopySupportByWorker(unavailableNamespace)).isEmpty();
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

  @ParameterizedTest(name = "{2} encode")
  @CsvSource({"VIDEO_CODEC_FAMILY_AV1, libsvtav1, av1", "VIDEO_CODEC_FAMILY_HEVC, libx265, hevc"})
  @DisplayName(
      "Should dispatch an encode only to a worker that advertises an encoder for its codec family")
  void shouldDispatchAnEncodeOnlyToAWorkerThatAdvertisesAnEncoderForItsCodecFamily(
      VideoCodecFamily family, String encoder, String codecFamilyName) {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var h264OnlyResponses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    var familyResponses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    var familyWorkerId = UUID.randomUUID();
    registry.register(
        WORKER_ID,
        registration(
            WORKER_ID, softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")),
        collecting(h264OnlyResponses));
    var job = encodeJob(codecFamilyName);

    assertThat(registry.dispatch(job)).isEmpty();

    registry.register(
        familyWorkerId,
        registration(familyWorkerId, softwareEncoder(family, encoder)),
        collecting(familyResponses));

    assertThat(registry.dispatch(job)).isPresent();
    assertThat(h264OnlyResponses)
        .extracting(EstablishWorkerSessionResponse::getCommandCase)
        .containsExactly(EstablishWorkerSessionResponse.CommandCase.SESSION_ACCEPTED);
    assertThat(familyResponses)
        .filteredOn(EstablishWorkerSessionResponse::hasStartVariant)
        .singleElement()
        .extracting(response -> response.getStartVariant().getJob())
        .isEqualTo(softwareEncoderAskedFor(job));
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
                    .setVideo(
                        VideoDecision.newBuilder()
                            .setMode(VideoMode.VIDEO_MODE_COPY)
                            .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1)))
            .build();

    assertThat(registry.dispatch(copyJob)).isPresent();
  }

  @ParameterizedTest
  @EnumSource(
      value = AudioChannelLayout.class,
      names = {
        "AUDIO_CHANNEL_LAYOUT_TWO_POINT_ONE",
        "AUDIO_CHANNEL_LAYOUT_THREE_POINT_ZERO",
        "AUDIO_CHANNEL_LAYOUT_THREE_POINT_ZERO_BACK",
        "AUDIO_CHANNEL_LAYOUT_FOUR_POINT_ZERO",
        "AUDIO_CHANNEL_LAYOUT_QUAD",
        "AUDIO_CHANNEL_LAYOUT_QUAD_SIDE",
        "AUDIO_CHANNEL_LAYOUT_SIX_POINT_ONE",
        "AUDIO_CHANNEL_LAYOUT_SIX_POINT_ONE_BACK",
        "AUDIO_CHANNEL_LAYOUT_SEVEN_POINT_ONE",
        "AUDIO_CHANNEL_LAYOUT_SEVEN_POINT_ONE_WIDE"
      })
  @DisplayName(
      "Should dispatch a job that names a layout of 3, 4, 7 or 8 channels only to a worker that"
          + " lists probe version 7")
  void shouldDispatchAJobThatNamesALayoutOf3478ChannelsOnlyToAWorkerThatListsProbeVersion7(
      AudioChannelLayout layout) {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var version6Responses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    var version7Responses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    var version7WorkerId = UUID.randomUUID();
    registry.register(
        WORKER_ID, registrationListingProbeVersion(WORKER_ID, 6), collecting(version6Responses));
    var job = audioEncodeJob(layout);

    assertThat(registry.dispatch(job)).isEmpty();

    registry.register(
        version7WorkerId,
        registrationListingProbeVersion(version7WorkerId, 7),
        collecting(version7Responses));

    assertThat(registry.dispatch(job)).isPresent();
    assertThat(version6Responses).noneMatch(EstablishWorkerSessionResponse::hasStartVariant);
    assertThat(version7Responses)
        .filteredOn(EstablishWorkerSessionResponse::hasStartVariant)
        .singleElement()
        .extracting(response -> response.getStartVariant().getJob())
        .isEqualTo(job);
  }

  @ParameterizedTest
  @EnumSource(
      value = AudioChannelLayout.class,
      names = {
        "AUDIO_CHANNEL_LAYOUT_UNSPECIFIED",
        "AUDIO_CHANNEL_LAYOUT_MONO",
        "AUDIO_CHANNEL_LAYOUT_STEREO",
        "AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO",
        "AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO_SIDE",
        "AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE",
        "AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE_SIDE"
      })
  @DisplayName(
      "Should dispatch a job that names no layout or one of 1, 2, 5 or 6 channels to a worker"
          + " without probe version 7")
  void shouldDispatchAJobThatNamesNoLayoutOrOneOf1256ChannelsToAWorkerWithoutProbeVersion7(
      AudioChannelLayout layout) {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    registry.register(
        WORKER_ID, registrationListingProbeVersion(WORKER_ID, 6), new CancellableObserver());

    assertThat(registry.dispatch(audioEncodeJob(layout))).isPresent();
  }

  @Test
  @DisplayName(
      "Should count free slots and offer execution targets only on probe version 7 workers when a"
          + " job names a quad layout")
  void shouldCountFreeSlotsAndOfferTargetsOnlyOnProbeVersion7WorkersWhenAJobNamesAQuadLayout() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var version7WorkerId = UUID.randomUUID();
    registry.register(
        WORKER_ID, registrationListingProbeVersion(WORKER_ID, 6), new CancellableObserver());
    var quad = audioEncodeJob(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_QUAD).getDecision();
    var stereo = audioEncodeJob(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_STEREO).getDecision();

    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID, quad)).isZero();
    assertThat(registry.eligibleWorkers(SOURCE_NAMESPACE_ID, quad)).isEmpty();

    registry.register(
        version7WorkerId,
        registrationListingProbeVersion(version7WorkerId, 7),
        new CancellableObserver());

    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID, quad)).isOne();
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID, stereo)).isEqualTo(2);
    assertThat(registry.eligibleWorkers(SOURCE_NAMESPACE_ID, quad))
        .singleElement()
        .isIn(registry.eligibleWorkers(SOURCE_NAMESPACE_ID, stereo));
    assertThat(registry.eligibleWorkers(SOURCE_NAMESPACE_ID, stereo)).hasSize(2);
  }

  // A worker that predates a rewrite ignores the field and would deliver the copy unchanged, so
  // the server sends the job only to a worker that lists the rewrite (ADR 0036).
  @ParameterizedTest
  @EnumSource(
      value = BitstreamRewrite.class,
      names = {"BITSTREAM_REWRITE_UNSPECIFIED", "UNRECOGNIZED"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName(
      "Should dispatch a copy that lists a rewrite only to a worker that lists the rewrite")
  void shouldDispatchACopyThatListsARewriteOnlyToAWorkerThatListsTheRewrite(
      BitstreamRewrite rewrite) {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var otherRewritesResponses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    var listingResponses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    var listingWorkerId = UUID.randomUUID();
    var otherRewrites =
        plannedOutputSupport()
            .addAllBitstreamRewrites(
                EnumSet.complementOf(
                    EnumSet.of(
                        rewrite,
                        BitstreamRewrite.BITSTREAM_REWRITE_UNSPECIFIED,
                        BitstreamRewrite.UNRECOGNIZED)));
    registry.register(
        WORKER_ID,
        registrationSupporting(WORKER_ID, otherRewrites),
        collecting(otherRewritesResponses));
    var job = copyJob(pqCopy().addBitstreamRewrites(rewrite));

    assertThat(registry.dispatch(job)).isEmpty();

    registry.register(
        listingWorkerId,
        registrationSupporting(
            listingWorkerId, plannedOutputSupport().addBitstreamRewrites(rewrite)),
        collecting(listingResponses));

    assertThat(registry.dispatch(job)).isPresent();
    assertThat(otherRewritesResponses).noneMatch(EstablishWorkerSessionResponse::hasStartVariant);
    assertThat(listingResponses)
        .filteredOn(EstablishWorkerSessionResponse::hasStartVariant)
        .singleElement()
        .extracting(response -> response.getStartVariant().getJob())
        .isEqualTo(job);
  }

  // A worker that predates the planned output refuses a copy that sets output_dynamic_range, and
  // it ignores the Dolby Vision output and the static HDR metadata (ADR 0013).
  @ParameterizedTest(name = "{0}")
  @MethodSource("plannedOutputCopies")
  @DisplayName(
      "Should dispatch a copy that carries a planned output only to a worker that lists the planned"
          + " output")
  void shouldDispatchACopyThatCarriesAPlannedOutputOnlyToAWorkerThatListsThePlannedOutput(
      String plannedOutput, VideoDecision.Builder video) {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var listingWorkerId = UUID.randomUUID();
    var listingResponses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    var everyRewrite =
        WorkerCapabilities.newBuilder()
            .addAllBitstreamRewrites(
                EnumSet.complementOf(
                    EnumSet.of(
                        BitstreamRewrite.BITSTREAM_REWRITE_UNSPECIFIED,
                        BitstreamRewrite.UNRECOGNIZED)));
    registry.register(
        WORKER_ID, registrationSupporting(WORKER_ID, everyRewrite), new CancellableObserver());
    var job = copyJob(video);

    assertThat(registry.dispatch(job)).isEmpty();

    registry.register(
        listingWorkerId,
        registrationSupporting(listingWorkerId, plannedOutputSupport()),
        collecting(listingResponses));

    assertThat(registry.dispatch(job)).isPresent();
    assertThat(listingResponses).anyMatch(EstablishWorkerSessionResponse::hasStartVariant);
  }

  static Stream<Arguments> plannedOutputCopies() {
    return Stream.of(
        Arguments.of("a PQ range", pqCopy()),
        Arguments.of(
            "a Dolby Vision output alone",
            copyVideo()
                .setDolbyVisionOutput(
                    dolbyVisionOutput(DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_HVC1))),
        Arguments.of(
            "a mastering display alone",
            copyVideo().setMasteringDisplay(MasteringDisplayColorVolume.getDefaultInstance())),
        Arguments.of(
            "a content light level alone",
            copyVideo().setContentLightLevel(ContentLightLevel.getDefaultInstance())));
  }

  @ParameterizedTest
  @CsvSource({
    "DOLBY_VISION_SAMPLE_ENTRY_DVH1, VIDEO_COPY_FEATURE_DVH1_SAMPLE_ENTRY,"
        + " VIDEO_COPY_FEATURE_DAV1_SAMPLE_ENTRY",
    "DOLBY_VISION_SAMPLE_ENTRY_DAV1, VIDEO_COPY_FEATURE_DAV1_SAMPLE_ENTRY,"
        + " VIDEO_COPY_FEATURE_DVH1_SAMPLE_ENTRY"
  })
  @DisplayName(
      "Should dispatch a Dolby Vision copy in a dvh1 or dav1 sample entry only to a worker that"
          + " lists that sample entry")
  void shouldDispatchADolbyVisionCopyInADvh1OrDav1SampleEntryOnlyToAWorkerThatListsIt(
      DolbyVisionSampleEntry sampleEntry, VideoCopyFeature needed, VideoCopyFeature other) {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var listingWorkerId = UUID.randomUUID();
    var listingResponses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    registry.register(
        WORKER_ID,
        registrationSupporting(WORKER_ID, plannedOutputSupport().addVideoCopyFeatures(other)),
        new CancellableObserver());
    var job = copyJob(pqCopy().setDolbyVisionOutput(dolbyVisionOutput(sampleEntry)));

    assertThat(registry.dispatch(job)).isEmpty();

    registry.register(
        listingWorkerId,
        registrationSupporting(
            listingWorkerId, plannedOutputSupport().addVideoCopyFeatures(needed)),
        collecting(listingResponses));

    assertThat(registry.dispatch(job)).isPresent();
    assertThat(listingResponses).anyMatch(EstablishWorkerSessionResponse::hasStartVariant);
  }

  @ParameterizedTest
  @EnumSource(
      value = DolbyVisionSampleEntry.class,
      names = {"DOLBY_VISION_SAMPLE_ENTRY_HVC1", "DOLBY_VISION_SAMPLE_ENTRY_AV01"})
  @DisplayName(
      "Should dispatch a Dolby Vision copy in an hvc1 or av01 sample entry to a worker that lists"
          + " only the planned output")
  void
      shouldDispatchADolbyVisionCopyInAnHvc1OrAv01SampleEntryToAWorkerThatListsOnlyThePlannedOutput(
          DolbyVisionSampleEntry sampleEntry) {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    registry.register(
        WORKER_ID,
        registrationSupporting(WORKER_ID, plannedOutputSupport()),
        new CancellableObserver());

    assertThat(
            registry.dispatch(
                copyJob(pqCopy().setDolbyVisionOutput(dolbyVisionOutput(sampleEntry)))))
        .isPresent();
  }

  // ADR 0036: the decision plans a profile 5 or 10.0 copy only when a connected worker writes its
  // dvh1 or dav1 sample entry, so the registry reports the Dolby Vision sample entries that each
  // worker lists.
  @ParameterizedTest(name = "{0}")
  @MethodSource("dolbyVisionSampleEntryFeatures")
  @DisplayName(
      "Should report the Dolby Vision sample entries that a worker lists when the worker registers")
  void shouldReportTheDolbyVisionSampleEntriesThatAWorkerListsWhenTheWorkerRegisters(
      String features, List<Integer> featureValues, Set<VideoSampleEntry> sampleEntries) {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    registry.register(
        WORKER_ID,
        registrationSupporting(
            WORKER_ID, plannedOutputSupport().addAllVideoCopyFeaturesValue(featureValues)),
        new CancellableObserver());

    assertThat(registry.videoCopySupportByWorker(SOURCE_NAMESPACE_ID))
        .singleElement()
        .extracting(WorkerVideoCopySupport::dolbyVisionSampleEntries)
        .isEqualTo(sampleEntries);
  }

  static Stream<Arguments> dolbyVisionSampleEntryFeatures() {
    var dvh1 = VideoCopyFeature.VIDEO_COPY_FEATURE_DVH1_SAMPLE_ENTRY_VALUE;
    var dav1 = VideoCopyFeature.VIDEO_COPY_FEATURE_DAV1_SAMPLE_ENTRY_VALUE;
    return Stream.of(
        Arguments.of("the planned output alone", List.of(), Set.of()),
        Arguments.of("dvh1", List.of(dvh1), Set.of(VideoSampleEntry.DVH1)),
        Arguments.of("dav1", List.of(dav1), Set.of(VideoSampleEntry.DAV1)),
        Arguments.of(
            "dvh1 and dav1",
            List.of(dvh1, dav1),
            Set.of(VideoSampleEntry.DVH1, VideoSampleEntry.DAV1)),
        Arguments.of("a feature this server does not know", List.of(99), Set.of()));
  }

  // An encode always carries its planned range, and the planned output feature covers only copies.
  @Test
  @DisplayName(
      "Should dispatch an encode with its planned range to a worker that lists no copy feature")
  void shouldDispatchAnEncodeWithItsPlannedRangeToAWorkerThatListsNoCopyFeature() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    registry.register(
        WORKER_ID, registration(WORKER_ID, libx264Encoder()), new CancellableObserver());
    var encode = encodeJob("h264").toBuilder();
    encode.getDecisionBuilder().getVideoBuilder().setOutputDynamicRange(sdrRange());

    assertThat(registry.dispatch(encode.build())).isPresent();
  }

  @Test
  @DisplayName(
      "Should count free slots and offer execution targets only on workers that list a copy's"
          + " rewrite")
  void shouldCountFreeSlotsAndOfferTargetsOnlyOnWorkersThatListACopysRewrite() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var listingWorkerId = UUID.randomUUID();
    registry.register(
        WORKER_ID,
        registrationSupporting(WORKER_ID, plannedOutputSupport()),
        new CancellableObserver());
    var stripping =
        copyJob(
                pqCopy()
                    .addBitstreamRewrites(BitstreamRewrite.BITSTREAM_REWRITE_STRIP_DOLBY_VISION))
            .getDecision();

    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID, stripping)).isZero();
    assertThat(registry.eligibleWorkers(SOURCE_NAMESPACE_ID, stripping)).isEmpty();

    var listingSessionId =
        registry.register(
            listingWorkerId,
            registrationSupporting(
                listingWorkerId,
                plannedOutputSupport()
                    .addBitstreamRewrites(BitstreamRewrite.BITSTREAM_REWRITE_STRIP_DOLBY_VISION)),
            new CancellableObserver());

    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID, stripping)).isOne();
    assertThat(registry.eligibleWorkers(SOURCE_NAMESPACE_ID, stripping))
        .containsExactly(new ExecutionTargetId(listingSessionId.toString()));
  }

  // An older worker refuses an encode whose output is not 8-bit SDR, and an encoder that passed the
  // 8-bit test encode can still fail a 10-bit one (streamarr/streamarr-transcode-worker#27), so
  // the server sends a 10-bit or HDR encode only to a worker whose encoder of the codec family
  // lists every feature the encode needs (#397).
  @ParameterizedTest(name = "{0}")
  @MethodSource("encodesBeyond8BitSdr")
  @DisplayName(
      "Should dispatch a 10-bit or HDR encode only to a worker whose encoder of the codec family"
          + " lists every feature that the encode needs")
  void shouldDispatchA10BitOrHdrEncodeOnlyToAWorkerWhoseEncoderListsEveryFeatureThatItNeeds(
      String encode, VideoDecision.Builder video, List<VideoEncodeFeature> needed) {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var listingWorkerId = UUID.randomUUID();
    var listingResponses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    var everyOtherFeature = EnumSet.complementOf(EnumSet.copyOf(needed));
    everyOtherFeature.removeAll(
        EnumSet.of(
            VideoEncodeFeature.VIDEO_ENCODE_FEATURE_UNSPECIFIED, VideoEncodeFeature.UNRECOGNIZED));
    var partialFeatures = new ArrayList<>(everyOtherFeature);
    partialFeatures.addAll(needed.subList(1, needed.size()));
    registry.register(
        WORKER_ID,
        registration(WORKER_ID, svtAv1Encoder(partialFeatures), libx264Encoder()),
        new CancellableObserver());
    var job = encodeJob(video);

    assertThat(registry.dispatch(job)).isEmpty();

    registry.register(
        listingWorkerId,
        registration(listingWorkerId, svtAv1Encoder(needed)),
        collecting(listingResponses));

    assertThat(registry.dispatch(job)).isPresent();
    assertThat(listingResponses).anyMatch(EstablishWorkerSessionResponse::hasStartVariant);
  }

  static Stream<Arguments> encodesBeyond8BitSdr() {
    return Stream.of(
        Arguments.of(
            "a 10-bit SDR encode",
            av1Encode(sdrRange()).setOutputBitDepth(10),
            List.of(VideoEncodeFeature.VIDEO_ENCODE_FEATURE_TEN_BIT)),
        Arguments.of(
            "an HDR10 encode",
            av1Encode(rangeOf(CompatibleBase.COMPATIBLE_BASE_PQ)).setOutputBitDepth(10),
            List.of(VideoEncodeFeature.VIDEO_ENCODE_FEATURE_PQ)),
        Arguments.of(
            "an HLG encode",
            av1Encode(rangeOf(CompatibleBase.COMPATIBLE_BASE_HLG)).setOutputBitDepth(10),
            List.of(VideoEncodeFeature.VIDEO_ENCODE_FEATURE_HLG)),
        Arguments.of(
            "an HDR10 encode of Dolby Vision without a compatible base",
            av1Encode(rangeOf(CompatibleBase.COMPATIBLE_BASE_PQ))
                .setOutputBitDepth(10)
                .setSourceDynamicRange(
                    VideoDynamicRange.newBuilder()
                        .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_NONE)
                        .setHdr10Plus(Presence.PRESENCE_ABSENT)
                        .setDolbyVisionPresent(DolbyVisionPresent.getDefaultInstance())),
            List.of(
                VideoEncodeFeature.VIDEO_ENCODE_FEATURE_DOLBY_VISION_TO_PQ,
                VideoEncodeFeature.VIDEO_ENCODE_FEATURE_PQ)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("eightBitSdrEncodes")
  @DisplayName(
      "Should dispatch an 8-bit SDR encode to a worker whose encoder lists no encode feature, also"
          + " when an older server names no bit depth")
  void shouldDispatchAn8BitSdrEncodeToAWorkerWhoseEncoderListsNoEncodeFeature(
      String encode, VideoDecision.Builder video) {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    registry.register(
        WORKER_ID, registration(WORKER_ID, svtAv1Encoder(List.of())), new CancellableObserver());

    assertThat(registry.dispatch(encodeJob(video))).isPresent();
  }

  static Stream<Arguments> eightBitSdrEncodes() {
    return Stream.of(
        Arguments.of("an 8-bit SDR encode", av1Encode(sdrRange()).setOutputBitDepth(8)),
        Arguments.of("an SDR encode from an older server", av1Encode(sdrRange())));
  }

  @Test
  @DisplayName(
      "Should count free slots and offer execution targets only on workers whose encoder lists an"
          + " HDR encode's feature")
  void shouldCountFreeSlotsAndOfferTargetsOnlyOnWorkersWhoseEncoderListsAnHdrEncodesFeature() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var listingWorkerId = UUID.randomUUID();
    registry.register(
        WORKER_ID,
        registration(
            WORKER_ID, svtAv1Encoder(List.of(VideoEncodeFeature.VIDEO_ENCODE_FEATURE_TEN_BIT))),
        new CancellableObserver());
    var hdr10 =
        encodeJob(av1Encode(rangeOf(CompatibleBase.COMPATIBLE_BASE_PQ)).setOutputBitDepth(10))
            .getDecision();

    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID, hdr10)).isZero();
    assertThat(registry.eligibleWorkers(SOURCE_NAMESPACE_ID, hdr10)).isEmpty();

    var listingSessionId =
        registry.register(
            listingWorkerId,
            registration(
                listingWorkerId,
                svtAv1Encoder(List.of(VideoEncodeFeature.VIDEO_ENCODE_FEATURE_PQ))),
            new CancellableObserver());

    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID, hdr10)).isOne();
    assertThat(registry.eligibleWorkers(SOURCE_NAMESPACE_ID, hdr10))
        .containsExactly(new ExecutionTargetId(listingSessionId.toString()));
  }

  // The decision plans an encode only in a codec family whose encoder a worker with a free slot
  // runs, so a busy worker's encode features must not plan an encode that only it writes. The
  // refusal of an encode that no free worker runs still counts busy workers' encoders.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName(
      "Should list only the encoders of workers with a free slot as encodable, and every connected"
          + " worker's encoders as verified")
  void shouldListOnlyTheEncodersOfWorkersWithAFreeSlotAsEncodableAndEveryWorkersAsVerified() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var idleWorkerId = UUID.randomUUID();
    registry.register(
        WORKER_ID,
        registration(WORKER_ID, svtAv1Encoder(List.of(VideoEncodeFeature.VIDEO_ENCODE_FEATURE_PQ))),
        new CancellableObserver());
    assertThat(registry.dispatch(encodeJob(av1Encode(sdrRange())))).isPresent();
    registry.register(
        idleWorkerId,
        registration(
            idleWorkerId,
            svtAv1Encoder(List.of(VideoEncodeFeature.VIDEO_ENCODE_FEATURE_TEN_BIT)).toBuilder()
                .addEncodeFeaturesValue(99)
                .build()),
        new CancellableObserver());

    assertThat(registry.encodableVideoEncoders(SOURCE_NAMESPACE_ID))
        .extracting(WorkerVerifiedEncoder::encodeFeatures)
        .containsExactly(Set.of(com.streamarr.server.domain.streaming.VideoEncodeFeature.TEN_BIT));
    assertThat(registry.verifiedVideoEncoders(SOURCE_NAMESPACE_ID))
        .extracting(WorkerVerifiedEncoder::encodeFeatures)
        .containsExactlyInAnyOrder(
            Set.of(com.streamarr.server.domain.streaming.VideoEncodeFeature.PQ),
            Set.of(com.streamarr.server.domain.streaming.VideoEncodeFeature.TEN_BIT));
  }

  // A software encode after a hardware encoder failure runs on the software encoder, so its plan
  // reads the software encoders apart from the hardware encoders of the same codec family
  // (ADR 0044).
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName(
      "Should list the software encoders apart from the hardware encoders, the encodable ones only"
          + " from workers with a free slot")
  void shouldListTheSoftwareEncodersApartFromTheHardwareEncoders() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var idleWorkerId = UUID.randomUUID();
    registry.register(
        WORKER_ID,
        withSoftwareEncoder(
            registration(WORKER_ID, av1Nvenc()),
            svtAv1Encoder(List.of(VideoEncodeFeature.VIDEO_ENCODE_FEATURE_PQ))),
        new CancellableObserver());
    assertThat(registry.dispatch(encodeJob(av1Encode(sdrRange())))).isPresent();
    registry.register(
        idleWorkerId,
        withSoftwareEncoder(
            registration(idleWorkerId, av1Nvenc()),
            svtAv1Encoder(List.of(VideoEncodeFeature.VIDEO_ENCODE_FEATURE_TEN_BIT))),
        new CancellableObserver());

    assertThat(registry.encodableSoftwareVideoEncoders(SOURCE_NAMESPACE_ID))
        .extracting(WorkerVerifiedEncoder::encodeFeatures)
        .containsExactly(Set.of(com.streamarr.server.domain.streaming.VideoEncodeFeature.TEN_BIT));
    assertThat(registry.verifiedSoftwareVideoEncoders(SOURCE_NAMESPACE_ID))
        .extracting(WorkerVerifiedEncoder::encodeFeatures)
        .containsExactlyInAnyOrder(
            Set.of(com.streamarr.server.domain.streaming.VideoEncodeFeature.PQ),
            Set.of(com.streamarr.server.domain.streaming.VideoEncodeFeature.TEN_BIT));
    assertThat(registry.verifiedVideoEncoders(SOURCE_NAMESPACE_ID))
        .extracting(encoder -> encoder.backend().encoder())
        .containsExactly("av1_nvenc", "av1_nvenc");
  }

  private static WorkerVerifiedVideoEncoder av1Nvenc() {
    return keyframeVerifiedHardwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "av1_nvenc");
  }

  private static WorkerRegistration withSoftwareEncoder(
      WorkerRegistration registration, WorkerVerifiedVideoEncoder softwareEncoder) {
    var withSoftware = registration.toBuilder();
    withSoftware.getCapabilitiesBuilder().addSoftwareVideoEncoders(softwareEncoder);
    return withSoftware.build();
  }

  // A later job attempt must run the pinned backend, which decides the variant's initialization
  // segment, but encode features that its output does not need must not keep it off a worker.
  @Test
  @DisplayName(
      "Should restart a pinned 8-bit SDR variant on a worker whose same encoder lists encode"
          + " features that the pin did not list")
  void shouldRestartAPinnedSdrVariantOnAWorkerWhoseSameEncoderListsFeaturesThatThePinDidNotList() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var sessionId =
        registry.register(
            WORKER_ID,
            registration(
                WORKER_ID,
                svtAv1Encoder(
                    List.of(
                        VideoEncodeFeature.VIDEO_ENCODE_FEATURE_TEN_BIT,
                        VideoEncodeFeature.VIDEO_ENCODE_FEATURE_PQ))),
            new CancellableObserver());
    var pinned = WorkerVerifiedEncoderFixture.libsvtav1();
    var sdr = encodeJob(av1Encode(sdrRange()).setOutputBitDepth(8));

    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID, sdr.getDecision(), pinned)).isOne();
    assertThat(registry.eligibleWorkers(SOURCE_NAMESPACE_ID, sdr.getDecision(), pinned))
        .containsExactly(new ExecutionTargetId(sessionId.toString()));
    assertThat(registry.dispatch(sdr, pinned)).isPresent();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName(
      "Should keep a pinned HDR10 variant off a worker whose same encoder does not list PQ")
  void shouldKeepAPinnedHdr10VariantOffAWorkerWhoseSameEncoderDoesNotListPq() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    registry.register(
        WORKER_ID,
        registration(
            WORKER_ID, svtAv1Encoder(List.of(VideoEncodeFeature.VIDEO_ENCODE_FEATURE_TEN_BIT))),
        new CancellableObserver());
    var pinned =
        WorkerVerifiedEncoder.builder()
            .backend(WorkerVerifiedEncoderFixture.libsvtav1().backend())
            .keyframeVerified(true)
            .encodeFeatures(Set.of(com.streamarr.server.domain.streaming.VideoEncodeFeature.PQ))
            .build();
    var hdr10 =
        encodeJob(av1Encode(rangeOf(CompatibleBase.COMPATIBLE_BASE_PQ)).setOutputBitDepth(10));

    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID, hdr10.getDecision(), pinned)).isZero();
    assertThat(registry.eligibleWorkers(SOURCE_NAMESPACE_ID, hdr10.getDecision(), pinned))
        .isEmpty();
    assertThat(registry.dispatch(hdr10, pinned)).isEmpty();
  }

  private static VideoDecision.Builder av1Encode(VideoDynamicRange outputRange) {
    return VideoDecision.newBuilder()
        .setMode(VideoMode.VIDEO_MODE_ENCODE)
        .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1)
        .setSourceDynamicRange(outputRange)
        .setOutputDynamicRange(outputRange);
  }

  private static VariantJob encodeJob(VideoDecision.Builder video) {
    var job = encodeJob("av1").toBuilder();
    job.getDecisionBuilder().setVideo(video);
    return job.build();
  }

  private static WorkerVerifiedVideoEncoder svtAv1Encoder(List<VideoEncodeFeature> features) {
    return softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "libsvtav1").toBuilder()
        .setKeyframeVerified(true)
        .addAllEncodeFeatures(features)
        .build();
  }

  private static VideoDynamicRange rangeOf(CompatibleBase base) {
    return sdrRange().toBuilder().setCompatibleBase(base).build();
  }

  // A later job attempt of a pinned variant carries the session's audio, so the pinned queries
  // agree with dispatch about which workers encode its layout.
  @Test
  @DisplayName(
      "Should count a pinned encode's free slots and targets only on probe version 7 workers when"
          + " its job names a quad layout")
  void
      shouldCountAPinnedEncodesFreeSlotsAndTargetsOnlyOnProbeVersion7WorkersWhenItsJobNamesAQuadLayout() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var version7WorkerId = UUID.randomUUID();
    registry.register(
        WORKER_ID, libx264RegistrationListingProbeVersion(WORKER_ID, 6), new CancellableObserver());
    var version7WorkerSessionId =
        registry.register(
            version7WorkerId,
            libx264RegistrationListingProbeVersion(version7WorkerId, 7),
            new CancellableObserver());
    var pinned = WorkerVerifiedEncoderFixture.libx264();
    var quad = h264EncodeNaming(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_QUAD);
    var stereo = h264EncodeNaming(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_STEREO);

    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID, quad, pinned)).isOne();
    assertThat(registry.eligibleWorkers(SOURCE_NAMESPACE_ID, quad, pinned))
        .containsExactly(new ExecutionTargetId(version7WorkerSessionId.toString()));
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID, stereo, pinned)).isEqualTo(2);
    assertThat(registry.eligibleWorkers(SOURCE_NAMESPACE_ID, stereo, pinned)).hasSize(2);
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
            .setVideo(
                VideoDecision.newBuilder()
                    .setMode(VideoMode.VIDEO_MODE_COPY)
                    .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC))
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

  @Test
  @DisplayName(
      "Should report a codec family as encoded in hardware only when a worker with a free slot"
          + " counts a hardware encoder for it")
  void
      shouldReportACodecFamilyAsEncodedInHardwareOnlyWhenAWorkerWithAFreeSlotCountsAHardwareEncoderForIt() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    registry.register(
        WORKER_ID,
        registration(
            WORKER_ID,
            keyframeVerifiedHardwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "av1_nvenc"),
            hardwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC, "hevc_nvenc"),
            softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")),
        new CancellableObserver());

    assertThat(registry.hardwareEncodableVideoCodecFamilies(SOURCE_NAMESPACE_ID))
        .containsExactly("av1");
    assertThat(registry.hardwareEncodableVideoCodecFamilies(UUID.randomUUID())).isEmpty();

    assertThat(registry.dispatch(encodeJob("h264"))).isPresent();

    assertThat(registry.hardwareEncodableVideoCodecFamilies(SOURCE_NAMESPACE_ID)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep a codec family among the verified hardware families when the worker that counts"
          + " its hardware encoder has no free slot")
  void
      shouldKeepACodecFamilyAmongTheVerifiedHardwareFamiliesWhenTheWorkerThatCountsItsHardwareEncoderHasNoFreeSlot() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    registry.register(
        WORKER_ID,
        registration(
            WORKER_ID,
            keyframeVerifiedHardwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "av1_nvenc"),
            hardwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC, "hevc_nvenc"),
            softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")),
        new CancellableObserver());

    assertThat(registry.dispatch(encodeJob("h264"))).isPresent();

    assertThat(registry.hardwareEncodableVideoCodecFamilies(SOURCE_NAMESPACE_ID)).isEmpty();
    assertThat(registry.verifiedHardwareVideoCodecFamilies(SOURCE_NAMESPACE_ID))
        .containsExactly("av1");
    assertThat(registry.verifiedHardwareVideoCodecFamilies(UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName(
      "Should start AV1 variants on the hardware AV1 worker until it has no free slot when a"
          + " software AV1 worker registered first")
  void
      shouldStartAv1VariantsOnTheHardwareAv1WorkerUntilItHasNoFreeSlotWhenASoftwareAv1WorkerRegisteredFirst() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    registerSoftwareThenHardwareAv1Workers(registry);

    var first = registry.dispatch(encodeJob("av1")).orElseThrow();
    var second = registry.dispatch(encodeJob("av1")).orElseThrow();

    assertThat(first.encoder()).map(encoder -> encoder.backend().encoder()).contains("av1_nvenc");
    assertThat(second.encoder()).map(encoder -> encoder.backend().encoder()).contains("libsvtav1");
  }

  @Test
  @DisplayName(
      "Should list the hardware AV1 worker first among an AV1 variant's execution targets when a"
          + " software AV1 worker registered first")
  void
      shouldListTheHardwareAv1WorkerFirstAmongAnAv1VariantsExecutionTargetsWhenASoftwareAv1WorkerRegisteredFirst() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var hardwareTarget = registerSoftwareThenHardwareAv1Workers(registry);

    var targets = registry.eligibleWorkers(SOURCE_NAMESPACE_ID, encodeJob("av1").getDecision());

    assertThat(targets).hasSize(2).first().isEqualTo(hardwareTarget);
  }

  @Test
  @DisplayName(
      "Should start a variant on the software worker when the hardware worker ordered next loses its"
          + " encoder while the dispatch waits on the first hardware worker")
  void
      shouldStartVariantOnSoftwareWorkerWhenHardwareWorkerOrderedNextLosesItsEncoderWhileDispatchWaitsOnFirstHardwareWorker()
          throws Exception {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var nvenc =
        keyframeVerifiedHardwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "h264_nvenc");
    var gated = new GatedDispatchObserver();
    var gatedSession = registry.register(WORKER_ID, registration(WORKER_ID, nvenc), gated);
    var withdrawnWorkerId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    var withdrawnRegistration = registration(withdrawnWorkerId, 2).toBuilder();
    withdrawnRegistration.getCapabilitiesBuilder().addVideoEncoders(nvenc);
    var withdrawnSession =
        registry.register(
            withdrawnWorkerId, withdrawnRegistration.build(), new CancellableObserver());
    var softwareWorkerId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    registry.register(
        softwareWorkerId,
        registration(softwareWorkerId, libx264Encoder()),
        new CancellableObserver());
    var running = encodeJob("h264");
    assertThat(
            registry.dispatchTo(
                new ExecutionTargetId(withdrawnSession.toString()),
                running,
                RemoteEncoderBackendMapper.workerVerifiedEncoder(nvenc).orElseThrow()))
        .isPresent();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var dispatching = executor.submit(() -> registry.dispatch(encodeJob("h264")));
      assertThat(gated.dispatchReached.await(5, TimeUnit.SECONDS)).isTrue();

      registry.checkReportedEncoderBackend(
          withdrawnWorkerId, withdrawnSession, libx264Started(running.getJobAttemptId()));
      registry.disconnect(WORKER_ID, gatedSession);
      gated.dispatchRelease.countDown();

      assertThat(dispatching.get(5, TimeUnit.SECONDS))
          .flatMap(VariantDispatch::encoder)
          .contains(WorkerVerifiedEncoderFixture.libx264());
    } finally {
      gated.dispatchRelease.countDown();
    }
  }

  private static ExecutionTargetId registerSoftwareThenHardwareAv1Workers(
      LiveWorkerConnectionRegistry registry) {
    var hardwareWorkerId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    registry.register(
        WORKER_ID,
        registration(
            WORKER_ID, softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "libsvtav1")),
        new CancellableObserver());
    var hardwareSession =
        registry.register(
            hardwareWorkerId,
            registration(
                    hardwareWorkerId,
                    keyframeVerifiedHardwareEncoder(
                        VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "av1_nvenc"))
                .toBuilder()
                .setAvailableSlots(1)
                .build(),
            new CancellableObserver());
    return new ExecutionTargetId(hardwareSession.toString());
  }

  @ParameterizedTest(name = "{2} encode with only {1}")
  @CsvSource({
    "VIDEO_CODEC_FAMILY_AV1, av1_nvenc, av1",
    "VIDEO_CODEC_FAMILY_HEVC, hevc_nvenc, hevc",
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
        LiveWorkerConnectionRegistry.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(WorkerSessionServerConfiguration.builder().build())
            .meterRegistry(meterRegistry)
            .heldRequests(new HeldSegmentRequests(new SimpleMeterRegistry()))
            .build();
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
      "Should credit a published media segment to the job attempt that uploaded it, and no"
          + " initialization segment")
  void shouldCreditAPublishedMediaSegmentToTheJobAttemptThatUploadedItAndNoInitializationSegment() {
    var events = new CapturingEventPublisher();
    var registry =
        LiveWorkerConnectionRegistry.builder()
            .eventPublisher(events)
            .configuration(WorkerSessionServerConfiguration.builder().build())
            .meterRegistry(new SimpleMeterRegistry())
            .heldRequests(new HeldSegmentRequests(new SimpleMeterRegistry()))
            .build();
    var workerRegistration = registration();
    var workerSessionId =
        registry.register(WORKER_ID, workerRegistration, collecting(new CopyOnWriteArrayList<>()));
    var job = variantJob();
    assertThat(registry.dispatch(job)).isPresent();
    var upload = uploadMetadata(workerSessionId, workerRegistration.getWorker(), job);

    registry.publishIfAuthorized(
        WORKER_ID,
        upload.toBuilder().setSegmentName(SegmentNames.INITIALIZATION_SEGMENT_NAME).build(),
        () -> SegmentPublication.PUBLISHED);
    registry.publishIfAuthorized(
        WORKER_ID,
        upload.toBuilder().setSegmentName(SegmentNames.mediaSegmentName(7)).build(),
        () -> SegmentPublication.PUBLISHED);

    assertThat(events.getEventsOfType(MediaSegmentPublishedEvent.class))
        .containsExactly(
            MediaSegmentPublishedEvent.builder()
                .streamSessionId(fromProto(job.getStreamSessionId()))
                .jobAttemptId(fromProto(job.getJobAttemptId()))
                .variantLabel(job.getVariant().getVariantLabel())
                .segmentIndex(7)
                .executionTarget(
                    registry
                        .runningExecutionTarget(
                            fromProto(job.getStreamSessionId()), job.getVariant().getVariantLabel())
                        .orElseThrow())
                .build());
  }

  @Test
  @DisplayName(
      "Should report each published media segment's production speed when the worker uploads it")
  void shouldReportEachPublishedMediaSegmentsProductionSpeedWhenTheWorkerUploadsIt() {
    var meterRegistry = new SimpleMeterRegistry();
    var registry = registryReportingTo(meterRegistry);
    var workerRegistration = registration();
    var workerSessionId =
        registry.register(WORKER_ID, workerRegistration, collecting(new CopyOnWriteArrayList<>()));
    var job = variantJob();
    assertThat(registry.dispatch(job)).isPresent();
    var metadata = uploadMetadata(workerSessionId, workerRegistration.getWorker(), job);

    registry.publishIfAuthorized(WORKER_ID, metadata, () -> SegmentPublication.PUBLISHED);
    registry.publishIfAuthorized(
        WORKER_ID,
        timedUploadMetadata(workerSessionId, workerRegistration.getWorker(), job),
        () -> SegmentPublication.PUBLISHED);

    var speed = productionSpeed(meterRegistry);
    assertThat(speed.count()).as("The initialization segment carries no timing").isOne();
    assertThat(speed.mean()).isEqualTo(3.0);
  }

  @Test
  @DisplayName(
      "Should start a reconnected worker's production speed afresh when its previous session"
          + " disconnected")
  void shouldStartAReconnectedWorkersProductionSpeedAfreshWhenItsPreviousSessionDisconnected() {
    var meterRegistry = new SimpleMeterRegistry();
    var registry = registryReportingTo(meterRegistry);
    var workerRegistration = registration();
    var workerSessionId =
        registry.register(WORKER_ID, workerRegistration, collecting(new CopyOnWriteArrayList<>()));
    var job = variantJob();
    assertThat(registry.dispatch(job)).isPresent();
    registry.publishIfAuthorized(
        WORKER_ID,
        timedUploadMetadata(workerSessionId, workerRegistration.getWorker(), job),
        () -> SegmentPublication.PUBLISHED);

    registry.disconnect(WORKER_ID, workerSessionId);
    assertThat(productionSpeed(meterRegistry)).isNull();
    registry.register(WORKER_ID, registration(), collecting(new CopyOnWriteArrayList<>()));

    assertThat(productionSpeed(meterRegistry)).isNotNull();
    assertThat(productionSpeed(meterRegistry).count()).isZero();
  }

  @Test
  @DisplayName("Should report no production speed when a media segment took no production time")
  void shouldReportNoProductionSpeedWhenAMediaSegmentTookNoProductionTime() {
    var meterRegistry = new SimpleMeterRegistry();
    var registry = registryReportingTo(meterRegistry);
    var workerRegistration = registration();
    var workerSessionId =
        registry.register(WORKER_ID, workerRegistration, collecting(new CopyOnWriteArrayList<>()));
    var job = variantJob();
    assertThat(registry.dispatch(job)).isPresent();

    registry.publishIfAuthorized(
        WORKER_ID,
        timedUploadMetadata(workerSessionId, workerRegistration.getWorker(), job).toBuilder()
            .setProductionTime(protoSeconds(0))
            .build(),
        () -> SegmentPublication.PUBLISHED);

    assertThat(productionSpeed(meterRegistry).count()).isZero();
  }

  @Test
  @DisplayName(
      "Should report no production speed when the worker disconnects during the publication")
  void shouldReportNoProductionSpeedWhenTheWorkerDisconnectsDuringThePublication() {
    var meterRegistry = new SimpleMeterRegistry();
    var registry = registryReportingTo(meterRegistry);
    var workerRegistration = registration();
    var workerSessionId =
        registry.register(WORKER_ID, workerRegistration, collecting(new CopyOnWriteArrayList<>()));
    var job = variantJob();
    assertThat(registry.dispatch(job)).isPresent();

    var outcome =
        registry.publishIfAuthorized(
            WORKER_ID,
            timedUploadMetadata(workerSessionId, workerRegistration.getWorker(), job),
            () -> {
              registry.disconnect(WORKER_ID, workerSessionId);
              return SegmentPublication.PUBLISHED;
            });

    assertThat(outcome).contains(SegmentPublication.PUBLISHED);
    assertThat(productionSpeed(meterRegistry)).isNull();
  }

  @Test
  @DisplayName(
      "Should report each worker's playback slots and the slots in use when a job attempt runs")
  void shouldReportEachWorkersPlaybackSlotsAndTheSlotsInUseWhenAJobAttemptRuns() {
    var meterRegistry = new SimpleMeterRegistry();
    var registry = registryReportingTo(meterRegistry);
    registry.register(
        WORKER_ID,
        registration().toBuilder().setAvailableSlots(2).build(),
        collecting(new CopyOnWriteArrayList<>()));

    assertThat(registry.dispatch(variantJob())).isPresent();

    assertThat(workerGauge(meterRegistry, "streamarr.streaming.playback_slots")).isEqualTo(2);
    assertThat(workerGauge(meterRegistry, "streamarr.streaming.playback_slots_in_use")).isOne();
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

    assertThat(
            registry.eligibleWorkers(SOURCE_NAMESPACE_ID, encodeJob("h264").getDecision(), pinned))
        .isEmpty();
    assertThat(registry.dispatch(job, pinned)).isEmpty();

    var verifiedWorkerSessionId =
        registry.register(
            verifiedWorkerId,
            registration(verifiedWorkerId, libx264Encoder()),
            new CancellableObserver());

    assertThat(
            registry.eligibleWorkers(SOURCE_NAMESPACE_ID, encodeJob("h264").getDecision(), pinned))
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

    assertThat(
            registry.eligibleWorkers(SOURCE_NAMESPACE_ID, encodeJob("h264").getDecision(), pinned))
        .isEmpty();
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
        registry.eligibleWorkers(
            SOURCE_NAMESPACE_ID,
            encodeJob("h264").getDecision(),
            WorkerVerifiedEncoderFixture.libx264());

    assertThat(targets).containsExactly(new ExecutionTargetId(libx264WorkerSessionId.toString()));
    assertThat(
            registry.eligibleWorkers(
                UUID.randomUUID(),
                encodeJob("h264").getDecision(),
                WorkerVerifiedEncoderFixture.libx264()))
        .isEmpty();
  }

  @Test
  @DisplayName(
      "Should count a pinned encode's free slots only on workers that advertise the pinned encoder backend")
  void shouldCountAPinnedEncodesFreeSlotsOnlyOnWorkersThatAdvertiseThePinnedEncoderBackend() {
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var libx264WorkerId = UUID.randomUUID();
    registry.register(
        WORKER_ID,
        registration(
            WORKER_ID,
            keyframeVerifiedHardwareEncoder(
                VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "h264_nvenc")),
        new CancellableObserver());
    registry.register(
        libx264WorkerId,
        registration(libx264WorkerId, libx264Encoder()),
        new CancellableObserver());
    var pinned = WorkerVerifiedEncoderFixture.libx264();
    var freeSlots =
        registry.availableSlots(SOURCE_NAMESPACE_ID, encodeJob("h264").getDecision(), pinned);

    registry.dispatch(encodeJob("h264"), pinned);

    assertThat(freeSlots).isOne();
    assertThat(
            registry.availableSlots(SOURCE_NAMESPACE_ID, encodeJob("h264").getDecision(), pinned))
        .isZero();
    assertThat(registry.availableSlots(SOURCE_NAMESPACE_ID, encodeJob("h264").getDecision()))
        .isOne();
    assertThat(registry.availableSlots(UUID.randomUUID(), encodeJob("h264").getDecision(), pinned))
        .isZero();
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
                .eligibleWorkers(
                    SOURCE_NAMESPACE_ID,
                    encodeJob("h264").getDecision(),
                    WorkerVerifiedEncoderFixture.libx264()))
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
          LiveWorkerConnectionRegistry.builder()
              .eventPublisher(new CapturingEventPublisher())
              .configuration(configuration)
              .meterRegistry(new SimpleMeterRegistry())
              .heldRequests(new HeldSegmentRequests(new SimpleMeterRegistry()))
              .build();
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

  private static WorkerRegistration registrationListingProbeVersion(
      UUID workerId, int probeVersion) {
    var registration = registration(workerId, 1).toBuilder();
    registration.getCapabilitiesBuilder().addProbeVersions(probeVersion);
    return registration.build();
  }

  private static WorkerRegistration registrationSupporting(
      UUID workerId, WorkerCapabilities.Builder copySupport) {
    var registration = registration(workerId, 1).toBuilder();
    registration.getCapabilitiesBuilder().mergeFrom(copySupport.build());
    return registration.build();
  }

  private static WorkerCapabilities.Builder plannedOutputSupport() {
    return WorkerCapabilities.newBuilder()
        .addVideoCopyFeatures(VideoCopyFeature.VIDEO_COPY_FEATURE_PLANNED_OUTPUT);
  }

  private static VariantJob copyJob(VideoDecision.Builder video) {
    return variantJob().toBuilder()
        .setDecision(
            TranscodeDecision.newBuilder()
                .setMode(TranscodeMode.TRANSCODE_MODE_REMUX)
                .setVideo(video))
        .build();
  }

  private static VideoDecision.Builder copyVideo() {
    return VideoDecision.newBuilder()
        .setMode(VideoMode.VIDEO_MODE_COPY)
        .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC);
  }

  private static VideoDecision.Builder pqCopy() {
    return copyVideo()
        .setOutputDynamicRange(
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
                .setHdr10Plus(Presence.PRESENCE_ABSENT)
                .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance()));
  }

  private static VideoDynamicRange sdrRange() {
    return VideoDynamicRange.newBuilder()
        .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
        .setHdr10Plus(Presence.PRESENCE_ABSENT)
        .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance())
        .build();
  }

  private static DolbyVisionOutput dolbyVisionOutput(DolbyVisionSampleEntry sampleEntry) {
    return DolbyVisionOutput.newBuilder()
        .setProfile(8)
        .setLevel(6)
        .setBaseLayerCompatibilityId(1)
        .setSampleEntry(sampleEntry)
        .build();
  }

  private static WorkerRegistration libx264RegistrationListingProbeVersion(
      UUID workerId, int probeVersion) {
    var registration = registration(workerId, libx264Encoder()).toBuilder();
    registration.getCapabilitiesBuilder().addProbeVersions(probeVersion);
    return registration.build();
  }

  private static TranscodeDecision h264EncodeNaming(AudioChannelLayout layout) {
    return encodeJob("h264").getDecision().toBuilder()
        .setAudio(
            AudioDecision.newBuilder()
                .setMode(AudioMode.AUDIO_MODE_TRANSCODE)
                .setCodec("aac")
                .setChannelLayout(layout))
        .build();
  }

  // The video is copied, so the job needs no video encoder.
  private static VariantJob audioEncodeJob(AudioChannelLayout layout) {
    return variantJob().toBuilder()
        .setDecision(
            TranscodeDecision.newBuilder()
                .setMode(TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE)
                .setVideo(
                    VideoDecision.newBuilder()
                        .setMode(VideoMode.VIDEO_MODE_COPY)
                        .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264))
                .setAudio(
                    AudioDecision.newBuilder()
                        .setMode(AudioMode.AUDIO_MODE_TRANSCODE)
                        .setCodec("flac")
                        .setChannelLayout(layout)))
        .build();
  }

  private static VariantJob encodeJob(String videoCodecFamily) {
    return variantJob().toBuilder()
        .setDecision(
            TranscodeDecision.newBuilder()
                .setMode(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE)
                .setVideo(
                    VideoDecision.newBuilder()
                        .setMode(VideoMode.VIDEO_MODE_ENCODE)
                        .setCodecFamily(
                            VideoCodecFamily.valueOf(
                                "VIDEO_CODEC_FAMILY_"
                                    + videoCodecFamily.toUpperCase(Locale.ROOT)))))
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

  private static LiveWorkerConnectionRegistry registryReportingTo(
      SimpleMeterRegistry meterRegistry) {
    return LiveWorkerConnectionRegistry.builder()
        .eventPublisher(new CapturingEventPublisher())
        .configuration(WorkerSessionServerConfiguration.builder().build())
        .meterRegistry(meterRegistry)
        .heldRequests(new HeldSegmentRequests(new SimpleMeterRegistry()))
        .build();
  }

  // java.time.Duration and the protobuf Duration share a simple name.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.google.protobuf.Duration protoSeconds(long seconds) {
    return com.google.protobuf.Duration.newBuilder().setSeconds(seconds).build();
  }

  private static SegmentUploadMetadata timedUploadMetadata(
      UUID workerSessionId, WorkerIdentity worker, VariantJob job) {
    return uploadMetadata(workerSessionId, worker, job).toBuilder()
        .setMediaDuration(protoSeconds(6))
        .setProductionTime(protoSeconds(2))
        .build();
  }

  private static DistributionSummary productionSpeed(SimpleMeterRegistry meterRegistry) {
    return meterRegistry
        .find("streamarr.streaming.production_speed")
        .tag("worker", WORKER_ID.toString())
        .summary();
  }

  private static double workerGauge(SimpleMeterRegistry meterRegistry, String name) {
    return meterRegistry.get(name).tag("worker", WORKER_ID.toString()).gauge().value();
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

  // A job on a worker's software encoder asks for it, so the worker runs it whichever encoder it
  // prefers (ADR 0044).
  private static VariantJob softwareEncoderAskedFor(VariantJob job) {
    var asked = job.toBuilder();
    asked.getDecisionBuilder().getVideoBuilder().setSoftwareEncoder(true);
    return asked.build();
  }
}
