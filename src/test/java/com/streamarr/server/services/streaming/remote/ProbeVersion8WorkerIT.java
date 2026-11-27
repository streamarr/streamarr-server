package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
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
import com.streamarr.server.domain.streaming.AudioStreamProperties;
import com.streamarr.server.domain.streaming.ChapterKnowledge;
import com.streamarr.server.domain.streaming.ColorPrimaries;
import com.streamarr.server.domain.streaming.ColorRange;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.MatrixCoefficients;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TransferCharacteristics;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.CapturingKeyframePacketScanRequests;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.WorkerContainerFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.library.ProbeExecution;
import com.streamarr.server.services.library.ProbeExecutionResult;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.support.security.WithProfileContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/** The checks that need a pinned worker image that answers probe version 8. */
@Tag("IntegrationTest")
@EnableDgsTest
@WithProfileContext
@DisplayName("Probe Version 8 Worker Integration Tests")
class ProbeVersion8WorkerIT extends AbstractIntegrationTest {

  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
  private static final int SEGMENT_SECONDS = 6;
  private static final List<String> CHAPTER_TITLES = List.of("Opening", "Middle", "Closing");
  // 40 subtitle streams with a 4,000-byte language tag each make a probe report of more than
  // 160,000 bytes, past the 131,072 bytes of one message.
  private static final int OVERSIZED_STREAM_COUNT = 40;
  private static final int OVERSIZED_LANGUAGE_BYTES = 4_000;
  // The default result length limit of 128 KiB keeps a result within one message, so the worker
  // fails a larger result as too large. This limit lets the oversized result through.
  private static final long RESULT_LENGTH_LIMIT_BYTES = 256L * 1024;
  private static final int WORKER_SLOTS = 2;

  @TempDir static Path tempDir;

  private static Path mediaRoot;
  private static LocalSegmentStore segmentStore;
  private static WorkerSessionServer server;
  private static WorkerContainerFixture worker;

  @Autowired private DgsQueryExecutor dgsQueryExecutor;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private MediaFileContainerInfoRepository containerInfoRepository;
  @Autowired private ProbeExecution probeExecution;
  @Autowired private PersistedProbeReader reader;

