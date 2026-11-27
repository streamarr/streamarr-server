package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.WorkerProbeResultFixture.pqDolbyVisionOf;
import static com.streamarr.server.fixtures.WorkerProbeResultFixture.reportedEnhancementLayer;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ChapterKnowledge;
import com.streamarr.server.domain.streaming.ContentLightLevel;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionEnhancementLayer;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.LoopbackProbeWorker;
import com.streamarr.server.fixtures.WorkerProbeResultFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.support.BoundedTask;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Probe Version 8 Facts Integration Tests")
class ProbeVersion8FactsIT extends AbstractIntegrationTest {

  private static final int VERSION_8 =
      ProbeVersion.FIRST_TO_REPORT_MAXIMUM_FRAME_RATE_AND_RPU_FACTS;

  @TempDir Path directory;

  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private MediaFileContainerInfoRepository containerInfoRepository;

  @Test
  @DisplayName(
      "Should read the maximum frame rate, the enhancement layer and the level 6 light levels back"
          + " when a worker reports them in probe version 8")
  void shouldReadTheVersion8FactsBackWhenAWorkerReportsThem() throws Exception {
    var media = WorkerProbeResultFixture.version4MediaBuilder();
    var video = media.getStreamsBuilder(0).setCodec("hevc");
    video.getMaximumFrameRateBuilder().setNumerator(60_000).setDenominator(1_001);
    var reported = pqDolbyVisionOf(video).setEnhancementLayer(reportedEnhancementLayer("MEL"));
    reported
        .getRpuContentLightLevelBuilder()
        .setMaxContentLightLevel(1_000)
        .setMaxFrameAverageLightLevel(0);
    media.addStreams(ProbeStreamInfo.newBuilder().setIndex(1).setCodecType("audio"));
    var file = createMediaFile();

    publish(file, probeWithWorker(media));

    var streams = storedStreams(file);
    var dolbyVision =
        (DolbyVision.Present) streams.getFirst().dynamicRange().orElseThrow().dolbyVision();
    assertThat(streams.getFirst().maximumFrameRate()).contains(new Fraction(60_000, 1_001));
    assertThat(dolbyVision.enhancementLayer()).isEqualTo(DolbyVisionEnhancementLayer.MEL);
    assertThat(dolbyVision.rpuContentLightLevel()).contains(new ContentLightLevel(1_000, 0));
    assertThat(streams.get(1).maximumFrameRate()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should read an unknown maximum frame rate, enhancement layer and level 6 back as unknown"
          + " when a probe version 8 worker could not measure them")
  void shouldReadUnknownVersion8FactsBackAsUnknownWhenAWorkerCouldNotMeasureThem()
      throws Exception {
    var media = WorkerProbeResultFixture.version4MediaBuilder();
    pqDolbyVisionOf(media.getStreamsBuilder(0).setCodec("hevc"));
    var file = createMediaFile();

    publish(file, probeWithWorker(media));

    var video = storedStreams(file).getFirst();
    var dolbyVision = (DolbyVision.Present) video.dynamicRange().orElseThrow().dolbyVision();
    assertThat(video.maximumFrameRate()).isEmpty();
    assertThat(dolbyVision.enhancementLayer()).isEqualTo(DolbyVisionEnhancementLayer.UNSPECIFIED);
    assertThat(dolbyVision.rpuContentLightLevel()).isEmpty();
  }

  private List<StreamInfo> storedStreams(MediaFile file) {
    var stored =
        containerInfoRepository
            .findByMediaFileId(file.getId())
            .orElseThrow()
            .getOutcome(KeyframeKnowledge.NotReported::new, ChapterKnowledge.NotReported::new);
    assertThat(stored).isInstanceOf(ProbeOutcome.Success.class);
    return ((ProbeOutcome.Success) stored).streams();
  }

  private ProbeOutcome.Success probeWithWorker(ProbeMediaInfo.Builder media) throws Exception {
    try (var server =
        WorkerSessionServer.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(serverConfigurationBuilder().build())
            .segmentStore(new FakeSegmentStore())
            .meterRegistry(new SimpleMeterRegistry())
            .build()) {
      server.start();
      try (var worker =
          LoopbackProbeWorker.builder()
              .port(server.port())
              .probeVersion(VERSION_8)
              .availableSlots(1)
              .backgroundProbeCapacity(1)
              .build()) {
        var service = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, directory);
        var request =
            ProbeExecutionRequest.builder()
                .sourcePath(directory.resolve("movie.mkv"))
                .attemptId(UUID.randomUUID())
                .probeVersion(VERSION_8)
                .priority(ProbePriority.BACKGROUND)
                .build();
        try (var result = BoundedTask.start(() -> service.probe(request))) {
          var dispatched = worker.nextResponse().getStartProbe().getRequest();
          worker.report(
              ProbeAttemptResult.newBuilder()
                  .setProbeAttemptId(dispatched.getProbeAttemptId())
                  .setProbeVersion(dispatched.getProbeVersion())
                  .setMedia(media)
                  .build());
          var outcome = result.await(Duration.ofSeconds(5));
          assertThat(outcome).isInstanceOf(ProbeOutcome.Success.class);
          return (ProbeOutcome.Success) outcome;
        }
      }
    }
  }

  private MediaFile createMediaFile() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie =
        movieRepository.saveAndFlush(
            Movie.builder().title("Probe version 8 facts").library(library).build());
    var source = directory.resolve("movie.mkv");
    return mediaFileRepository.saveAndFlush(
        MediaFile.builder()
            .mediaId(movie.getId())
            .libraryId(library.getId())
            .status(MediaFileStatus.MATCHED)
            .filename(source.getFileName().toString())
            .filepathUri(FilepathCodec.encode(source))
            .build());
  }

  private void publish(MediaFile file, ProbeOutcome.Success outcome) {
    assertThat(
            containerInfoRepository.publish(
                ProbePublication.builder()
                    .mediaFileId(file.getId())
                    .snapshot(new SourceFileSnapshot(4096, Instant.parse("2026-11-02T10:00:00Z")))
                    .probeVersion(VERSION_8)
                    .outcome(outcome)
                    .build()))
        .isTrue();
  }
}
