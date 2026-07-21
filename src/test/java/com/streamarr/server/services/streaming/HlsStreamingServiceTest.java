package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.MaxConcurrentTranscodesException;
import com.streamarr.server.exceptions.MediaFileNotFoundException;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.services.concurrency.MutexFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;

@Tag("UnitTest")
@DisplayName("HLS Streaming Service Tests")
class HlsStreamingServiceTest {

  private FakeMediaFileRepository mediaFileRepository;
  private FakeTranscodeExecutor transcodeExecutor;
  private FakeSegmentStore segmentStore;
  private FakeFfprobeService ffprobeService;
  private FakePlaybackAuthorityGate authorityGate;
  private FakeRuntimeStreamSessionRegistry runtimeRegistry;
  private HlsStreamingService service;

  @BeforeEach
  void setUp() {
    mediaFileRepository = new FakeMediaFileRepository();
    transcodeExecutor = new FakeTranscodeExecutor();
    segmentStore = new FakeSegmentStore();
    ffprobeService = new FakeFfprobeService();
    authorityGate = new FakePlaybackAuthorityGate();
    runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    service = serviceWith(transcodeExecutor, runtimeRegistry);
  }

  private HlsStreamingService serviceWith(
      TranscodeExecutor executor, RuntimeStreamSessionRegistry registry) {
    var properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(3)
            .targetSegmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofSeconds(60))
            .build();
    var lifecycle = lifecycleWith(executor, registry, properties);
    return new HlsStreamingService(
        mediaFileRepository,
        executor,
        segmentStore,
        ffprobeService,
        new TranscodeDecisionService(),
        new QualityLadderService(),
        properties,
        authorityGate,
        registry,
        lifecycle,
        SegmentDeliveryCoordinator.builder()
            .runtimeRegistry(registry)
            .segmentStore(segmentStore)
            .transcodeExecutor(executor)
            .producerLifecycle(lifecycle)
            .properties(properties)
            .clock(Clock.systemUTC())
            .build());
  }

  private ProducerLifecycleService lifecycleWith(
      TranscodeExecutor executor,
      RuntimeStreamSessionRegistry registry,
      StreamingProperties properties) {
    return ProducerLifecycleService.builder()
        .transcodeExecutor(executor)
        .segmentStore(segmentStore)
        .properties(properties)
        .runtimeRegistry(registry)
        .sessionMutex(new MutexFactory<>())
        .build();
  }

  private StreamingOptions defaultOptions() {
    return StreamingOptions.builder()
        .quality(VideoQuality.AUTO)
        .supportedCodecs(List.of("h264"))
        .build();
  }

  private MediaFile seedMediaFile() {
    var file =
        MediaFile.builder()
            .filepathUri("/media/movies/test.mkv")
            .filename("test.mkv")
            .status(MediaFileStatus.MATCHED)
            .size(1_000_000L)
            .build();
    return mediaFileRepository.save(file);
  }

  private StreamSession createSession(UUID mediaFileId, UUID profileId, StreamingOptions options) {
    return service.createSession(createStreamSessionCommand(mediaFileId, profileId, options));
  }

  private Optional<StreamSession> accessSession(StreamSession session) {
    return service.accessSession(playbackRequest(session));
  }

  private Optional<StreamSession> accessMissingSession(UUID streamSessionId) {
    return service.accessSession(
        PlaybackRequest.builder()
            .streamSessionId(streamSessionId)
            .authority(defaultPlaybackAuthorityBuilder().build())
            .build());
  }

  @Test
  @DisplayName("Should assign session ID and media file when creating session")
  void shouldAssignSessionIdAndMediaFileWhenCreatingSession() {
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getSessionId()).isNotNull();
    assertThat(session.getMediaFileId()).isEqualTo(file.getId());
  }

  @Test
  @DisplayName("Should propagate authority failure when accessing a session")
  void shouldPropagateAuthorityFailureWhenAccessingSession() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    authorityGate.failWith(
        new DataAccessResourceFailureException("authority database unavailable"));

    assertThatThrownBy(() -> accessSession(session)).isInstanceOf(DataAccessException.class);
  }

  @Test
  @DisplayName("Should propagate authority failure and start no transcode when creating a session")
  void shouldPropagateAuthorityFailureWhenCreatingSession() {
    var file = seedMediaFile();
    var fileId = file.getId();
    var profileId = UUID.randomUUID();
    var options = defaultOptions();
    authorityGate.failWith(
        new DataAccessResourceFailureException("authority database unavailable"));

    assertThatThrownBy(() -> createSession(fileId, profileId, options))
        .isInstanceOf(DataAccessException.class);
    assertThat(transcodeExecutor.getStarted()).isEmpty();
  }

  @Test
  @DisplayName("Should populate media probe when creating session")
  void shouldPopulateMediaProbeWhenCreatingSession() {
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getMediaProbe()).isNotNull();
  }

  @Test
  @DisplayName("Should populate transcode decision when creating session")
  void shouldPopulateTranscodeDecisionWhenCreatingSession() {
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getTranscodeDecision()).isNotNull();
  }

  @Test
  @DisplayName("Should start transcode when creating session")
  void shouldStartTranscodeWhenCreatingSession() {
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(transcodeExecutor.getStarted()).contains(session.getSessionId());
    assertThat(transcodeExecutor.isRunning(session.getSessionId())).isTrue();
  }

  @Test
  @DisplayName("Should throw when media file not found")
  void shouldThrowWhenMediaFileNotFound() {
    var invalidId = UUID.randomUUID();
    var profileId = UUID.randomUUID();

    var options = defaultOptions();

    assertThatThrownBy(() -> createSession(invalidId, profileId, options))
        .isInstanceOf(MediaFileNotFoundException.class);
  }

  @Test
  @DisplayName("Should return session when session exists")
  void shouldReturnSessionWhenSessionExists() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    var retrieved = accessSession(session);

    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().getSessionId()).isEqualTo(session.getSessionId());
  }

  @Test
  @DisplayName("Should return empty when session does not exist")
  void shouldReturnEmptyWhenSessionDoesNotExist() {
    var result = accessMissingSession(UUID.randomUUID());

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should return empty when playback authority does not own runtime session")
  void shouldReturnEmptyWhenPlaybackAuthorityDoesNotOwnRuntimeSession() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var lastAccessedAt = session.getLastAccessedAt();
    var request =
        PlaybackRequest.builder()
            .streamSessionId(session.getSessionId())
            .authority(defaultPlaybackAuthorityBuilder().build())
            .build();

    var result = service.accessSession(request);

    assertThat(result).isEmpty();
    assertThat(session.getLastAccessedAt()).isEqualTo(lastAccessedAt);
  }

  @Test
  @DisplayName("Should return empty when live playback authority is denied")
  void shouldReturnEmptyWhenLivePlaybackAuthorityIsDenied() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var lastAccessedAt = session.getLastAccessedAt();
    authorityGate.deny();

    var result = accessSession(session);

    assertThat(result).isEmpty();
    assertThat(session.getLastAccessedAt()).isEqualTo(lastAccessedAt);
  }

  @Test
  @DisplayName("Should refuse session creation when live playback authority is denied")
  void shouldRefuseSessionCreationWhenLivePlaybackAuthorityIsDenied() {
    var file = seedMediaFile();
    var command = createStreamSessionCommand(file.getId(), UUID.randomUUID(), defaultOptions());
    authorityGate.deny();

    assertThatThrownBy(() -> service.createSession(command))
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(transcodeExecutor.getStarted()).isEmpty();
  }

  @Test
  @DisplayName("Should update last accessed timestamp when session is retrieved")
  void shouldUpdateLastAccessedTimestampWhenSessionIsRetrieved() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var initialAccess = session.getLastAccessedAt();

    var retrieved = accessSession(session);

    assertThat(retrieved.get().getLastAccessedAt()).isAfterOrEqualTo(initialAccess);
  }

  @Test
  @DisplayName("Should remove session and stop transcode when session is destroyed")
  void shouldRemoveSessionAndStopTranscodeWhenSessionIsDestroyed() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    service.destroySession(session.getSessionId());

    assertThat(accessSession(session)).isEmpty();
    assertThat(transcodeExecutor.getStopped()).contains(session.getSessionId());
    assertThat(transcodeExecutor.isRunning(session.getSessionId())).isFalse();
  }

  @Test
  @DisplayName("Should delete stored segments when destroy fails to stop the transcode")
  void shouldDeleteStoredSegmentsWhenDestroyFailsToStopTranscode() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    segmentStore.addSegment(session.getSessionId(), "segment0.ts", "data".getBytes());
    transcodeExecutor.failOnStop(session.getSessionId());
    var sessionId = session.getSessionId();

    assertThatThrownBy(() -> service.destroySession(sessionId))
        .isInstanceOf(TranscodeException.class);

    assertThat(accessSession(session)).isEmpty();
    assertThat(segmentStore.segmentExists(session.getSessionId(), "segment0.ts")).isFalse();
  }

  @Test
  @DisplayName("Should reject full transcode when at concurrency limit")
  void shouldRejectFullTranscodeWhenAtConcurrencyLimit() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("hevc")
            .audioCodec("aac")
            .bitrate(5_000_000L)
            .build());

    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.FULL_HD_1080P)
            .supportedCodecs(List.of("h264"))
            .build();

    for (int i = 0; i < 3; i++) {
      var file = seedMediaFile();
      createSession(file.getId(), UUID.randomUUID(), options);
    }

    var oneMore = seedMediaFile();
    var oneMoreId = oneMore.getId();
    var profileId = UUID.randomUUID();

    assertThatThrownBy(() -> createSession(oneMoreId, profileId, options))
        .isInstanceOf(MaxConcurrentTranscodesException.class);
  }

  @Test
  @DisplayName("Should not count suspended sessions against transcode limit")
  void shouldNotCountSuspendedSessionsAgainstTranscodeLimit() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("hevc")
            .audioCodec("aac")
            .bitrate(5_000_000L)
            .build());

    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.FULL_HD_1080P)
            .supportedCodecs(List.of("h264"))
            .build();

    var sessions = new java.util.ArrayList<StreamSession>();
    for (int i = 0; i < 3; i++) {
      var file = seedMediaFile();
      sessions.add(createSession(file.getId(), UUID.randomUUID(), options));
    }

    var suspended = sessions.getFirst();
    suspended.setHandle(new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));

    var oneMore = seedMediaFile();
    var newSession = createSession(oneMore.getId(), UUID.randomUUID(), options);

    assertThat(newSession).isNotNull();
  }

  @Test
  @DisplayName("Should allow remux sessions when at transcode concurrency limit")
  void shouldAllowRemuxSessionsWhenAtTranscodeConcurrencyLimit() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("hevc")
            .audioCodec("aac")
            .bitrate(5_000_000L)
            .build());

    var transcodeOptions =
        StreamingOptions.builder()
            .quality(VideoQuality.FULL_HD_1080P)
            .supportedCodecs(List.of("h264"))
            .build();

    for (int i = 0; i < 3; i++) {
      var file = seedMediaFile();
      createSession(file.getId(), UUID.randomUUID(), transcodeOptions);
    }

    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("h264")
            .audioCodec("aac")
            .bitrate(5_000_000L)
            .build());

    var remuxOptions = StreamingOptions.builder().supportedCodecs(List.of("h264")).build();
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), remuxOptions);

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  @Test
  @DisplayName("Should transcode video when video codec is incompatible")
  void shouldTranscodeVideoWhenVideoCodecIsIncompatible() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(90))
            .framerate(24.0)
            .width(1920)
            .height(1080)
            .videoCodec("hevc")
            .audioCodec("aac")
            .bitrate(8_000_000L)
            .build());

    var file = seedMediaFile();
    var options = StreamingOptions.builder().supportedCodecs(List.of("h264")).build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(session.getTranscodeDecision().videoCodecFamily()).isEqualTo("h264");
  }

  @Test
  @DisplayName("Should start multiple variants when auto quality with full transcode")
  void shouldStartMultipleVariantsWhenAutoQualityWithFullTranscode() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("hevc")
            .audioCodec("aac")
            .bitrate(8_000_000L)
            .build());

    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getVariants()).hasSizeGreaterThan(1);
    assertThat(session.getVariantHandles()).hasSizeGreaterThan(1);
  }

  @Test
  @DisplayName("Should roll back the session when the first transcode startup fails")
  void shouldRollbackSessionWhenFirstTranscodeStartupFails() {
    var failingExecutor = new FailingStartupTranscodeExecutor(0, segmentStore);
    service = serviceWith(failingExecutor, runtimeRegistry);
    var file = seedMediaFile();

    var command = createStreamSessionCommand(file.getId(), UUID.randomUUID(), defaultOptions());
    assertThatThrownBy(() -> service.createSession(command))
        .isInstanceOf(TranscodeException.class)
        .hasMessage("Simulated transcode startup failure");

    assertStartupRolledBack(failingExecutor);
  }

  @Test
  @DisplayName("Should roll back running transcodes when a later variant startup fails")
  void shouldRollbackRunningTranscodesWhenLaterVariantStartupFails() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("hevc")
            .audioCodec("aac")
            .bitrate(8_000_000L)
            .build());
    var failingExecutor = new FailingStartupTranscodeExecutor(1, segmentStore);
    service = serviceWith(failingExecutor, runtimeRegistry);
    var file = seedMediaFile();

    var command = createStreamSessionCommand(file.getId(), UUID.randomUUID(), defaultOptions());
    assertThatThrownBy(() -> service.createSession(command))
        .isInstanceOf(TranscodeException.class)
        .hasMessage("Simulated transcode startup failure");

    assertThat(failingExecutor.getAttemptedRequests()).hasSize(2);
    assertStartupRolledBack(failingExecutor);
  }

  @Test
  @DisplayName("Should preserve the startup failure when rollback cleanup fails")
  void shouldPreserveStartupFailureWhenRollbackCleanupFails() {
    var failingExecutor = new FailingStartupTranscodeExecutor(0, segmentStore);
    failingExecutor.failOnStop();
    service = serviceWith(failingExecutor, runtimeRegistry);
    var file = seedMediaFile();

    var startupFailure =
        catchThrowable(() -> createSession(file.getId(), UUID.randomUUID(), defaultOptions()));

    assertThat(startupFailure)
        .isInstanceOf(TranscodeException.class)
        .hasMessage("Simulated transcode startup failure");
    assertThat(startupFailure.getSuppressed())
        .singleElement()
        .satisfies(
            cleanupFailure ->
                assertThat(cleanupFailure)
                    .isInstanceOf(TranscodeException.class)
                    .hasMessage("Simulated rollback cleanup failure"));
    assertStartupRolledBack(failingExecutor);
  }

  private void assertStartupRolledBack(FailingStartupTranscodeExecutor executor) {
    var sessionId = executor.getAttemptedRequests().getFirst().sessionId();
    assertThat(runtimeRegistry.findById(sessionId)).isEmpty();
    assertThat(executor.getRunningCount()).isZero();
    assertThat(segmentStore.segmentExists(sessionId, "startup.ts")).isFalse();
  }

  @Test
  @DisplayName("Should use single variant when auto quality with remux")
  void shouldUseSingleVariantWhenAutoQualityWithRemux() {
    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getVariants()).isEmpty();
    assertThat(session.getHandle()).isNotNull();
  }

  @Test
  @DisplayName("Should use single variant when explicit quality is specified")
  void shouldUseSingleVariantWhenExplicitQualityIsSpecified() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("hevc")
            .audioCodec("aac")
            .bitrate(8_000_000L)
            .build());

    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.HIGH_720P)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getVariants()).isEmpty();
    assertThat(session.getHandle()).isNotNull();
  }

  @Test
  @DisplayName("Should pass variant label to transcode request for ABR session")
  void shouldPassVariantLabelToTranscodeRequestForAbrSession() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("hevc")
            .audioCodec("aac")
            .bitrate(8_000_000L)
            .build());

    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getVariants()).hasSizeGreaterThan(1);
    assertThat(transcodeExecutor.getStartedVariants())
        .containsExactlyInAnyOrderElementsOf(
            session.getVariants().stream().map(v -> v.label()).toList());
  }

  @Test
  @DisplayName("Should return session immediately after creation")
  void shouldReturnSessionImmediatelyAfterCreation() {
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(accessSession(session)).isPresent();
  }

  @Test
  @DisplayName("Should not throw when destroying nonexistent session")
  void shouldNotThrowWhenDestroyingNonexistentSession() {
    service.destroySession(UUID.randomUUID());

    assertThat(transcodeExecutor.getStopped()).isEmpty();
  }

  @Test
  @DisplayName("Should not destroy session when destroy requested by another profile")
  void shouldNotDestroySessionWhenDestroyRequestedByAnotherProfile() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    service.destroySession(session.getSessionId(), UUID.randomUUID());

    assertThat(accessSession(session)).isPresent();
    assertThat(transcodeExecutor.getStopped()).doesNotContain(session.getSessionId());
  }

  @Test
  @DisplayName("Should log ownership miss when destroy requested by another profile")
  void shouldLogOwnershipMissWhenDestroyRequestedByAnotherProfile() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    var logger = (Logger) LoggerFactory.getLogger(HlsStreamingService.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      service.destroySession(session.getSessionId(), UUID.randomUUID());
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list)
        .filteredOn(event -> event.getLevel() == Level.WARN)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anyMatch(message -> message.contains(session.getSessionId().toString()));
  }

  @Test
  @DisplayName("Should remove session and stop transcode when destroy requested by owning profile")
  void shouldRemoveSessionAndStopTranscodeWhenDestroyRequestedByOwningProfile() {
    var file = seedMediaFile();
    var profileId = UUID.randomUUID();
    var session = createSession(file.getId(), profileId, defaultOptions());

    service.destroySession(session.getSessionId(), profileId);

    assertThat(accessSession(session)).isEmpty();
    assertThat(transcodeExecutor.getStopped()).contains(session.getSessionId());
  }

  @Test
  @DisplayName("Should return all sessions when multiple sessions created")
  void shouldReturnAllSessionsWhenMultipleSessionsCreated() {
    var file1 = seedMediaFile();
    var file2 = seedMediaFile();
    createSession(file1.getId(), UUID.randomUUID(), defaultOptions());
    createSession(file2.getId(), UUID.randomUUID(), defaultOptions());

    var all = service.getAllSessions();

    assertThat(all).hasSize(2);
  }

  @Test
  @DisplayName("Should return active session count when sessions exist")
  void shouldReturnActiveSessionCountWhenSessionsExist() {
    var file1 = seedMediaFile();
    var file2 = seedMediaFile();
    var session1 = createSession(file1.getId(), UUID.randomUUID(), defaultOptions());
    createSession(file2.getId(), UUID.randomUUID(), defaultOptions());

    service.destroySession(session1.getSessionId());

    assertThat(service.getActiveSessionCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should truncate variants when exceeding available slots")
  void shouldTruncateVariantsWhenExceedingAvailableSlots() {
    var properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(2)
            .targetSegmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofSeconds(60))
            .build();
    var limitedExecutor = new FakeTranscodeExecutor();
    var limitedRegistry = new FakeRuntimeStreamSessionRegistry();
    var limitedLifecycle = lifecycleWith(limitedExecutor, limitedRegistry, properties);
    var limitedService =
        new HlsStreamingService(
            mediaFileRepository,
            limitedExecutor,
            segmentStore,
            ffprobeService,
            new TranscodeDecisionService(),
            new QualityLadderService(),
            properties,
            authorityGate,
            limitedRegistry,
            limitedLifecycle,
            SegmentDeliveryCoordinator.builder()
                .runtimeRegistry(limitedRegistry)
                .segmentStore(segmentStore)
                .transcodeExecutor(limitedExecutor)
                .producerLifecycle(limitedLifecycle)
                .properties(properties)
                .clock(Clock.systemUTC())
                .build());

    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("hevc")
            .audioCodec("aac")
            .bitrate(8_000_000L)
            .build());

    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session =
        limitedService.createSession(
            createStreamSessionCommand(file.getId(), UUID.randomUUID(), options));

    assertThat(session.getVariants()).hasSize(2);
  }

  @Test
  @DisplayName("Should truncate variants to executor slots available now")
  void shouldTruncateVariantsToExecutorSlotsAvailableNow() {
    transcodeExecutor.setAvailableSlots(2);
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("hevc")
            .audioCodec("aac")
            .bitrate(8_000_000L)
            .build());
    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getVariants()).hasSize(2);
    assertThat(transcodeExecutor.getStartedVariants()).containsExactlyInAnyOrder("1080p", "720p");
  }

  @Test
  @DisplayName("Should truncate to one variant when only one slot is available")
  void shouldTruncateToOneVariantWhenOnlyOneSlotAvailable() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("hevc")
            .audioCodec("aac")
            .bitrate(8_000_000L)
            .build());

    var singleVariantOptions =
        StreamingOptions.builder()
            .quality(VideoQuality.FULL_HD_1080P)
            .supportedCodecs(List.of("h264"))
            .build();

    for (int i = 0; i < 2; i++) {
      var file = seedMediaFile();
      createSession(file.getId(), UUID.randomUUID(), singleVariantOptions);
    }

    var abrOptions =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), abrOptions);

    assertThat(session.getVariants()).hasSize(1);
  }

  @Test
  @DisplayName("Should reject ABR session when all transcode slots are full")
  void shouldRejectAbrSessionWhenAllTranscodeSlotsAreFull() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("hevc")
            .audioCodec("aac")
            .bitrate(5_000_000L)
            .build());

    var singleVariantOptions =
        StreamingOptions.builder()
            .quality(VideoQuality.FULL_HD_1080P)
            .supportedCodecs(List.of("h264"))
            .build();

    for (int i = 0; i < 3; i++) {
      var file = seedMediaFile();
      createSession(file.getId(), UUID.randomUUID(), singleVariantOptions);
    }

    var abrOptions =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();
    var abrFile = seedMediaFile();
    var abrFileId = abrFile.getId();
    var profileId = UUID.randomUUID();

    assertThatThrownBy(() -> createSession(abrFileId, profileId, abrOptions))
        .isInstanceOf(MaxConcurrentTranscodesException.class);
  }

  private static final class FailingStartupTranscodeExecutor extends FakeTranscodeExecutor {

    private final int successfulStarts;
    private final FakeSegmentStore segmentStore;
    private boolean failOnStop;
    private final List<TranscodeRequest> attemptedRequests = new ArrayList<>();

    private FailingStartupTranscodeExecutor(int successfulStarts, FakeSegmentStore segmentStore) {
      this.successfulStarts = successfulStarts;
      this.segmentStore = segmentStore;
    }

    @Override
    public TranscodeHandle start(TranscodeRequest request) {
      attemptedRequests.add(request);
      segmentStore.addSegment(request.sessionId(), "startup.ts", new byte[] {1});
      if (attemptedRequests.size() > successfulStarts) {
        throw new TranscodeException("Simulated transcode startup failure");
      }
      return super.start(request);
    }

    @Override
    public void stop(UUID sessionId) {
      super.stop(sessionId);
      if (failOnStop) {
        throw new TranscodeException("Simulated rollback cleanup failure");
      }
    }

    private void failOnStop() {
      failOnStop = true;
    }

    private List<TranscodeRequest> getAttemptedRequests() {
      return List.copyOf(attemptedRequests);
    }
  }
}