  @BeforeAll
  static void startServerAndWorker() throws IOException {
    mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));
    server =
        WorkerSessionServer.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(
                serverConfigurationBuilder()
                    .address("127.0.0.1")
                    .probeReportLimits(
                        ProbeReportLimits.builder()
                            .resultLengthLimitBytes(RESULT_LENGTH_LIMIT_BYTES)
                            .build())
                    .build())
            .segmentStore(segmentStore)
            .meterRegistry(new SimpleMeterRegistry())
            .build();
    server.start();
    worker =
        WorkerContainerFixture.builder()
            .workerSessions(server)
            .sourceNamespaceId(SOURCE_NAMESPACE_ID)
            .sourceRoot(mediaRoot)
            .availableSlots(WORKER_SLOTS)
            .build();
    worker.start();
  }

  @AfterAll
  static void stopWorkerAndServer() {
    if (worker != null) {
      worker.close();
    }

    if (server != null) {
      server.close();
    }
  }

  // A stop frees its slot at once, so the slot count is final when a test ends. A probe that a test
  // stopped waiting for keeps its probe unit until the worker confirms the cancellation, or until
  // the server drops the worker after its 5 s cancellation timeout.
  @BeforeEach
  void requireTheSharedWorkerConnectedAndIdle() {
    await()
        .alias("the probes that earlier tests stopped waiting for have ended on the shared worker")
        .pollDelay(Duration.ZERO)
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                server.probeCapacity(SOURCE_NAMESPACE_ID, ProbeVersion.CURRENT).runningProbes()
                    == 0);
    assertThat(server.hasConnectedWorker(SOURCE_NAMESPACE_ID))
        .as("the shared worker kept its session through the earlier tests")
        .isTrue();
    assertThat(server.availableSlots(SOURCE_NAMESPACE_ID))
        .as("the shared worker's free slots after the earlier tests")
        .isEqualTo(WORKER_SLOTS);
  }

  @ParameterizedTest
  @ValueSource(strings = {"chapters.mkv", "chapters.mp4"})
  @DisplayName(
      "Should return each chapter through GraphQL and seek to its start when the worker probes a chaptered file")
  void shouldReturnEachChapterThroughGraphQlAndSeekToItsStartWhenTheWorkerProbesAChapteredFile(
      String filename) throws Exception {
    var source = worker.generateMedia(mediaDirectory().resolve(filename), chapteredMedia());
    var outcome = probe(source);
    var file = createMediaFile(source);
    publish(file, outcome);

    var chapters = queryChapters(file);

    assertThat(chapters)
        .extracting(chapter -> chapter.get("title"))
        .containsExactlyElementsOf(CHAPTER_TITLES);
    var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
    for (var chapter : chapters) {
      var startSeconds = (Double) chapter.get("startSeconds");
      var seek = encodeRequestBuilder(source, outcome.mediaProbe()).mediaSegmentCount(3);

      assertThat(firstTimestampAfterSeeking(executor, seek, startSeconds))
          .as("the first frame after a seek to %s", chapter.get("title"))
          .isCloseTo(startSeconds, within(1.0 / 24));
    }
  }

  @Test
  @DisplayName(
      "Should return the full result, keep the job attempt running and keep the worker's session"
          + " when a probe result exceeds one message")
  void
      shouldReturnTheFullResultKeepTheJobAttemptRunningAndKeepTheWorkersSessionWhenAProbeResultExceedsOneMessage()
          throws Exception {
    var directory = mediaDirectory();
    var clip = worker.generateMedia(directory.resolve("clip.mp4"), shortMedia());
    var playing = worker.generateMedia(directory.resolve("playing.mkv"), longMedia(clip));
    var oversized = worker.generateMedia(directory.resolve("oversized.mkv"), oversizedMedia());
    var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
    var sessionId = UUID.randomUUID();
    var playingOutcome = probe(playing);
    executor.start(
        encodeRequestBuilder(playing, playingOutcome.mediaProbe())
            .sessionId(sessionId)
            .mediaSegmentCount(300)
            .build());
    try {
      awaitSegment(sessionId, 0);

      var oversizedOutcome = probe(oversized);
      var segmentsBefore = publishedSegmentCount(sessionId);

      assertThat(oversizedOutcome.mediaProbe().streams())
          .filteredOn(stream -> "subtitle".equals(stream.codecType()))
          .extracting(StreamInfo::language)
          .as("the result holds every subtitle stream's language tag in full")
          .hasSize(OVERSIZED_STREAM_COUNT)
          .containsOnly(Optional.of("x".repeat(OVERSIZED_LANGUAGE_BYTES)));
      awaitSegment(sessionId, segmentsBefore);
      assertThat(executor.isRunning(sessionId, StreamSession.defaultVariant()))
          .as("the job attempt keeps running")
          .isTrue();
      assertThat(server.hasConnectedWorker(SOURCE_NAMESPACE_ID))
          .as("the worker keeps its session")
          .isTrue();
    } finally {
      executor.stop(sessionId);
    }
  }

  @Test
  @DisplayName(
      "Should store an SDR stream's codec profile, color signaling and absent HDR features when the worker reads its first frames")
  void
      shouldStoreAnSdrStreamsCodecProfileColorSignalingAndAbsentHdrFeaturesWhenTheWorkerReadsItsFirstFrames()
          throws Exception {
    var source = worker.generateMedia(mediaDirectory().resolve("bt709.mkv"), bt709Media());
    var file = createMediaFile(source);
    publish(file, probe(source));

    var stored =
        containerInfoRepository
            .findByMediaFileId(file.getId())
            .orElseThrow()
            .getOutcome(KeyframeKnowledge.NotReported::new, ChapterKnowledge.NotReported::new);

    assertThat(stored)
        .isInstanceOfSatisfying(
            ProbeOutcome.Success.class,
            success -> {
              var video = success.streams().getFirst();
              assertThat(video.videoProperties())
                  .contains(
                      VideoStreamProperties.builder()
                          .codecProfile(Optional.of(VideoCodecProfile.H264_HIGH))
                          .codecLevel(OptionalInt.of(40))
                          .colorPrimaries(Optional.of(ColorPrimaries.BT709))
                          .transferCharacteristics(Optional.of(TransferCharacteristics.BT709))
                          .matrixCoefficients(Optional.of(MatrixCoefficients.BT709))
                          .colorRange(Optional.of(ColorRange.LIMITED))
                          .firstFramesRead(true)
                          .firstPacketsCarryPresentationTimes(Optional.of(true))
                          // libx264 writes a whole avcC with 4-byte NAL unit lengths, and a
                          // Matroska track has no sample entry, so a copy keeps every packet.
                          .copyKeepsPacketBytes(Optional.of(true))
                          .build());
              // For H.264 the worker walks the units of the frames that it read, so it reports
              // HDR10+ and an RPU as absent rather than unknown.
              assertThat(video.dynamicRange())
                  .contains(
                      new VideoDynamicRange(
                          CompatibleBase.SDR, Presence.ABSENT, new DolbyVision.Absent()));
            });
  }

  @Test
  @DisplayName(
      "Should store each stream's bitrate from its statistics tags when the probe job probes a file that mkvmerge wrote")
  void
      shouldStoreEachStreamsBitrateFromItsStatisticsTagsWhenTheProbeJobProbesAFileThatMkvmergeWrote()
          throws Exception {
    var fixture = getClass().getResource("/matroska-statistics/statistics-tags.mkv");
    assertThat(fixture).isNotNull();
    var source = copyIntoMediaDirectory(Path.of(fixture.toURI()));
    var file = createMediaFile(source);

    var result =
        runProbeJob(new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, mediaRoot), file, source);

    assertThat(result).isEqualTo(new ProbeExecutionResult.Completed());
    var stored = reader.find(file.getId()).orElseThrow();
    assertThat(stored.probeVersion())
        .as("the probe version that the server requests")
        .isEqualTo(ProbeVersion.CURRENT);
    // The BPS tags that mkvmerge wrote. ffprobe reports no bitrate for either stream.
    assertThat(stored.outcome())
        .isInstanceOfSatisfying(
            ProbeOutcome.Success.class,
            success ->
                assertThat(success.streams())
                    .extracting(StreamInfo::codecType, StreamInfo::bitrate)
                    .containsExactly(
                        tuple("video", OptionalLong.of(15_596)),
                        tuple("audio", OptionalLong.of(64_314))));
  }

  @Test
  @DisplayName(
      "Should store the audio stream's channel layout and sample format and the video stream's sample entry and major brand when the probe job probes an MP4 file")
  void
      shouldStoreTheAudioLayoutAndSampleFormatAndTheVideoSampleEntryAndMajorBrandWhenTheProbeJobProbesAnMp4File()
          throws Exception {
    var source =
        worker.generateMedia(mediaDirectory().resolve("four-channel.mp4"), fourChannelMp4());
    var file = createMediaFile(source);

    var result =
        runProbeJob(new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, mediaRoot), file, source);

    assertThat(result).isEqualTo(new ProbeExecutionResult.Completed());
    var stored = reader.find(file.getId()).orElseThrow();
    // FFmpeg's MP4 muxer writes the isom major brand and the avc1 sample entry for H.264. Its
    // AAC decoder names AAC channel configuration 4 as the 4.0 layout and decodes to planar
    // float.
    assertThat(stored.outcome())
        .isInstanceOfSatisfying(
            ProbeOutcome.Success.class,
            success -> {
              assertThat(success.container().majorBrand()).contains("isom");
              assertThat(success.streams())
                  .extracting(
                      StreamInfo::codecType,
                      StreamInfo::sampleEntry,
                      stream ->
                          stream.audioProperties().flatMap(AudioStreamProperties::channelLayout),
                      stream ->
                          stream.audioProperties().flatMap(AudioStreamProperties::sampleFormat))
                  .containsExactly(
                      tuple("video", Optional.of("avc1"), Optional.empty(), Optional.empty()),
                      tuple("audio", Optional.empty(), Optional.of("4.0"), Optional.of("fltp")));
            });
  }

  @Test
  @DisplayName(
      "Should store the video stream's maximum frame rate when the probe job probes a constant frame rate MP4 file")
  void shouldStoreTheVideoStreamsMaximumFrameRateWhenTheProbeJobProbesAConstantFrameRateMp4File()
      throws Exception {
    var source =
        worker.generateMedia(mediaDirectory().resolve("constant-rate.mp4"), constantRateMp4());
    var file = createMediaFile(source);

    var result =
        runProbeJob(new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, mediaRoot), file, source);

    assertThat(result).isEqualTo(new ProbeExecutionResult.Completed());
    var stored = reader.find(file.getId()).orElseThrow();
    assertThat(stored.probeVersion()).isEqualTo(ProbeVersion.CURRENT);
    assertThat(stored.outcome())
        .isInstanceOfSatisfying(
            ProbeOutcome.Success.class,
            success ->
                assertThat(success.streams())
                    .extracting(StreamInfo::codecType, StreamInfo::maximumFrameRate)
                    .containsExactly(tuple("video", Optional.of(new Fraction(24, 1)))));
  }

  // The worker reads the source root as another user, and the fixture makes the root readable to
  // it only when the worker starts. A directory or copied file that a test adds later is made
  // readable here, whatever the umask.
  private static Path mediaDirectory() throws IOException {
    var directory = Files.createDirectory(mediaRoot.resolve(UUID.randomUUID().toString()));
    return Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  private static Path copyIntoMediaDirectory(Path fixture) throws IOException {
    var copy = Files.copy(fixture, mediaDirectory().resolve(fixture.getFileName()));
    return Files.setPosixFilePermissions(copy, PosixFilePermissions.fromString("rw-r--r--"));
  }

  // Each seek encodes from the media segment that holds the chapter's start. The chapters start on
  // the 6 s grid, so that segment opens at the chapter's start.
  private static double firstTimestampAfterSeeking(
      RemoteTranscodeExecutor executor,
      TranscodeRequest.TranscodeRequestBuilder seek,
      double startSeconds) {
    var sessionId = UUID.randomUUID();
    var index = (int) Math.round(startSeconds / SEGMENT_SECONDS);
    executor.start(
        seek.sessionId(sessionId)
            .seekPosition(index * SEGMENT_SECONDS)
            .startSequenceNumber(index)
            .build());
    try {
      awaitSegment(sessionId, index);
      return Fmp4Fixture.firstVideoPresentationTime(
          Fmp4Fixture.withInitializationSegment(
              segmentStore, sessionId, SegmentNames.mediaSegmentName(index)));
    } finally {
      executor.stop(sessionId);
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

  private static ProbeOutcome.Success probe(Path source) {
    var outcome =
        new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, mediaRoot)
            .probe(probeRequest(source));
    assertThat(outcome).isInstanceOf(ProbeOutcome.Success.class);
    return (ProbeOutcome.Success) outcome;
  }

  private ProbeExecutionResult runProbeJob(
      RemoteFfprobeService producer, MediaFile file, Path source) throws IOException {
    return probeExecution.toBuilder()
        .producer(producer)
        .packetScans(new CapturingKeyframePacketScanRequests())
        .build()
        .execute(probeTaskRequest(file, source), ProbePriority.BACKGROUND);
  }

  private static ProbeExecutionRequest probeRequest(Path source) {
    return ProbeExecutionRequest.builder()
        .sourcePath(source)
        .attemptId(UUID.randomUUID())
        .probeVersion(ProbeVersion.CURRENT)
        .priority(ProbePriority.BACKGROUND)
        .build();
  }

  private static void awaitSegment(UUID sessionId, int index) {
    await()
        .alias("the job attempt publishes media segment %d".formatted(index))
        .atMost(Duration.ofSeconds(60))
        .until(
            () ->
                segmentStore.segmentExists(sessionId, SegmentNames.INITIALIZATION_SEGMENT_NAME)
                    && segmentStore.segmentExists(sessionId, SegmentNames.mediaSegmentName(index)));
  }

  private static int publishedSegmentCount(UUID sessionId) {
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

  // H.264 High at level 4.0, tagged BT.709 in limited range. The encoder takes the color tags from
  // the frames, so the filter sets them there.
  private static List<String> bt709Media() {
    return List.of(
        "-f",
        "lavfi",
        "-i",
        "testsrc=size=320x180:rate=24:duration=2",
        "-vf",
        "setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv",
        "-c:v",
        "libx264",
        "-profile:v",
        "high",
        "-level:v",
        "4.0",
        "-pix_fmt",
        "yuv420p");
  }

  // H.264 video and four-channel AAC audio in an MP4 file.
  private static List<String> fourChannelMp4() {
    return List.of(
        "-f",
        "lavfi",
        "-i",
        "testsrc=size=320x180:rate=24:duration=2",
        "-f",
        "lavfi",
        "-i",
        "anullsrc=channel_layout=4.0:sample_rate=48000",
        "-t",
        "2",
        "-c:v",
        "libx264",
        "-c:a",
        "aac");
  }

  // Two seconds of H.264 video at a constant 24 fps in an MP4 file.
  private static List<String> constantRateMp4() {
    return List.of(
        "-f",
        "lavfi",
        "-i",
        "testsrc=size=320x180:rate=24:duration=2",
        "-c:v",
        "libx264",
        "-pix_fmt",
        "yuv420p");
  }

  // Two seconds at 24 fps with a keyframe every second. MP4 keeps each frame's duration exact, so
  // every repeat of the clip starts on the 24 fps grid. Matroska stores the last frame's duration
  // in whole milliseconds, and each repeat would start 1 ms early. testsrc names no color
  // primaries, transfer or matrix, and without them MP4 stores the color range only with
  // write_colr.
  private static List<String> shortMedia() {
    return List.of(
        "-f",
        "lavfi",
        "-i",
        "testsrc=size=320x180:rate=24:duration=2",
        "-c:v",
        "libx264",
        "-preset",
        "ultrafast",
        "-pix_fmt",
        "yuv420p",
        "-g",
        "24",
        "-movflags",
        "+write_colr");
  }

  // Thirty minutes, so the job attempt still runs after the oversized probe ends. Repeating the
  // clip by stream copy encodes no more frames. MP4 marks its track as the default, which an
  // encode into Matroska does not.
  private static List<String> longMedia(Path clip) {
    return List.of(
        "-stream_loop",
        "-1",
        "-i",
        worker.containerPath(clip),
        "-c",
        "copy",
        "-t",
        "1800",
        "-disposition:v",
        "0");
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

  private MediaFile createMediaFile(Path source) {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie =
        movieRepository.saveAndFlush(Movie.builder().title("Chapters").library(library).build());
    return mediaFileRepository.saveAndFlush(
        MediaFile.builder()
            .mediaId(movie.getId())
            .libraryId(library.getId())
            .status(MediaFileStatus.MATCHED)
            .filename(source.getFileName().toString())
            .filepathUri(FilepathCodec.encode(source))
            .build());
  }

  private static ProbeTaskRequest probeTaskRequest(MediaFile file, Path source) throws IOException {
    var attributes = Files.readAttributes(source, BasicFileAttributes.class);
    return ProbeTaskRequest.builder()
        .mediaFileId(file.getId())
        .libraryId(file.getLibraryId())
        .filepathUri(file.getFilepathUri())
        .snapshot(
            new SourceFileSnapshot(attributes.size(), attributes.lastModifiedTime().toInstant()))
        .probeVersion(ProbeVersion.CURRENT)
        .build();
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
}
