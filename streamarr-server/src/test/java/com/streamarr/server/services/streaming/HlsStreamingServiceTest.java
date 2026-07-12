package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
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
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.MaxConcurrentTranscodesException;
import com.streamarr.server.exceptions.MediaFileNotFoundException;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeMediaSourceCatalog;
import com.streamarr.server.fakes.FakePlaybackTranscodeJobService;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeStreamSessionRepository;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.streaming.worker.InspectJobQuery;
import com.streamarr.server.services.streaming.worker.InspectJobResult;
import com.streamarr.server.services.streaming.worker.StartJobCommand;
import com.streamarr.server.services.streaming.worker.StartJobResult;
import com.streamarr.server.services.streaming.worker.StopJobCommand;
import com.streamarr.server.services.streaming.worker.StopJobResult;
import com.streamarr.server.services.streaming.worker.TranscodeWorkerPort;
import com.streamarr.server.services.streaming.worker.WorkerTarget;
import com.streamarr.transcode.engine.model.RenditionObservation;
import com.streamarr.transcode.engine.model.RenditionRequest;
import com.streamarr.transcode.engine.model.RenditionState;
import com.streamarr.transcode.engine.model.TranscodeJobObservation;
import com.streamarr.transcode.engine.model.TranscodeJobState;
import com.streamarr.transcode.engine.model.TranscodeMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

@Tag("UnitTest")
@DisplayName("HLS Streaming Service Tests")
class HlsStreamingServiceTest {

  private FakeMediaFileRepository mediaFileRepository;
  private FakeTranscodeExecutor transcodeExecutor;
  private FakeSegmentStore segmentStore;
  private FakeFfprobeService ffprobeService;
  private FakePlaybackTranscodeJobService transcodeJobService;
  private FakeMediaSourceCatalog mediaSourceCatalog;
  private StreamingProperties properties;
  private RuntimeStreamSessionRegistry runtimeRegistry;
  private HlsStreamingService service;

