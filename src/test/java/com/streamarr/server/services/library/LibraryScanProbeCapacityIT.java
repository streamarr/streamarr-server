package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommand;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.LoopbackProbeWorker;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.streaming.HlsStreamingService;
import com.streamarr.server.services.streaming.ProbeCapacitySource;
import com.streamarr.server.services.streaming.QualityLadderService;
import com.streamarr.server.services.streaming.TranscodeDecisionService;
import com.streamarr.server.services.streaming.remote.ProbeRefusal;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.server.support.LibraryOperationDriver;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Scans a library while a worker advertises more probe capacity than playback slots. The scan's
 * probes run up to the probe capacity at once, and a stream session still starts a job attempt on
 * the worker's only playback slot.
 */
@Tag("IntegrationTest")
@DisplayName("Library scan against the workers' probe capacity")
class LibraryScanProbeCapacityIT extends AbstractScanningIntegrationTest {

  private static final int FILES = 8;
  private static final int PROBE_CAPACITY = 4;
  private static final int PROBE_SCHEDULER_THREADS = 16;
  private static final Duration WORKER_WAIT = Duration.ofSeconds(20);
  private static final Path STREAMING_SOURCE_ROOT = Path.of("/media");

  @Autowired private WorkerSessionServer workerSessionServer;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private LibraryOperationService libraryOperationService;
  @Autowired private LibraryOperationDriver libraryOperationDriver;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private PersistedProbeReader probeReader;
  @Autowired private ProbeExecution probeExecution;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ApplicationContext applicationContext;

  @TempDir Path tempDir;

  private Library library;
  private ProbeSchedulerStarter.StartedProbeScheduler probeScheduler;

  @AfterEach
  void tearDown() {
    if (probeScheduler != null) {
      probeScheduler.scheduler().stop();
    }

    jdbcTemplate.update("DELETE FROM scheduled_tasks");
    if (library != null) {
      mediaFileRepository.deleteAll(mediaFileRepository.findByLibraryId(library.getId()));
      libraryRepository.delete(library);
    }
  }

  @Test
  @DisplayName(
      "Should run four probes at once and start a stream session meanwhile when a worker"
          + " advertises a probe capacity of four and one playback slot")
  void shouldRunFourProbesAtOnceAndStartAStreamSessionMeanwhileWhenAWorkerAdvertisesFourAndOne()
      throws Exception {
    library = movieLibraryWithFiles();
    var deferredBefore = deferredBecauseWorkersAreBusy();

    try (var worker =
        LoopbackProbeWorker.builder()
            .port(workerSessionServer.port())
            .probeVersion(ProbeVersion.CURRENT)
            .availableSlots(1)
            .backgroundProbeCapacity(PROBE_CAPACITY)
            .build()) {
      startProbeScheduler();
      var end = libraryOperationDriver.nextEnd(library.getId());
      assertThat(libraryOperationService.startLibraryScanAsSystemActor(library.getId()))
          .isInstanceOf(Outcome.Accepted.class);

      var firstProbes = startedProbes(worker, PROBE_CAPACITY);
      assertThat(workerSessionServer.probeCapacity(SOURCE_NAMESPACE_ID, ProbeVersion.CURRENT))
          .satisfies(
              capacity -> {
                assertThat(capacity.backgroundProbeCapacity()).isEqualTo(PROBE_CAPACITY);
                assertThat(capacity.runningProbes()).isEqualTo(PROBE_CAPACITY);
              });

      assertThat(startStreamSession()).isInstanceOf(Outcome.Accepted.class);
      assertThat(worker.nextResponse(WORKER_WAIT).getCommandCase())
          .as("The stream session's job attempt starts while the scan's probes run")
          .isEqualTo(EstablishWorkerSessionResponse.CommandCase.START_VARIANT);

      firstProbes.forEach(probe -> worker.reply(invalidMedia(probe)));
      var remainingProbes = startedProbes(worker, FILES - PROBE_CAPACITY);
      remainingProbes.forEach(probe -> worker.reply(invalidMedia(probe)));
      libraryOperationDriver.awaitEnd(end);
    }

    assertThat(mediaFileRepository.findByLibraryId(library.getId()))
        .hasSize(FILES)
        .allSatisfy(file -> assertThat(probeReader.find(file.getId())).isPresent());
    assertThat(deferredBecauseWorkersAreBusy())
        .as("The scheduler picked no probe beyond the worker's probe capacity")
        .isEqualTo(deferredBefore);
  }

