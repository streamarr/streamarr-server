package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.streamarr.server.exceptions.MaxConcurrentTranscodesException;
import com.streamarr.server.exceptions.MediaFileNotFoundException;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeStreamSessionRepository;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.services.concurrency.MutexFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@Tag("UnitTest")
@DisplayName("HLS Streaming Service Tests")
class HlsStreamingServiceTest {

  private FakeMediaFileRepository mediaFileRepository;
  private FakeTranscodeExecutor transcodeExecutor;
  private FakeSegmentStore segmentStore;
  private FakeFfprobeService ffprobeService;
  private StreamingProperties properties;
  private RuntimeStreamSessionRegistry runtimeRegistry;
  private HlsStreamingService service;

  @BeforeEach
  void setUp() {
    mediaFileRepository = new FakeMediaFileRepository();
    transcodeExecutor = new FakeTranscodeExecutor();
    segmentStore = new FakeSegmentStore();
    ffprobeService = new FakeFfprobeService();
    properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(3)
            .segmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofSeconds(60))
            .build();
    var decisionService = new TranscodeDecisionService();

    var qualityLadderService = new QualityLadderService();
    runtimeRegistry = new FakeStreamSessionRepository();

    service =
        new HlsStreamingService(
            mediaFileRepository,
            transcodeExecutor,
            segmentStore,
            ffprobeService,
            decisionService,
            qualityLadderService,
            properties,
            runtimeRegistry,
            new MutexFactory<>());
  }

  @Test
  @DisplayName("Should stop a late transcode when destroy wins during startup")
  void shouldStopLateTranscodeWhenDestroyWinsDuringStartup() throws Exception {
    var file = seedMediaFile();
    var streamSessionId = UUID.randomUUID();
    var blockingExecutor = new BlockingTranscodeExecutor();
    var racingRegistry = new FakeStreamSessionRepository();
    var reservation = racingRegistry.reserve(streamSessionId).orElseThrow();
    var racingService = createService(blockingExecutor, racingRegistry);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var creation =
          executor.submit(
              () ->
                  racingService.createSession(
                      CreateRuntimeStreamSessionCommand.builder()
                          .streamSessionId(streamSessionId)
                          .mediaFileId(file.getId())
                          .profileId(UUID.randomUUID())
                          .options(defaultOptions())
                          .initialLastAccessedAt(Instant.now())
                          .reservation(reservation)
                          .build()));
      assertThat(blockingExecutor.awaitStart()).isTrue();

      racingService.destroySession(streamSessionId);
      blockingExecutor.releaseStart();
      assertThatThrownBy(() -> creation.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(com.streamarr.server.exceptions.SessionNotFoundException.class);
    }

    assertThat(racingService.accessSession(streamSessionId)).isEmpty();
    assertThat(blockingExecutor.isRunning(streamSessionId)).isFalse();
  }

  @Test
  @DisplayName("Should reject runtime creation when reservation attachment is refused")
  void shouldRejectRuntimeCreationWhenReservationAttachmentIsRefused() {
    var file = seedMediaFile();
    var streamSessionId = UUID.randomUUID();
    var rejectingRegistry = new RejectingAttachRegistry();
    var reservation = rejectingRegistry.reserve(streamSessionId).orElseThrow();
    var rejectingService = createService(transcodeExecutor, rejectingRegistry);

    assertThatThrownBy(
            () ->
                rejectingService.createSession(
                    runtimeCreationCommand(streamSessionId, file.getId(), reservation)))
        .isInstanceOf(com.streamarr.server.exceptions.SessionNotFoundException.class);
    assertThat(transcodeExecutor.getStarted()).doesNotContain(streamSessionId);
  }

  @Test
  @DisplayName("Should reject runtime creation when transcode startup cannot begin")
  void shouldRejectRuntimeCreationWhenTranscodeStartupCannotBegin() {
    var file = seedMediaFile();
    var streamSessionId = UUID.randomUUID();
    var rejectingRegistry = new RejectingTranscodeStartRegistry();
    var reservation = rejectingRegistry.reserve(streamSessionId).orElseThrow();
    var rejectingService = createService(transcodeExecutor, rejectingRegistry);

    assertThatThrownBy(
            () ->
                rejectingService.createSession(
                    runtimeCreationCommand(streamSessionId, file.getId(), reservation)))
        .isInstanceOf(com.streamarr.server.exceptions.SessionNotFoundException.class);
    assertThat(transcodeExecutor.getStarted()).doesNotContain(streamSessionId);
  }

  @Test
  @DisplayName("Should fence a reserved session before runtime shutdown returns")
  void shouldFenceReservedSessionBeforeRuntimeShutdownReturns() {
    var streamSessionId = UUID.randomUUID();
    var shutdownRegistry = new FakeStreamSessionRepository();
    var reservation = shutdownRegistry.reserve(streamSessionId).orElseThrow();
    var shutdownService = createService(transcodeExecutor, shutdownRegistry);

    shutdownService.shutdownRuntime();

    assertThat(
            shutdownRegistry.attach(
                reservation, StreamSession.builder().sessionId(streamSessionId).build()))
        .isFalse();
    assertThat(shutdownRegistry.beginTranscodeStart(streamSessionId)).isEmpty();
  }

  @Test
  @DisplayName("Should wait for in-flight starter before runtime shutdown returns")
  void shouldWaitForInFlightStarterBeforeRuntimeShutdownReturns() throws Exception {
    var file = seedMediaFile();
    var streamSessionId = UUID.randomUUID();
    var blockingExecutor = new BlockingTranscodeExecutor();
    var shutdownRegistry = new FakeStreamSessionRepository();
    var reservation = shutdownRegistry.reserve(streamSessionId).orElseThrow();
    var shutdownService = createService(blockingExecutor, shutdownRegistry);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var creation =
          executor.submit(
              () ->
                  shutdownService.createSession(
                      CreateRuntimeStreamSessionCommand.builder()
                          .streamSessionId(streamSessionId)
                          .mediaFileId(file.getId())
                          .profileId(UUID.randomUUID())
                          .options(defaultOptions())
                          .initialLastAccessedAt(Instant.now())
                          .reservation(reservation)
                          .build()));
      assertThat(blockingExecutor.awaitStart()).isTrue();
      var shutdown = executor.submit(shutdownService::shutdownRuntime);

      try {
        assertThatThrownBy(() -> shutdown.get(200, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
      } finally {
        blockingExecutor.releaseStart();
      }

      assertThatThrownBy(() -> creation.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(com.streamarr.server.exceptions.SessionNotFoundException.class);
      shutdown.get(5, TimeUnit.SECONDS);
    }

    assertThat(blockingExecutor.isRunning(streamSessionId)).isFalse();
  }

  @Test
  @DisplayName("Should isolate a stop failure while shutting down runtime sessions")
  void shouldIsolateStopFailureWhileShuttingDownRuntimeSessions() {
    var shutdownExecutor = new SelectiveStopFailureTranscodeExecutor();
    var shutdownRegistry = new OrderedFenceRegistry();
    var shutdownService = createService(shutdownExecutor, shutdownRegistry);
    var failingSession =
        RuntimeStreamSessionTestDriver.create(
            shutdownService,
            shutdownRegistry,
            seedMediaFile().getId(),
            UUID.randomUUID(),
            defaultOptions());
    var healthySession =
        RuntimeStreamSessionTestDriver.create(
            shutdownService,
            shutdownRegistry,
            seedMediaFile().getId(),
            UUID.randomUUID(),
            defaultOptions());
    shutdownExecutor.failStopping(failingSession.getSessionId());
    shutdownRegistry.fenceInOrder(failingSession.getSessionId(), healthySession.getSessionId());

    assertThatNoException().isThrownBy(shutdownService::shutdownRuntime);

    assertThat(shutdownExecutor.isRunning(healthySession.getSessionId())).isFalse();
    assertThat(shutdownExecutor.isRunning(failingSession.getSessionId())).isFalse();
    assertThat(shutdownExecutor.getStopped()).contains(healthySession.getSessionId());
    assertThat(shutdownExecutor.getForceStopAllAttempts()).isEqualTo(1);
    assertThat(shutdownService.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should retain failed late startup bookkeeping when its stop fails")
  void shouldRetainFailedLateStartupBookkeepingWhenItsStopFails() {
    var file = seedMediaFile();
    var streamSessionId = UUID.randomUUID();
    var rejectingRegistry = new RejectingCompletedStartRegistry();
    var reservation = rejectingRegistry.reserve(streamSessionId).orElseThrow();
    var failingExecutor = new StopFailureTranscodeExecutor();
    var rejectingService = createService(failingExecutor, rejectingRegistry);

    assertThatThrownBy(
            () ->
                rejectingService.createSession(
                    runtimeCreationCommand(streamSessionId, file.getId(), reservation)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("stop failed");

    rejectingRegistry.releaseReservation(reservation);
    assertThat(rejectingRegistry.releaseTerminal(streamSessionId)).isFalse();
    assertThat(rejectingRegistry.reserve(streamSessionId)).isEmpty();
    assertThat(failingExecutor.isRunning(streamSessionId)).isTrue();

    assertThat(rejectingService.terminateRuntime(streamSessionId)).isTrue();
    assertThat(failingExecutor.isRunning(streamSessionId)).isFalse();
    var replacement = rejectingRegistry.reserve(streamSessionId);
    assertThat(replacement).isPresent();
    rejectingRegistry.releaseReservation(replacement.orElseThrow());
  }

  private HlsStreamingService createService(
      TranscodeExecutor executor, RuntimeStreamSessionRegistry repository) {
    return new HlsStreamingService(
        mediaFileRepository,
        executor,
        segmentStore,
        ffprobeService,
        new TranscodeDecisionService(),
        new QualityLadderService(),
        properties,
        repository,
        new MutexFactory<>());
  }

  private StreamSession createSession(UUID mediaFileId, UUID profileId, StreamingOptions options) {
    return RuntimeStreamSessionTestDriver.create(
        service, runtimeRegistry, mediaFileId, profileId, options);
  }

  private StreamingOptions defaultOptions() {
    return StreamingOptions.builder()
        .quality(VideoQuality.AUTO)
        .supportedCodecs(List.of("h264"))
        .build();
  }

  private CreateRuntimeStreamSessionCommand runtimeCreationCommand(
      UUID streamSessionId, UUID mediaFileId, RuntimeSessionReservation reservation) {
    return CreateRuntimeStreamSessionCommand.builder()
        .streamSessionId(streamSessionId)
        .mediaFileId(mediaFileId)
        .profileId(UUID.randomUUID())
        .options(defaultOptions())
        .initialLastAccessedAt(Instant.now())
        .reservation(reservation)
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

  @Test
  @DisplayName("Should assign session ID and media file when creating session")
  void shouldAssignSessionIdAndMediaFileWhenCreatingSession() {
    var file = seedMediaFile();

    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    assertThat(session.getSessionId()).isNotNull();
    assertThat(session.getMediaFileId()).isEqualTo(file.getId());
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

    var retrieved = service.accessSession(session.getSessionId());

    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().getSessionId()).isEqualTo(session.getSessionId());
  }

  @Test
  @DisplayName("Should return empty when session does not exist")
  void shouldReturnEmptyWhenSessionDoesNotExist() {
    var result = service.accessSession(UUID.randomUUID());

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should preserve committed last accessed timestamp when session is retrieved")
  void shouldPreserveCommittedLastAccessedTimestampWhenSessionIsRetrieved() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var initialAccess = session.getLastAccessedAt();

    var retrieved = service.accessSession(session.getSessionId());

    assertThat(retrieved.get().getLastAccessedAt()).isEqualTo(initialAccess);
  }

  @Test
  @DisplayName("Should remove session and stop transcode when session is destroyed")
  void shouldRemoveSessionAndStopTranscodeWhenSessionIsDestroyed() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    service.destroySession(session.getSessionId());

    assertThat(service.accessSession(session.getSessionId())).isEmpty();
    assertThat(transcodeExecutor.getStopped()).contains(session.getSessionId());
    assertThat(transcodeExecutor.isRunning(session.getSessionId())).isFalse();
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
  @DisplayName("Should keep previously transcoded segments when relocating")
  void shouldKeepPreviouslyTranscodedSegmentsWhenRelocating() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    segmentStore.addSegment(session.getSessionId(), "segment0.ts", new byte[] {1});

    service.resumeSessionIfNeeded(session.getSessionId(), "segment100.ts");

    // Segments are addressed on the absolute timeline, so earlier segments stay valid.
    assertThat(segmentStore.segmentExists(session.getSessionId(), "segment0.ts")).isTrue();
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

    assertThat(service.accessSession(session.getSessionId())).isPresent();
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

    assertThat(service.accessSession(session.getSessionId())).isPresent();
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

    assertThat(service.accessSession(session.getSessionId())).isEmpty();
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
    var limitedProperties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(2)
            .segmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofSeconds(60))
            .build();
    var limitedRegistry = new FakeStreamSessionRepository();
    var limitedService =
        new HlsStreamingService(
            mediaFileRepository,
            new FakeTranscodeExecutor(),
            segmentStore,
            ffprobeService,
            new TranscodeDecisionService(),
            new QualityLadderService(),
            limitedProperties,
            limitedRegistry,
            new MutexFactory<>());

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
        RuntimeStreamSessionTestDriver.create(
            limitedService, limitedRegistry, file.getId(), UUID.randomUUID(), options);

    assertThat(session.getVariants()).hasSize(2);
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

  @Test
  @DisplayName("Should restart FFmpeg when segment is missing from suspended session")
  void shouldRestartFfmpegWhenSegmentIsMissingFromSuspendedSession() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
    transcodeExecutor.markDead(session.getSessionId());

    service.resumeSessionIfNeeded(session.getSessionId(), "segment5.ts");

    assertThat(transcodeExecutor.isRunning(session.getSessionId())).isTrue();
    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should not restart FFmpeg when session is actively transcoding")
  void shouldNotRestartFfmpegWhenSessionIsActivelyTranscoding() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var startedBefore = transcodeExecutor.getStarted().size();

    service.resumeSessionIfNeeded(session.getSessionId(), "segment0.ts");

    assertThat(transcodeExecutor.getStarted()).hasSize(startedBefore);
  }

  @Test
  @DisplayName("Should not restart FFmpeg when segment already exists on disk")
  void shouldNotRestartFfmpegWhenSegmentAlreadyExistsOnDisk() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
    transcodeExecutor.markDead(session.getSessionId());
    segmentStore.addSegment(session.getSessionId(), "segment5.ts", new byte[] {0x47});

    service.resumeSessionIfNeeded(session.getSessionId(), "segment5.ts");

    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.SUSPENDED);
  }

  @Test
  @DisplayName("Should not throw when resuming nonexistent session")
  void shouldNotThrowWhenResumingNonexistentSession() {
    assertThatNoException()
        .isThrownBy(() -> service.resumeSessionIfNeeded(UUID.randomUUID(), "segment0.ts"));
  }

  @Test
  @DisplayName("Should preserve committed last accessed time when resuming suspended session")
  void shouldPreserveCommittedLastAccessedTimeWhenResumingSuspendedSession() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
    session.setLastAccessedAt(Instant.now().minusSeconds(200));
    transcodeExecutor.markDead(session.getSessionId());
    var oldAccessTime = session.getLastAccessedAt();

    service.resumeSessionIfNeeded(session.getSessionId(), "segment5.ts");

    assertThat(session.getLastAccessedAt()).isEqualTo(oldAccessTime);
  }

  @Test
  @DisplayName("Should resume with correct start number when segment is TS format")
  void shouldResumeWithCorrectStartNumberWhenSegmentIsTsFormat() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
    transcodeExecutor.markDead(session.getSessionId());

    service.resumeSessionIfNeeded(session.getSessionId(), "segment5.ts");

    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);
    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.startNumber()).isEqualTo(5);
    assertThat(lastRequest.seekPosition()).isEqualTo(30);
  }

  @Test
  @DisplayName("Should resume with correct start number when segment is fMP4 format")
  void shouldResumeWithCorrectStartNumberWhenSegmentIsFmp4Format() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
    transcodeExecutor.markDead(session.getSessionId());

    service.resumeSessionIfNeeded(session.getSessionId(), "segment12.m4s");

    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);
    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.startNumber()).isEqualTo(12);
    assertThat(lastRequest.seekPosition()).isEqualTo(72);
  }

  @Test
  @DisplayName("Should resume with correct start number when segment includes variant path")
  void shouldResumeWithCorrectStartNumberWhenSegmentIncludesVariantPath() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
    transcodeExecutor.markDead(session.getSessionId());

    service.resumeSessionIfNeeded(session.getSessionId(), "720p/segment3.ts");

    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);
    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.startNumber()).isEqualTo(3);
    assertThat(lastRequest.seekPosition()).isEqualTo(18);
  }

  @Test
  @DisplayName("Should resume at beginning when segment name has no index")
  void shouldResumeAtBeginningWhenSegmentNameHasNoIndex() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
    transcodeExecutor.markDead(session.getSessionId());

    service.resumeSessionIfNeeded(session.getSessionId(), "init.mp4");

    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);
    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.startNumber()).isZero();
    assertThat(lastRequest.seekPosition()).isZero();
  }

  @Test
  @DisplayName("Should resume at beginning when segment is first")
  void shouldResumeAtBeginningWhenSegmentIsFirst() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
    transcodeExecutor.markDead(session.getSessionId());

    service.resumeSessionIfNeeded(session.getSessionId(), "segment0.ts");

    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);
    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.startNumber()).isZero();
    assertThat(lastRequest.seekPosition()).isZero();
  }

  @Test
  @DisplayName("Should restart all variant transcodes when ABR session is resumed")
  void shouldRestartAllVariantTranscodesWhenAbrSessionIsResumed() {
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
    var variantLabels = session.getVariants().stream().map(v -> v.label()).toList();

    for (var label : variantLabels) {
      session.setVariantHandle(label, new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
      transcodeExecutor.markDead(session.getSessionId(), label);
    }

    var requestsBefore = transcodeExecutor.getStartedRequests().size();
    service.resumeSessionIfNeeded(session.getSessionId(), "segment5.ts");
    var resumeRequests =
        transcodeExecutor
            .getStartedRequests()
            .subList(requestsBefore, transcodeExecutor.getStartedRequests().size());

    assertThat(resumeRequests).hasSize(variantLabels.size());
    assertThat(resumeRequests).extracting(TranscodeRequest::startNumber).containsOnly(5);
    assertThat(resumeRequests).extracting(TranscodeRequest::seekPosition).containsOnly(30);
    assertThat(resumeRequests)
        .extracting(TranscodeRequest::variantLabel)
        .containsExactlyInAnyOrderElementsOf(variantLabels);
    for (var label : variantLabels) {
      assertThat(session.getVariantHandle(label).status()).isEqualTo(TranscodeStatus.ACTIVE);
    }
  }

  @Test
  @DisplayName(
      "Should relocate the transcode when the requested segment is behind the encoder start")
  void shouldRelocateTheTranscodeWhenTheRequestedSegmentIsBehindTheEncoderStart() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    // Move the encoder forward first: segment50 is far ahead of fresh output.
    service.resumeSessionIfNeeded(session.getSessionId(), "segment50.ts");

    service.resumeSessionIfNeeded(session.getSessionId(), "segment10.ts");

    // The encoder started at segment50 and will never produce segment10.
    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.seekPosition()).isEqualTo(60);
    assertThat(lastRequest.startNumber()).isEqualTo(10);
  }

  @Test
  @DisplayName("Should relocate the transcode when the requested segment is far ahead of progress")
  void shouldRelocateTheTranscodeWhenTheRequestedSegmentIsFarAheadOfProgress() {
    var file = seedMediaFile();
    var profileId = UUID.randomUUID();
    var session = createSession(file.getId(), profileId, defaultOptions());

    service.resumeSessionIfNeeded(session.getSessionId(), "segment100.ts");

    // Nothing near segment100 has been produced; waiting would stall the player.
    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.seekPosition()).isEqualTo(600);
    assertThat(lastRequest.startNumber()).isEqualTo(100);
  }

  @Test
  @DisplayName("Should wait when the requested segment is near the encoder start")
  void shouldWaitWhenTheRequestedSegmentIsNearTheEncoderStart() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var requestsBefore = transcodeExecutor.getStartedRequests().size();

    service.resumeSessionIfNeeded(session.getSessionId(), "segment2.ts");

    // The encoder started at segment0 and will reach segment2 shortly.
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(requestsBefore);
  }

  @Test
  @DisplayName("Should wait when the encoder is within the forward gap of the request")
  void shouldWaitWhenTheEncoderIsWithinTheForwardGapOfTheRequest() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    segmentStore.addSegment(session.getSessionId(), "segment96.ts", new byte[] {1});
    var requestsBefore = transcodeExecutor.getStartedRequests().size();

    service.resumeSessionIfNeeded(session.getSessionId(), "segment100.ts");

    // segment96 exists, so the encoder is close behind the request.
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(requestsBefore);
  }

  @Test
  @DisplayName("Should not relocate when the requested segment already exists")
  void shouldNotRelocateWhenTheRequestedSegmentAlreadyExists() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    segmentStore.addSegment(session.getSessionId(), "segment10.ts", new byte[] {1});
    var requestsBefore = transcodeExecutor.getStartedRequests().size();

    service.resumeSessionIfNeeded(session.getSessionId(), "segment10.ts");

    assertThat(transcodeExecutor.getStartedRequests()).hasSize(requestsBefore);
  }

  @Test
  @DisplayName("Should resume at the absolute segment position when resuming")
  void shouldResumeAtTheAbsoluteSegmentPositionWhenResuming() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
    transcodeExecutor.markDead(session.getSessionId());

    service.resumeSessionIfNeeded(session.getSessionId(), "segment5.ts");

    // The timeline is absolute: segment5 always covers [30s, 36s).
    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.seekPosition()).isEqualTo(30);
    assertThat(lastRequest.startNumber()).isEqualTo(5);
  }

  private static final class BlockingTranscodeExecutor implements TranscodeExecutor {

    private final FakeTranscodeExecutor delegate = new FakeTranscodeExecutor();
    private final CountDownLatch startEntered = new CountDownLatch(1);
    private final CountDownLatch allowStart = new CountDownLatch(1);

    @Override
    public TranscodeHandle start(TranscodeRequest request) {
      startEntered.countDown();
      await(allowStart);
      return delegate.start(request);
    }

    private boolean awaitStart() throws InterruptedException {
      return startEntered.await(5, TimeUnit.SECONDS);
    }

    private void releaseStart() {
      allowStart.countDown();
    }

    @Override
    public void stop(UUID sessionId) {
      delegate.stop(sessionId);
    }

    @Override
    public void forceStopAll() {
      delegate.forceStopAll();
    }

    @Override
    public boolean isRunning(UUID sessionId) {
      return delegate.isRunning(sessionId);
    }

    @Override
    public boolean isRunning(UUID sessionId, String variantLabel) {
      return delegate.isRunning(sessionId, variantLabel);
    }

    @Override
    public boolean isHealthy() {
      return delegate.isHealthy();
    }

    private static void await(CountDownLatch latch) {
      try {
        latch.await();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Interrupted while controlling transcode startup", exception);
      }
    }
  }

  private static final class RejectingAttachRegistry extends FakeStreamSessionRepository {

    @Override
    public boolean attach(RuntimeSessionReservation reservation, StreamSession session) {
      return false;
    }
  }

  private static final class RejectingTranscodeStartRegistry extends FakeStreamSessionRepository {

    @Override
    public java.util.Optional<RuntimeTranscodeStart> beginTranscodeStart(UUID sessionId) {
      return java.util.Optional.empty();
    }
  }

  private static final class SelectiveStopFailureTranscodeExecutor extends FakeTranscodeExecutor {

    private UUID failingSessionId;

    void failStopping(UUID sessionId) {
      failingSessionId = sessionId;
    }

    @Override
    public void stop(UUID sessionId) {
      if (sessionId.equals(failingSessionId)) {
        throw new IllegalStateException("stop failed");
      }
      super.stop(sessionId);
    }
  }

  private static final class OrderedFenceRegistry extends FakeStreamSessionRepository {

    private List<UUID> fenceOrder = List.of();

    private void fenceInOrder(UUID first, UUID second) {
      fenceOrder = List.of(first, second);
    }

    @Override
    public java.util.Collection<UUID> fenceAll() {
      super.fenceAll();
      return fenceOrder;
    }
  }

  private static final class RejectingCompletedStartRegistry extends FakeStreamSessionRepository {

    @Override
    public boolean completeTranscodeStart(RuntimeTranscodeStart start) {
      terminalize(start.sessionId());
      return super.completeTranscodeStart(start);
    }
  }

  private static final class StopFailureTranscodeExecutor extends FakeTranscodeExecutor {

    private boolean failNextStop = true;

    @Override
    public void stop(UUID sessionId) {
      if (failNextStop) {
        failNextStop = false;
        throw new IllegalStateException("stop failed");
      }
      super.stop(sessionId);
    }
  }
}