  @BeforeEach
  void setUp() {
    mediaFileRepository = new FakeMediaFileRepository();
    transcodeExecutor = new FakeTranscodeExecutor();
    segmentStore = new FakeSegmentStore();
    ffprobeService = new FakeFfprobeService();
    transcodeJobService = new FakePlaybackTranscodeJobService();
    mediaSourceCatalog = new FakeMediaSourceCatalog();
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
            segmentStore,
            ffprobeService,
            decisionService,
            qualityLadderService,
            properties,
            runtimeRegistry,
            new MutexFactory<>(),
            transcodeJobService,
            mediaSourceCatalog);
  }

  @Test
  @DisplayName("Should reject runtime creation when reservation attachment is refused")
  void shouldRejectRuntimeCreationWhenReservationAttachmentIsRefused() {
    var file = seedMediaFile();
    var streamSessionId = UUID.randomUUID();
    var rejectingRegistry = new RejectingAttachRegistry();
    var reservation = rejectingRegistry.reserve(streamSessionId).orElseThrow();
    var rejectingService = createService(rejectingRegistry);

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
    var shutdownService = createService(shutdownRegistry);

    shutdownService.shutdownRuntime();

    assertThat(
            shutdownRegistry.attach(
                reservation, StreamSession.builder().sessionId(streamSessionId).build()))
        .isFalse();
    assertThat(shutdownRegistry.beginTranscodeStart(streamSessionId)).isEmpty();
  }

  @Test
  @DisplayName("Should finish fencing when shutdown cleanup remains pending")
  void shouldFinishFencingWhenShutdownCleanupRemainsPending() {
    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    transcodeJobService.returnTerminalCleanup(RuntimeTranscodeCleanup.PENDING);
    var logger = (Logger) LoggerFactory.getLogger(HlsStreamingService.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);

    try {
      service.shutdownRuntime();
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(transcodeJobService.terminalCleanupAttempts())
        .containsExactly(session.getSessionId());
    assertThat(appender.list)
        .filteredOn(event -> event.getLevel() == Level.WARN)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anyMatch(message -> message.contains(session.getSessionId().toString()));
  }

  @Test
  @DisplayName("Should retain segments and terminal authority while exact cleanup is pending")
  void shouldRetainSegmentsAndTerminalAuthorityWhileExactCleanupIsPending() {
    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    segmentStore.addSegment(session.getSessionId(), "segment0.ts", new byte[] {1});
    transcodeJobService.returnTerminalCleanup(RuntimeTranscodeCleanup.PENDING);

    var quiescent = service.terminateRuntime(session.getSessionId());

    assertThat(quiescent).isFalse();
    assertThat(segmentStore.segmentExists(session.getSessionId(), "segment0.ts")).isTrue();
    assertThat(transcodeJobService.terminalCleanupAttempts())
        .containsExactly(session.getSessionId());
  }

  @Test
  @DisplayName("Should isolate a stop failure while shutting down runtime sessions")
  void shouldIsolateStopFailureWhileShuttingDownRuntimeSessions() {
    var shutdownRegistry = new OrderedFenceRegistry();
    var shutdownService = createService(shutdownRegistry);
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
    transcodeJobService.failTerminalCleanup(
        failingSession.getSessionId(), new IllegalStateException("stop failed"));
    shutdownRegistry.fenceInOrder(failingSession.getSessionId(), healthySession.getSessionId());

    assertThatNoException().isThrownBy(shutdownService::shutdownRuntime);

    assertThat(transcodeJobService.terminalCleanupAttempts())
        .containsExactly(failingSession.getSessionId(), healthySession.getSessionId());
    assertThat(shutdownService.getAllSessions()).isEmpty();
  }

  private HlsStreamingService createService(RuntimeStreamSessionRegistry repository) {
    return new HlsStreamingService(
        mediaFileRepository,
        segmentStore,
        ffprobeService,
        new TranscodeDecisionService(),
        new QualityLadderService(),
        properties,
        repository,
        new MutexFactory<>(),
        transcodeJobService,
        mediaSourceCatalog);
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

    assertThat(transcodeJobService.startCommands())
        .singleElement()
        .satisfies(command -> assertThat(command.sessionId()).isEqualTo(session.getSessionId()));
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
  @DisplayName("Should remove session after exact transcode cleanup when session is destroyed")
  void shouldRemoveSessionAfterExactTranscodeCleanupWhenSessionIsDestroyed() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    service.destroySession(session.getSessionId());

    assertThat(service.accessSession(session.getSessionId())).isEmpty();
    assertThat(transcodeJobService.terminalCleanupAttempts())
        .containsExactly(session.getSessionId());
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
    var sessions = fillTranscodeCapacity();

    var suspended = sessions.getFirst();
    transcodeJobService.suspend(suspended.getSessionId());

    var oneMore = seedMediaFile();
    var newSession = createSession(oneMore.getId(), UUID.randomUUID(), fullHdOptions());

    assertThat(newSession).isNotNull();
  }

  @Test
  @DisplayName("Should not count a completed whole job against transcode capacity")
  void shouldNotCountCompletedWholeJobAgainstTranscodeCapacity() {
    var sessions = fillTranscodeCapacity();
    transcodeJobService.observe(
        sessions.getFirst().getSessionId(),
        com.streamarr.transcode.engine.model.TranscodeJobState.COMPLETED,
        0);

    var oneMore = seedMediaFile();

    assertThat(createSession(oneMore.getId(), UUID.randomUUID(), fullHdOptions())).isNotNull();
  }

  @Test
  @DisplayName("Should consume capacity when active whole-job inspection is unavailable")
  void shouldConsumeCapacityWhenActiveWholeJobInspectionIsUnavailable() {
    var sessions = fillTranscodeCapacity();
    transcodeJobService.makeInspectionUnavailable(sessions.getFirst().getSessionId());
    var oneMore = seedMediaFile();
    var mediaFileId = oneMore.getId();
    var profileId = UUID.randomUUID();
    var options = fullHdOptions();

    assertThatThrownBy(() -> createSession(mediaFileId, profileId, options))
        .isInstanceOf(MaxConcurrentTranscodesException.class);
  }

  @Test
  @DisplayName("Should consume capacity while a whole job is admitting")
  void shouldConsumeCapacityWhileWholeJobIsAdmitting() {
    var sessions = fillTranscodeCapacity();
    transcodeJobService.observe(sessions.getFirst().getSessionId(), TranscodeJobState.ADMITTING, 0);
    var oneMore = seedMediaFile();
    var mediaFileId = oneMore.getId();
    var profileId = UUID.randomUUID();
    var options = fullHdOptions();

    assertThatThrownBy(() -> createSession(mediaFileId, profileId, options))
        .isInstanceOf(MaxConcurrentTranscodesException.class);
  }

  @Test
  @DisplayName("Should reserve final transcode slot while whole-job start is unresolved")
  void shouldReserveFinalTranscodeSlotWhileWholeJobStartIsUnresolved() throws Exception {
    var worker = new BlockingTranscodeWorker();
    var limitedProperties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(1)
            .segmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofSeconds(60))
            .build();
    var limitedRegistry = new FakeStreamSessionRepository();
    var blockingJobs =
        DefaultPlaybackTranscodeJobService.builder()
            .worker(worker)
            .workerTarget(worker.target())
            .runtimeRegistry(limitedRegistry)
            .sessionMutexes(new MutexFactory<>())
            .build();
    var limitedService =
        new HlsStreamingService(
            mediaFileRepository,
            segmentStore,
            ffprobeService,
            new TranscodeDecisionService(),
            new QualityLadderService(),
            limitedProperties,
            limitedRegistry,
            new MutexFactory<>(),
            blockingJobs,
            mediaSourceCatalog);
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
    var firstFile = seedMediaFile();
    var secondFile = seedMediaFile();
    Throwable secondOutcome;

    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () ->
                  RuntimeStreamSessionTestDriver.create(
                      limitedService,
                      limitedRegistry,
                      firstFile.getId(),
                      UUID.randomUUID(),
                      fullHdOptions()));
      assertThat(worker.awaitStart()).isTrue();
      try {
        secondOutcome =
            catchThrowable(
                () ->
                    RuntimeStreamSessionTestDriver.create(
                        limitedService,
                        limitedRegistry,
                        secondFile.getId(),
                        UUID.randomUUID(),
                        fullHdOptions()));
      } finally {
        worker.releaseStart();
      }
      assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
    }

    assertThat(secondOutcome).isInstanceOf(MaxConcurrentTranscodesException.class);
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
    assertThat(transcodeJobService.startCommands().getLast().renditions())
        .hasSameSizeAs(session.getVariants());
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
    assertThat(transcodeJobService.startCommands().getLast().renditions())
        .singleElement()
        .satisfies(
            rendition -> assertThat(rendition.label()).isEqualTo(RenditionRequest.DEFAULT_VARIANT));
  }

  @Test
  @DisplayName("Should create remux session when source bitrate is unavailable")
  void shouldCreateRemuxSessionWhenSourceBitrateIsUnavailable() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("h264")
            .audioCodec("aac")
            .bitrate(0)
            .build());
    var file = seedMediaFile();
    var options = StreamingOptions.builder().supportedCodecs(List.of("h264")).build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  @Test
  @DisplayName("Should create audio-transcode session when source bitrate is unavailable")
  void shouldCreateAudioTranscodeSessionWhenSourceBitrateIsUnavailable() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(23.976)
            .width(1920)
            .height(1080)
            .videoCodec("h264")
            .audioCodec("ac3")
            .bitrate(0)
            .build());
    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .supportedCodecs(List.of("h264"))
            .supportedAudioCodecs(List.of("aac"))
            .build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
  }

  @Test
  @DisplayName("Should create remux session when source framerate is unavailable")
  void shouldCreateRemuxSessionWhenSourceFramerateIsUnavailable() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(Double.NaN)
            .width(1920)
            .height(1080)
            .videoCodec("h264")
            .audioCodec("aac")
            .bitrate(5_000_000L)
            .build());
    var file = seedMediaFile();
    var options = StreamingOptions.builder().supportedCodecs(List.of("h264")).build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  @Test
  @DisplayName("Should use a safe execution framerate when remux source reports zero")
  void shouldUseSafeExecutionFramerateWhenRemuxSourceReportsZero() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(0)
            .width(1920)
            .height(1080)
            .videoCodec("h264")
            .audioCodec("aac")
            .bitrate(5_000_000L)
            .build());
    var file = seedMediaFile();
    var options = StreamingOptions.builder().supportedCodecs(List.of("h264")).build();

    createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(transcodeJobService.startCommands().getLast().execution().framerate())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("Should create audio-transcode session when source framerate is unavailable")
  void shouldCreateAudioTranscodeSessionWhenSourceFramerateIsUnavailable() {
    ffprobeService.setDefaultProbe(
        MediaProbe.builder()
            .duration(Duration.ofMinutes(120))
            .framerate(Double.NaN)
            .width(1920)
            .height(1080)
            .videoCodec("h264")
            .audioCodec("ac3")
            .bitrate(5_000_000L)
            .build());
    var file = seedMediaFile();
    var options =
        StreamingOptions.builder()
            .supportedCodecs(List.of("h264"))
            .supportedAudioCodecs(List.of("aac"))
            .build();

    var session = createSession(file.getId(), UUID.randomUUID(), options);

    assertThat(session.getTranscodeDecision().transcodeMode())
        .isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
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
    assertThat(transcodeJobService.startCommands().getLast().renditions())
        .singleElement()
        .satisfies(
            rendition -> assertThat(rendition.label()).isEqualTo(RenditionRequest.DEFAULT_VARIANT));
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
    assertThat(transcodeJobService.startCommands().getLast().renditions())
        .extracting(com.streamarr.transcode.engine.model.RenditionSpec::label)
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
  @DisplayName("Should clean transcode when destroy is requested by owning profile")
  void shouldCleanTranscodeWhenDestroyRequestedByOwningProfile() {
    var file = seedMediaFile();
    var profileId = UUID.randomUUID();
    var session = createSession(file.getId(), profileId, defaultOptions());

    service.destroySession(session.getSessionId(), profileId);

    assertThat(service.accessSession(session.getSessionId())).isEmpty();
    assertThat(transcodeJobService.terminalCleanupAttempts())
        .containsExactly(session.getSessionId());
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
            segmentStore,
            ffprobeService,
            new TranscodeDecisionService(),
            new QualityLadderService(),
            limitedProperties,
            limitedRegistry,
            new MutexFactory<>(),
            new FakePlaybackTranscodeJobService(),
            new FakeMediaSourceCatalog());

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
    transcodeJobService.suspend(session.getSessionId());
    var startsBefore = transcodeJobService.startCommands().size();

    service.resumeSessionIfNeeded(session.getSessionId(), "segment5.ts");

    assertThat(transcodeJobService.startCommands()).hasSize(startsBefore + 1);
    assertLastStartPosition(5, 30);
  }

  @Test
  @DisplayName("Should not restart FFmpeg when session is actively transcoding")
  void shouldNotRestartFfmpegWhenSessionIsActivelyTranscoding() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var startedBefore = transcodeJobService.startCommands().size();

    service.resumeSessionIfNeeded(session.getSessionId(), "segment0.ts");

    assertThat(transcodeJobService.startCommands()).hasSize(startedBefore);
  }

  @Test
  @DisplayName("Should not restart FFmpeg when segment already exists on disk")
  void shouldNotRestartFfmpegWhenSegmentAlreadyExistsOnDisk() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
    transcodeJobService.suspend(session.getSessionId());
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
    transcodeJobService.suspend(session.getSessionId());
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
    transcodeJobService.suspend(session.getSessionId());

    service.resumeSessionIfNeeded(session.getSessionId(), "segment5.ts");

    assertLastStartPosition(5, 30);
  }

  @Test
  @DisplayName("Should resume with correct start number when segment is fMP4 format")
  void shouldResumeWithCorrectStartNumberWhenSegmentIsFmp4Format() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
    transcodeJobService.suspend(session.getSessionId());

    service.resumeSessionIfNeeded(session.getSessionId(), "segment12.m4s");

    assertLastStartPosition(12, 72);
  }

  @Test
  @DisplayName("Should resume with correct start number when segment includes variant path")
  void shouldResumeWithCorrectStartNumberWhenSegmentIncludesVariantPath() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
    transcodeJobService.suspend(session.getSessionId());

    service.resumeSessionIfNeeded(session.getSessionId(), "720p/segment3.ts");

    assertLastStartPosition(3, 18);
  }

  @Test
  @DisplayName("Should resume at beginning when segment name has no index")
  void shouldResumeAtBeginningWhenSegmentNameHasNoIndex() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
    transcodeJobService.suspend(session.getSessionId());

    service.resumeSessionIfNeeded(session.getSessionId(), "init.mp4");

    assertLastStartPosition(0, 0);
  }

  @Test
  @DisplayName("Should resume at beginning when segment is first")
  void shouldResumeAtBeginningWhenSegmentIsFirst() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
    transcodeJobService.suspend(session.getSessionId());

    service.resumeSessionIfNeeded(session.getSessionId(), "segment0.ts");

    assertLastStartPosition(0, 0);
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
    }
    transcodeJobService.suspend(session.getSessionId());

    var requestsBefore = transcodeJobService.startCommands().size();
    service.resumeSessionIfNeeded(session.getSessionId(), "segment5.ts");
    assertThat(transcodeJobService.startCommands()).hasSize(requestsBefore + 1);
    var resume = transcodeJobService.startCommands().getLast();

    assertThat(resume.execution().startNumber()).isEqualTo(5);
    assertThat(resume.execution().seekPosition()).isEqualTo(30);
    assertThat(resume.renditions())
        .extracting(com.streamarr.transcode.engine.model.RenditionSpec::label)
        .containsExactlyInAnyOrderElementsOf(variantLabels);
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
    assertLastStartPosition(10, 60);
  }

  @Test
  @DisplayName("Should relocate the transcode when the requested segment is far ahead of progress")
  void shouldRelocateTheTranscodeWhenTheRequestedSegmentIsFarAheadOfProgress() {
    var file = seedMediaFile();
    var profileId = UUID.randomUUID();
    var session = createSession(file.getId(), profileId, defaultOptions());
    var legacyStopsBefore = transcodeExecutor.getStopped().size();

    service.resumeSessionIfNeeded(session.getSessionId(), "segment100.ts");

    // Nothing near segment100 has been produced; waiting would stall the player.
    assertLastStartPosition(100, 600);
    assertThat(transcodeExecutor.getStopped()).hasSize(legacyStopsBefore);
  }

  @Test
  @DisplayName("Should wait when the requested segment is near the encoder start")
  void shouldWaitWhenTheRequestedSegmentIsNearTheEncoderStart() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    var requestsBefore = transcodeJobService.startCommands().size();

    service.resumeSessionIfNeeded(session.getSessionId(), "segment2.ts");

    // The encoder started at segment0 and will reach segment2 shortly.
    assertThat(transcodeJobService.startCommands()).hasSize(requestsBefore);
  }

  @Test
  @DisplayName("Should wait when the encoder is within the forward gap of the request")
  void shouldWaitWhenTheEncoderIsWithinTheForwardGapOfTheRequest() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    segmentStore.addSegment(session.getSessionId(), "segment96.ts", new byte[] {1});
    var requestsBefore = transcodeJobService.startCommands().size();

    service.resumeSessionIfNeeded(session.getSessionId(), "segment100.ts");

    // segment96 exists, so the encoder is close behind the request.
    assertThat(transcodeJobService.startCommands()).hasSize(requestsBefore);
  }

  @Test
  @DisplayName("Should not relocate when the requested segment already exists")
  void shouldNotRelocateWhenTheRequestedSegmentAlreadyExists() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());
    segmentStore.addSegment(session.getSessionId(), "segment10.ts", new byte[] {1});
    var requestsBefore = transcodeJobService.startCommands().size();

    service.resumeSessionIfNeeded(session.getSessionId(), "segment10.ts");

    assertThat(transcodeJobService.startCommands()).hasSize(requestsBefore);
  }

  @Test
  @DisplayName("Should fail closed when active whole-job inspection is unavailable")
  void shouldFailClosedWhenActiveWholeJobInspectionIsUnavailable() {
    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    transcodeJobService.makeInspectionUnavailable(session.getSessionId());
    var requestsBefore = transcodeJobService.startCommands().size();

    service.resumeSessionIfNeeded(session.getSessionId(), "segment100.ts");

    assertThat(transcodeJobService.startCommands()).hasSize(requestsBefore);
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeJobState.class,
      names = {"COMPLETED", "FAILED", "STOPPED", "ABSENT"})
  @DisplayName("Should restart when an observed whole job cannot produce a missing segment")
  void shouldRestartWhenObservedWholeJobCannotProduceMissingSegment(TranscodeJobState state) {
    var session = createSession(seedMediaFile().getId(), UUID.randomUUID(), defaultOptions());
    transcodeJobService.observe(session.getSessionId(), state, 0);
    var requestsBefore = transcodeJobService.startCommands().size();

    service.resumeSessionIfNeeded(session.getSessionId(), "segment5.ts");

    assertThat(transcodeJobService.startCommands()).hasSize(requestsBefore + 1);
    assertLastStartPosition(5, 30);
  }

  @Test
  @DisplayName("Should resume at the absolute segment position when resuming")
  void shouldResumeAtTheAbsoluteSegmentPositionWhenResuming() {
    var file = seedMediaFile();
    var session = createSession(file.getId(), UUID.randomUUID(), defaultOptions());

    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
    transcodeJobService.suspend(session.getSessionId());

    service.resumeSessionIfNeeded(session.getSessionId(), "segment5.ts");

    // The timeline is absolute: segment5 always covers [30s, 36s).
    assertLastStartPosition(5, 30);
  }

  private void assertLastStartPosition(int startNumber, int seekPosition) {
    var execution = transcodeJobService.startCommands().getLast().execution();
    assertThat(execution.startNumber()).isEqualTo(startNumber);
    assertThat(execution.seekPosition()).isEqualTo(seekPosition);
  }

  private List<StreamSession> fillTranscodeCapacity() {
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
    var sessions = new java.util.ArrayList<StreamSession>();
    for (int i = 0; i < properties.maxConcurrentTranscodes(); i++) {
      var file = seedMediaFile();
      sessions.add(createSession(file.getId(), UUID.randomUUID(), fullHdOptions()));
    }
    return List.copyOf(sessions);
  }

  private static StreamingOptions fullHdOptions() {
    return StreamingOptions.builder()
        .quality(VideoQuality.FULL_HD_1080P)
        .supportedCodecs(List.of("h264"))
        .build();
  }

  private static final class RejectingAttachRegistry extends FakeStreamSessionRepository {

    @Override
    public boolean attach(RuntimeSessionReservation reservation, StreamSession session) {
      return false;
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

  private static final class BlockingTranscodeWorker implements TranscodeWorkerPort {

    private final WorkerTarget target = new WorkerTarget(UUID.randomUUID(), UUID.randomUUID());
    private final java.util.concurrent.CountDownLatch startEntered =
        new java.util.concurrent.CountDownLatch(1);
    private final java.util.concurrent.CountDownLatch allowStart =
        new java.util.concurrent.CountDownLatch(1);

    private WorkerTarget target() {
      return target;
    }

    private boolean awaitStart() throws InterruptedException {
      return startEntered.await(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void releaseStart() {
      allowStart.countDown();
    }

    @Override
    public StartJobResult start(StartJobCommand command) {
      startEntered.countDown();
      await(allowStart);
      var specification = command.specification();
      return new StartJobResult.Accepted(
          TranscodeJobObservation.builder()
              .jobRef(specification.jobRef())
              .state(TranscodeJobState.RUNNING)
              .renditions(
                  specification.renditions().stream()
                      .map(
                          rendition ->
                              new RenditionObservation(rendition.label(), RenditionState.RUNNING))
                      .toList())
              .build());
    }

    @Override
    public StopJobResult stop(StopJobCommand command) {
      return new StopJobResult.Stopped(command.jobRef());
    }

    @Override
    public InspectJobResult inspect(InspectJobQuery query) {
      return new InspectJobResult.Observed(
          TranscodeJobObservation.builder()
              .jobRef(query.jobRef())
              .state(TranscodeJobState.ABSENT)
              .renditions(List.of())
              .build());
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
      try {
        latch.await();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(exception);
      }
    }
  }
}