  @Override
  protected void startProbeScheduler() {
    var producer =
        new RemoteFfprobeService(workerSessionServer, SOURCE_NAMESPACE_ID, libraryRoot());
    probeScheduler =
        ProbeSchedulerStarter.fromContext(applicationContext).toBuilder()
            .threads(PROBE_SCHEDULER_THREADS)
            .probeCapacity(applicationContext.getBean(ProbeCapacitySource.class))
            .build()
            .start(
                probeExecution.toBuilder().producer(producer).build(),
                new AbstractSchedulerListener() {},
                Clock.systemUTC());
  }

  private static List<ProbeRequest> startedProbes(LoopbackProbeWorker worker, int count)
      throws InterruptedException {
    var started = new ArrayList<ProbeRequest>();
    for (var index = 0; index < count; index++) {
      started.add(worker.nextResponse(WORKER_WAIT).getStartProbe().getRequest());
    }

    return started;
  }

  private Outcome<?, ?> startStreamSession() {
    var properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(8)
            .targetSegmentDuration(Duration.ofSeconds(6))
            .build();
    var transcodeExecutor =
        new RemoteTranscodeExecutor(
            workerSessionServer, SOURCE_NAMESPACE_ID, STREAMING_SOURCE_ROOT);
    var segmentStore = new FakeSegmentStore();
    var runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(runtimeRegistry)
            .heldRequests(workerSessionServer.heldSegmentRequests())
            .build();
    var mediaFiles = new FakeMediaFileRepository();
    var probed =
        mediaFiles.save(
            MediaFile.builder()
                .filepathUri("file:///media/movies/probed.mkv")
                .filename("probed.mkv")
                .status(MediaFileStatus.MATCHED)
                .size(1_000_000L)
                .build());
    var probeResults = new FakeMediaFileContainerInfoRepository();
    probeResults.setDefaultProbe(defaultProbeBuilder().build());
    var streamingService =
        HlsStreamingService.builder()
            .meterRegistry(new SimpleMeterRegistry())
            .mediaFileRepository(mediaFiles)
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .playbackProbeService(playbackProbeServiceBuilder(probeResults).build())
            .transcodeDecisionService(
                new TranscodeDecisionService(properties, new SimpleMeterRegistry()))
            .qualityLadderService(new QualityLadderService())
            .properties(properties)
            .authorityGate(new FakePlaybackAuthorityGate())
            .runtimeRegistry(runtimeRegistry)
            .producerLifecycle(rig.lifecycle())
            .deliveryCoordinator(rig.coordinator())
            .build();
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.AUTO)
            .supportedCodecs(List.of("h264"))
            .build();
    return streamingService.createSession(
        createStreamSessionCommand(probed.getId(), UUID.randomUUID(), options));
  }

  private double deferredBecauseWorkersAreBusy() {
    var counter =
        meterRegistry
            .find("streamarr.probes.deferred")
            .tag("reason", ProbeRefusal.WORKERS_BUSY.name())
            .counter();
    if (counter == null) {
      return 0;
    }

    return counter.count();
  }

  private Path libraryRoot() {
    return tempDir.resolve("library");
  }

  private Library movieLibraryWithFiles() throws IOException {
    var root = Files.createDirectories(libraryRoot());
    for (var index = 0; index < FILES; index++) {
      var title = "Probe Capacity Feature " + index;
      var folder = Files.createDirectories(root.resolve(title + " (2001)"));
      Files.write(folder.resolve(title + " (2001).mkv"), new byte[] {1, 2, 3});
    }

    return libraryRepository.saveAndFlush(
        Library.builder()
            .name("Probe capacity")
            .backend(LibraryBackend.LOCAL)
            .status(LibraryStatus.HEALTHY)
            .filepathUri(FilepathCodec.encode(root))
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .type(MediaType.MOVIE)
            .build());
  }

  private static ProbeAttemptResult invalidMedia(ProbeRequest request) {
    return ProbeAttemptResult.newBuilder()
        .setProbeAttemptId(request.getProbeAttemptId())
        .setProbeVersion(request.getProbeVersion())
        .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
        .build();
  }
}
