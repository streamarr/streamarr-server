package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.WorkerContainerFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.support.security.WithProfileContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/** The checks that need a pinned worker image that answers probe version 4. */
@Tag("IntegrationTest")
@EnableDgsTest
@WithProfileContext
@DisplayName("Probe Version 4 Worker Integration Tests")
class ProbeVersion4WorkerIT extends AbstractIntegrationTest {

  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
  private static final int SEGMENT_SECONDS = 6;
  private static final List<String> CHAPTER_TITLES = List.of("Opening", "Middle", "Closing");
  // 40 subtitle streams with a 4,000-byte language tag each make a result of more than 160,000
  // bytes, past the 131,072-byte session message limit.
  private static final int OVERSIZED_STREAM_COUNT = 40;
  private static final int OVERSIZED_LANGUAGE_BYTES = 4_000;

  @TempDir Path tempDir;

  @Autowired private DgsQueryExecutor dgsQueryExecutor;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private MediaFileContainerInfoRepository containerInfoRepository;

  @ParameterizedTest
  @ValueSource(strings = {"chapters.mkv", "chapters.mp4"})
  @DisplayName(
      "Should return each chapter through GraphQL and seek to its start when the worker probes a chaptered file")
  void shouldReturnEachChapterThroughGraphQlAndSeekToItsStartWhenTheWorkerProbesAChapteredFile(
      String filename) throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));

    try (var server = server(segmentStore);
        var worker = workerBuilder(server, mediaRoot).build()) {
      server.start();
      worker.start();
      var source = worker.generateMedia(mediaRoot.resolve(filename), chapteredMedia());
      var outcome = probe(server, mediaRoot, source);
      var file = createMediaFile(filename);
      publish(file, outcome);

      var chapters = queryChapters(file);

      assertThat(chapters)
          .extracting(chapter -> chapter.get("title"))
          .containsExactlyElementsOf(CHAPTER_TITLES);
      var playback =
          new Playback(
              new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot), segmentStore);
      for (var chapter : chapters) {
        var startSeconds = (Double) chapter.get("startSeconds");
        var seek = encodeRequestBuilder(source, outcome.mediaProbe()).mediaSegmentCount(3);

        assertThat(firstTimestampAfterSeeking(playback, seek, startSeconds))
            .as("the first frame after a seek to %s", chapter.get("title"))
            .isCloseTo(startSeconds, within(1.0 / 24));
      }
    }
  }

  @Test
  @DisplayName(
      "Should fail only the oversized probe and keep playing when a probe result exceeds the session message limit")
  void shouldFailOnlyTheOversizedProbeAndKeepPlayingWhenAProbeResultExceedsTheSessionMessageLimit()
      throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));

    try (var server = server(segmentStore);
        var worker = workerBuilder(server, mediaRoot).availableSlots(2).build()) {
      server.start();
      worker.start();
      var playing = worker.generateMedia(mediaRoot.resolve("playing.mkv"), longMedia());
      var oversized = worker.generateMedia(mediaRoot.resolve("oversized.mkv"), oversizedMedia());
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
      var sessionId = UUID.randomUUID();
      var playingOutcome = probe(server, mediaRoot, playing);
      executor.start(
          encodeRequestBuilder(playing, playingOutcome.mediaProbe())
              .sessionId(sessionId)
              .mediaSegmentCount(300)
              .build());
      awaitSegment(segmentStore, sessionId, 0);

      var oversizedOutcome =
          new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, mediaRoot)
              .probe(probeRequest(oversized));
      var segmentsBefore = publishedSegmentCount(segmentStore, sessionId);

      assertThat(oversizedOutcome).isEqualTo(new ProbeOutcome.Failure(ProbeError.RESULT_TOO_LARGE));
      awaitSegment(segmentStore, sessionId, segmentsBefore);
      assertThat(executor.isRunning(sessionId, StreamSession.defaultVariant()))
          .as("the job attempt keeps running")
          .isTrue();
      assertThat(server.hasConnectedWorker(SOURCE_NAMESPACE_ID))
          .as("the worker keeps its session")
          .isTrue();
      executor.stop(sessionId);
    }
  }

  private record Playback(RemoteTranscodeExecutor executor, LocalSegmentStore segmentStore) {}

  // Each seek encodes from the media segment that holds the chapter's start. The chapters start on
  // the 6 s grid, so that segment opens at the chapter's start.
  private double firstTimestampAfterSeeking(
      Playback playback, TranscodeRequest.TranscodeRequestBuilder seek, double startSeconds)
      throws Exception {
    var sessionId = UUID.randomUUID();
    var index = (int) Math.round(startSeconds / SEGMENT_SECONDS);
    playback
        .executor()
        .start(
            seek.sessionId(sessionId)
                .seekPosition(index * SEGMENT_SECONDS)
                .startSequenceNumber(index)
                .build());
    try {
      awaitSegment(playback.segmentStore(), sessionId, index);
      return Fmp4Fixture.firstVideoPresentationTime(
          Fmp4Fixture.withInitializationSegment(
              playback.segmentStore(), sessionId, SegmentNames.mediaSegmentName(index)));
    } finally {
      playback.executor().stop(sessionId);
    }
  }

  private static TranscodeRequest.TranscodeRequestBuilder encodeRequestBuilder(
      Path source, MediaProbe probe) {
    return TranscodeRequest.builder()
        .sourcePath(source)
        .targetSegmentDuration(SEGMENT_SECONDS)
        .framerate(probe.framerate())
        .width(probe.displayedSize().width())
        .height(probe.displayedSize().height())
        .bitrate(1_000_000)
        .encoderPreset(EncoderPreset.FASTER)
        .transcodeDecision(
            TranscodeDecision.builder()
                .transcodeMode(TranscodeMode.VIDEO_TRANSCODE)
                .videoCodecFamily("h264")
                .audioDecision(AudioDecision.none())
                .subtitleDecision(SubtitleDecision.exclude())
                .videoSource(probe.videoPlaybackFacts())
                .build());
  }

  private ProbeOutcome.Success probe(WorkerSessionServer server, Path mediaRoot, Path source) {
    var outcome =
        new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, mediaRoot)
            .probe(probeRequest(source));
    assertThat(outcome).isInstanceOf(ProbeOutcome.Success.class);
    return (ProbeOutcome.Success) outcome;
  }

  private static ProbeExecutionRequest probeRequest(Path source) {
    return ProbeExecutionRequest.builder()
        .sourcePath(source)
        .attemptId(UUID.randomUUID())
        .probeVersion(ProbeVersion.CURRENT)
        .build();
  }

  private static void awaitSegment(LocalSegmentStore segmentStore, UUID sessionId, int index) {
    await()
        .atMost(Duration.ofSeconds(60))
        .until(
            () ->
                segmentStore.segmentExists(sessionId, SegmentNames.INITIALIZATION_SEGMENT_NAME)
                    && segmentStore.segmentExists(sessionId, SegmentNames.mediaSegmentName(index)));
  }

  private static int publishedSegmentCount(LocalSegmentStore segmentStore, UUID sessionId) {
    return (int)
        IntStream.iterate(0, index -> index + 1)
            .takeWhile(
                index ->
                    segmentStore.segmentExists(sessionId, SegmentNames.mediaSegmentName(index)))
            .count();
  }

  // 18 s at 24 fps with a keyframe every second, and three chapters on the 6 s grid.
  private static List<String> chapteredMedia() {
    var metadata = new StringBuilder(";FFMETADATA1\n");
    for (var index = 0; index < CHAPTER_TITLES.size(); index++) {
      metadata
          .append("[CHAPTER]\nTIMEBASE=1/1000\n")
          .append("START=")
          .append(index * SEGMENT_SECONDS * 1000)
          .append("\nEND=")
          .append((index + 1) * SEGMENT_SECONDS * 1000)
          .append("\ntitle=")
          .append(CHAPTER_TITLES.get(index))
          .append('\n');
    }

    return List.of(
        "-f",
        "lavfi",
        "-i",
        "testsrc=size=320x180:rate=24:duration=18",
        "-f",
        "ffmetadata",
        "-i",
        "data:text/plain;base64,"
            + Base64.getEncoder()
                .encodeToString(metadata.toString().getBytes(StandardCharsets.UTF_8)),
        "-map",
        "0",
        "-map_chapters",
        "1",
        "-c:v",
        "libx264",
        "-pix_fmt",
        "yuv420p",
        "-g",
        "24");
  }

  // Thirty minutes, so the job attempt still runs after the oversized probe ends.
  private static List<String> longMedia() {
    return List.of(
        "-f",
        "lavfi",
        "-i",
        "testsrc=size=320x180:rate=24:duration=1800",
        "-c:v",
        "libx264",
        "-preset",
        "ultrafast",
        "-pix_fmt",
        "yuv420p",
        "-g",
        "24");
  }

  private static List<String> oversizedMedia() {
    var subtitle =
        Base64.getEncoder()
            .encodeToString(
                "1\n00:00:00,000 --> 00:00:00,500\nx\n".getBytes(StandardCharsets.UTF_8));
    var arguments =
        new ArrayList<>(
            List.of(
                "-f",
                "lavfi",
                "-i",
                "testsrc=size=64x36:rate=4:duration=1",
                "-f",
                "srt",
                "-i",
                "data:text/plain;base64," + subtitle,
                "-map",
                "0:v"));
    var language = "x".repeat(OVERSIZED_LANGUAGE_BYTES);
    for (var index = 0; index < OVERSIZED_STREAM_COUNT; index++) {
      arguments.addAll(List.of("-map", "1:s", "-metadata:s:s:" + index, "language=" + language));
    }

    arguments.addAll(List.of("-c:v", "libx264", "-c:s", "srt"));
    return arguments;
  }

  private MediaFile createMediaFile(String filename) {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie =
        movieRepository.saveAndFlush(Movie.builder().title("Chapters").library(library).build());
    return mediaFileRepository.saveAndFlush(
        MediaFile.builder()
            .mediaId(movie.getId())
            .libraryId(library.getId())
            .status(MediaFileStatus.MATCHED)
            .filename(filename)
            .filepathUri("file:///media/" + UUID.randomUUID() + "/" + filename)
            .build());
  }

  private void publish(MediaFile file, ProbeOutcome.Success outcome) {
    assertThat(
            containerInfoRepository.publish(
                ProbePublication.builder()
                    .mediaFileId(file.getId())
                    .snapshot(new SourceFileSnapshot(4096, Instant.parse("2026-10-16T10:00:00Z")))
                    .probeVersion(ProbeVersion.CURRENT)
                    .outcome(outcome)
                    .build()))
        .isTrue();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> queryChapters(MediaFile file) {
    var result =
        dgsQueryExecutor.execute(
            """
            {
              movie(id: "%s") {
                files {
                  chapterStatus
                  chapters { startSeconds endSeconds title }
                }
              }
            }
            """
                .formatted(file.getMediaId()));

    assertThat(result.getErrors()).isEmpty();
    Map<String, Object> data = result.getData();
    var files = (List<Map<String, Object>>) ((Map<String, Object>) data.get("movie")).get("files");
    assertThat(files).singleElement().extracting(f -> f.get("chapterStatus")).isEqualTo("KNOWN");
    return (List<Map<String, Object>>) files.getFirst().get("chapters");
  }

  private static WorkerSessionServer server(LocalSegmentStore segmentStore) {
    return new WorkerSessionServer(
        serverConfigurationBuilder().address("127.0.0.1").build(),
        segmentStore,
        new SimpleMeterRegistry());
  }

  private static WorkerContainerFixture.WorkerContainerFixtureBuilder workerBuilder(
      WorkerSessionServer server, Path mediaRoot) {
    return WorkerContainerFixture.builder()
        .workerSessions(server)
        .sourceNamespaceId(SOURCE_NAMESPACE_ID)
        .sourceRoot(mediaRoot);
  }
}
