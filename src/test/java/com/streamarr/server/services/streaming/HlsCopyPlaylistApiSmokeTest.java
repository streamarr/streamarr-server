package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.PlaybackLanguagePreferencesFixture.noLanguagePreferences;
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.streamarr.server.controllers.StreamController;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.CapturingKeyframePacketScanRequests;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.BitrateScanRequesters;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.fixtures.PlaybackSessionFixture;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.properties.RemoteTranscodeProperties;
import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.library.KeyframePacketScanExecution;
import com.streamarr.server.services.library.KeyframePacketScanResult;
import com.streamarr.server.services.library.ProbeExecution;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.probe.PlaybackProbeClaims;
import com.streamarr.server.services.streaming.events.JobAttemptCompletedEvent;
import com.streamarr.server.services.streaming.events.JobAttemptProgressedEvent;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRegistry;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Plays stream copies as a client does, against the pinned worker image: the probe task stores the
 * source's probe outcome, the playback session service starts playback, and the client reads the
 * media playlist and every segment that it lists from the stream endpoints.
 */
@Tag("SmokeTest")
@DisplayName("HLS Stream Copy Playlist API Smoke Tests")
class HlsCopyPlaylistApiSmokeTest {

  private static final int SEGMENT_SECONDS = 6;
  private static final double FRAME_SECONDS = 1.0 / 24;
  private static final Offset<Double> HALF_A_FRAME = within(FRAME_SECONDS / 2);
  // The AAC encoder's priming samples make each source about 23 ms longer than its video, which
  // the last segment's EXTINF includes. The offset stays below one frame.
  private static final Offset<Double> AUDIO_TAIL = within(0.03);
  private static final String TOKEN = "smoke-token";
  private static final Duration RESPONSE_BOUND = Duration.ofSeconds(90);

  // Keyframes every 10 s: the 6 s slots [12 s, 18 s) and [24 s, 30 s) hold no keyframe.
  private static final List<String> KEYFRAME_GAPS_MATROSKA =
      source(36, List.of("-force_key_frames", "0,10,20,30"), List.of("-f", "matroska"));

  // Keyframes off the 6 s grid: every 6 s slot holds one keyframe, and none starts a slot.
  private static final List<String> IRREGULAR_KEYFRAMES_MATROSKA =
      source(36, List.of("-force_key_frames", "0,7.5,13,20.5,26,31"), List.of("-f", "matroska"));

  private static final List<String> KEYFRAME_EVERY_TWO_SECONDS =
      List.of("-force_key_frames", "expr:gte(t,n_forced*2)");

  // Each container writes its index before its media, so the index survives a cut of the file.
  private static final List<String> INDEX_FIRST_MP4 =
      source(30, KEYFRAME_EVERY_TWO_SECONDS, List.of("-movflags", "+faststart", "-f", "mp4"));
  private static final List<String> INDEX_FIRST_MATROSKA =
      source(
          30, KEYFRAME_EVERY_TWO_SECONDS, List.of("-reserve_index_space", "4k", "-f", "matroska"));

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeMediaFileContainerInfoRepository probeOutcomes =
      new FakeMediaFileContainerInfoRepository();
  private final CapturingKeyframePacketScanRequests packetScans =
      new CapturingKeyframePacketScanRequests();
  private final AuthenticatedIdentity viewer =
      StreamSessionFixture.identityFor(
          StreamSessionFixture.defaultPlaybackAuthorityBuilder().build());
  private volatile UUID streamSessionId;
  private WorkerStreamingSmokeFixture workerFixture;
  private RemoteFfprobeService probes;
  private HlsStreamingService streamingService;
  private PlaybackSessionService playbackSessions;
  private MockMvc streamEndpoints;
  private volatile ProducerLifecycleService producerLifecycle;
  private Path sourceRoot;
  @TempDir private Path temporaryDirectory;

  @BeforeEach
  void setUp() throws Exception {
    sourceRoot = Files.createDirectory(temporaryDirectory.resolve("media"));
    var segmentStore =
        new LocalSegmentStore(Files.createDirectory(temporaryDirectory.resolve("segments")));
    workerFixture =
        WorkerStreamingSmokeFixture.builder()
            .sourceRoot(sourceRoot)
            .segmentStore(segmentStore)
            .eventPublisher(this::onWorkerEvent)
            .build();
    workerFixture.start();
    var workerSessions = workerFixture.workerSessions();
    var namespaceId = workerFixture.sourceNamespaceId();
    probes = new RemoteFfprobeService(workerSessions, namespaceId, sourceRoot);
    var transcodeExecutor = new RemoteTranscodeExecutor(workerSessions, namespaceId, sourceRoot);
    var properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(3)
            .targetSegmentDuration(Duration.ofSeconds(SEGMENT_SECONDS))
            .sessionTimeout(Duration.ofSeconds(60))
            .producerStallThreshold(Duration.ofSeconds(10))
            .build();
    var sessionRegistry = new InMemoryStreamSessionRegistry();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(sessionRegistry)
            .heldRequests(workerSessions.heldSegmentRequests())
            .sessionMutex(new MutexFactory<>())
            .build();
    producerLifecycle = rig.lifecycle();
    workerFixture.creditUploadsTo(producerLifecycle);
    streamingService =
        HlsStreamingService.builder()
            .languagePreferences(noLanguagePreferences())
            .meterRegistry(new SimpleMeterRegistry())
            .mediaFileRepository(mediaFiles)
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .playbackProbeService(playbackProbeServiceBuilder(probeOutcomes).build())
            .transcodeDecisionService(
                new TranscodeDecisionService(properties, new SimpleMeterRegistry()))
            .qualityLadderService(new QualityLadderService())
            .properties(properties)
            .authorityGate((_, _) -> true)
            .runtimeRegistry(sessionRegistry)
            .producerLifecycle(producerLifecycle)
            .deliveryCoordinator(rig.coordinator())
            .build();
    playbackSessions = PlaybackSessionFixture.playbackServiceBuilder(streamingService).build();
    var directPlayMetrics = new DirectPlayMetrics(new SimpleMeterRegistry());
    streamEndpoints =
        MockMvcBuilders.standaloneSetup(
                new StreamController(
                    streamingService,
                    new HlsPlaylistService(properties),
                    rig.coordinator(),
                    new FakeAuthorizationService(this::playbackIdentity, TOKEN),
                    new DirectPlaySourceService(
                        streamingService,
                        new RemoteTranscodeProperties(namespaceId, sourceRoot.toString()),
                        directPlayMetrics),
                    directPlayMetrics))
            .build();
  }

  // The application's listeners restart a job attempt's stall clock when its worker reports
  // progress, and settle a stream copy that ran short when its job attempt completes.
  private void onWorkerEvent(Object event) {
    var lifecycle = producerLifecycle;
    if (lifecycle == null) {
      return;
    }

    switch (event) {
      case JobAttemptProgressedEvent progressed ->
          new JobAttemptProgressListener(lifecycle).onJobAttemptProgressed(progressed);
      case JobAttemptCompletedEvent completed ->
          new ShortCopyListener(lifecycle, _ -> {}).onJobAttemptCompleted(completed);
      default -> {
        // These tests need no other listener.
      }
    }
  }

  @AfterEach
  void tearDown() {
    if (workerFixture == null) {
      return;
    }

    try {
      if (streamingService != null) {
        streamingService
            .getAllSessions()
            .forEach(session -> streamingService.destroySession(session.getSessionId()));
      }
    } finally {
      workerFixture.close();
    }
  }

  // ADR 0037, segment list: a slot without a keyframe merges into the segment before it, so the
  // keyframes at 0, 10, 20 and 30 s open four segments that last 10, 10, 10 and 6 s.
  @Test
  @DisplayName(
      "Should serve every source video packet from the first job attempt when a stream copy's"
          + " keyframe gap exceeds the segment period")
  void shouldServeEverySourceVideoPacketFromTheFirstJobAttemptWhenTheKeyframeGapExceedsThePeriod()
      throws Exception {
    var source = generateSource("keyframe-gaps.mkv", KEYFRAME_GAPS_MATROSKA);
    storeProbeOutcome(source);

    var session = startCopyPlayback(source);
    var firstAttempt = session.getHandle().orElseThrow().attemptId();
    var playlist = mediaPlaylist();
    var served = servedSegments(playlist);

    assertThat(playlist.segments())
        .extracting(AdvertisedSegment::seconds)
        .as("the copy's segments open at the keyframes 10 s apart")
        .zipSatisfy(
            List.of(10.0, 10.0, 10.0, 6.0),
            (advertised, expected) -> assertThat(advertised).isCloseTo(expected, AUDIO_TAIL));
    assertThat(workerFixture.worker().videoPacketHashes(playable(playlist, served)))
        .as("the segments carry every source video packet once, in order, across both gaps")
        .isEqualTo(workerFixture.worker().videoPacketHashes(source));
    assertThat(session.getHandle().orElseThrow().attemptId())
        .as("no failure of the first job attempt made the server replace it")
        .isEqualTo(firstAttempt);
  }

  // RFC 8216 §4.3.3.1: an EXTINF states its segment's duration, and no EXTINF, rounded to the
  // nearest integer, exceeds the target duration. The keyframes at 0, 7.5, 13, 20.5, 26 and 31 s
  // open segments that last 7.5, 5.5, 7.5, 5.5, 5 and 5 s.
  @Test
  @DisplayName(
      "Should advertise each segment's real duration when a stream copy's keyframes are irregular")
  void shouldAdvertiseEachSegmentsRealDurationWhenAStreamCopysKeyframesAreIrregular()
      throws Exception {
    var source = generateSource("irregular-keyframes.mkv", IRREGULAR_KEYFRAMES_MATROSKA);
    storeProbeOutcome(source);

    startCopyPlayback(source);
    var playlist = mediaPlaylist();
    var served = servedSegments(playlist);

    assertThat(playlist.segments())
        .extracting(AdvertisedSegment::seconds)
        .zipSatisfy(
            List.of(7.5, 5.5, 7.5, 5.5, 5.0, 5.0),
            (advertised, expected) -> assertThat(advertised).isCloseTo(expected, AUDIO_TAIL));
    assertThat(playlist.targetDurationSeconds()).isEqualTo(8);
    assertThat(videoSecondsOf(playlist, served))
        .as("the video that each served segment holds lasts as long as its EXTINF says")
        .zipSatisfy(
            playlist.segments(),
            (video, advertised) -> assertThat(video).isCloseTo(advertised.seconds(), AUDIO_TAIL));
  }

  // The index lists every keyframe of the 30 s source, but the file's data ends near 14 s. The
  // source has a keyframe every 2 s, so every 6 s slot that starts before the real end opens a
  // segment, and the plain grid's later slots open none.
  @ParameterizedTest(name = "{0}")
  @MethodSource("indexFirstSources")
  @DisplayName(
      "Should advertise no segment past the real end when the packet scan places the keyframes of"
          + " a truncated source")
  void shouldAdvertiseNoSegmentPastTheRealEndWhenThePacketScanPlacesTheKeyframesOfATruncatedSource(
      String fileName, List<String> mediaArguments) throws Exception {
    var source = generateSource(fileName, mediaArguments);
    truncateToHalf(source);
    var realEnd = lastVideoPresentationTime(source);
    var file = storeProbeOutcome(source);

    assertThat(probeOutcomes.findKeyframeKnowledge(file.getId()))
        .as("the probe cannot read the last keyframe that the index lists")
        .contains(
            new KeyframeKnowledge.PacketScanRequired(
                KeyframePacketScanReason.UNREADABLE_LAST_INDEXED_KEYFRAME));
    assertThat(runRequestedPacketScans()).containsExactly(new KeyframePacketScanResult.Completed());
    assertThat(probeOutcomes.findKeyframeKnowledge(file.getId()))
        .get()
        .isInstanceOfSatisfying(
            KeyframeKnowledge.Known.class,
            known ->
                assertThat(known.positions().source())
                    .isEqualTo(KeyframePositionSource.PACKET_SCAN));

    startCopyPlayback(source);
    var playlist = mediaPlaylist();
    var served = servedSegments(playlist);

    assertThat(realEnd)
        .as("the cut source ends after its second slot and before the plain grid's last slot")
        .isStrictlyBetween(6.0, 24.0);
    assertThat(playlist.segmentStarts())
        .as("one segment for each 6 s slot that starts before the real end at %s s", realEnd)
        .zipSatisfy(
            slotStartsBefore(realEnd),
            (advertised, slotStart) -> assertThat(advertised).isCloseTo(slotStart, HALF_A_FRAME));
    assertThat(videoOpenings(playlist, served))
        .as("each advertised segment's video opens where the playlist lists its start")
        .zipSatisfy(
            playlist.segmentStarts(),
            (opening, advertised) -> assertThat(opening).isCloseTo(advertised, HALF_A_FRAME));
  }

  static Stream<Arguments> indexFirstSources() {
    return Stream.of(
        Arguments.of("truncated.mp4", INDEX_FIRST_MP4),
        Arguments.of("truncated.mkv", INDEX_FIRST_MATROSKA));
  }

  // 24 fps video with a keyframe only where forced, and AAC audio of the same length.
  private static List<String> source(
      int seconds, List<String> keyframeArguments, List<String> formatArguments) {
    var arguments =
        new ArrayList<>(
            List.of(
                "-f",
                "lavfi",
                "-i",
                "testsrc=size=160x90:rate=24:duration=" + seconds,
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:duration=" + seconds,
                "-c:v",
                "libx264",
                "-pix_fmt",
                "yuv420p",
                "-g",
                "1000",
                "-keyint_min",
                "1000",
                "-sc_threshold",
                "0"));
    arguments.addAll(keyframeArguments);
    arguments.addAll(List.of("-c:a", "aac", "-shortest"));
    arguments.addAll(formatArguments);
    return List.copyOf(arguments);
  }

  private Path generateSource(String fileName, List<String> mediaArguments) throws Exception {
    return workerFixture.worker().generateMedia(sourceRoot.resolve(fileName), mediaArguments);
  }

  private static void truncateToHalf(Path source) throws Exception {
    try (var channel = FileChannel.open(source, StandardOpenOption.WRITE)) {
      channel.truncate(channel.size() / 2);
    }
  }

  private double lastVideoPresentationTime(Path source) throws Exception {
    return workerFixture.worker().packetTimestamps(source).stream()
        .max(Double::compare)
        .orElseThrow();
  }

  private static List<Double> slotStartsBefore(double seconds) {
    return Stream.iterate(0.0, start -> start < seconds, start -> start + SEGMENT_SECONDS).toList();
  }

  // Runs the probe task of the source with the worker, as a library scan requests it.
  private MediaFile storeProbeOutcome(Path source) throws Exception {
    var file =
        mediaFiles.save(
            MediaFile.builder()
                .libraryId(UUID.randomUUID())
                .filepathUri(FilepathCodec.encode(source))
                .filename(source.getFileName().toString())
                .status(MediaFileStatus.MATCHED)
                .size(Files.size(source))
                .build());
    ProbeExecution.builder()
        .mediaFiles(mediaFiles)
        .reader(new PersistedProbeReader(probeOutcomes))
        .producer(probes)
        .fileSystem(FileSystems.getDefault())
        .outcomes(probeOutcomes)
        .packetScans(packetScans)
        .bitrateScans(BitrateScanRequesters.none())
        .transactionManager(new FakeTransactionManager())
        .playbackProbeClaims(new PlaybackProbeClaims(System::nanoTime, new SimpleMeterRegistry()))
        .build()
        .execute(
            ProbeTaskRequest.builder()
                .mediaFileId(file.getId())
                .libraryId(file.getLibraryId())
                .filepathUri(file.getFilepathUri())
                .snapshot(
                    SourceFileSnapshot.of(Files.readAttributes(source, BasicFileAttributes.class)))
                .probeVersion(ProbeVersion.CURRENT)
                .build(),
            ProbePriority.BACKGROUND);
    return file;
  }

  // Runs each keyframe packet scan that a probe task requested, as the scan's task does.
  private List<KeyframePacketScanResult> runRequestedPacketScans() {
    var execution =
        KeyframePacketScanExecution.builder()
            .reader(new PersistedProbeReader(probeOutcomes))
            .scanner(probes::scanKeyframePackets)
            .fileSystem(FileSystems.getDefault())
            .outcomes(probeOutcomes)
            .eventPublisher(new CapturingEventPublisher())
            .transactionManager(new FakeTransactionManager())
            .meterRegistry(new SimpleMeterRegistry())
            .build();
    return packetScans.requests().stream().map(execution::execute).toList();
  }

  // Starts playback as the client's mutation does. The playback token that the client then sends
  // to the stream endpoints names the stream session of the format attempt.
  private StreamSession startCopyPlayback(Path source) {
    var file = mediaFiles.findFirstByFilepathUri(FilepathCodec.encode(source)).orElseThrow();
    var playback =
        PlaybackSessionFixture.acceptedPlayback(
            playbackSessions.start(
                PlaybackSessionFixture.startPlaybackBuilder()
                    .mediaFileId(file.getId())
                    .identity(viewer)
                    .build()));

    assertThat(playback.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Ready.class);
    var session = ((PlaybackFormatAttempt.Ready) playback.currentFormatAttempt()).session();
    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    streamSessionId = session.getSessionId();
    return session;
  }

  private AuthenticatedIdentity playbackIdentity() {
    return AuthenticatedIdentityFixture.defaultIdentityBuilder()
        .accountId(viewer.accountId())
        .authSessionId(viewer.authSessionId())
        .householdId(viewer.householdId())
        .contextHouseholdId(viewer.contextHouseholdId())
        .profileId(viewer.profileId())
        .streamSessionId(streamSessionId)
        .build();
  }

  private MediaPlaylist mediaPlaylist() throws Exception {
    var response = responseTo("stream.m3u8?t=" + TOKEN);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    return MediaPlaylist.parse(response.getContentAsString());
  }

  // The body of each media segment that the playlist lists, requested in order by its URI.
  private List<byte[]> servedSegments(MediaPlaylist playlist) throws Exception {
    var served = new ArrayList<byte[]>();
    for (var segment : playlist.segments()) {
      served.add(bodyOf(segment.uri()));
    }

    return served;
  }

  private byte[] bodyOf(String uri) throws Exception {
    var response = responseTo(uri);

    assertThat(response.getStatus()).as("the status of %s", uri).isEqualTo(HttpStatus.OK.value());
    return response.getContentAsByteArray();
  }

  // A segment request waits for its segment, so each response is bounded.
  private MockHttpServletResponse responseTo(String uri) throws Exception {
    var request = get(URI.create("/api/stream/%s/%s".formatted(streamSessionId, uri)));
    return CompletableFuture.supplyAsync(
            () -> {
              try {
                return streamEndpoints.perform(request).andReturn().getResponse();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            })
        .get(RESPONSE_BOUND.toSeconds(), TimeUnit.SECONDS);
  }

  // The initialization segment and the served media segments, as one file that a player reads.
  private Path playable(MediaPlaylist playlist, List<byte[]> mediaSegments) throws Exception {
    var media = new ByteArrayOutputStream();
    media.writeBytes(bodyOf(playlist.initializationUri()));
    mediaSegments.forEach(media::writeBytes);
    return Files.write(
        Files.createTempFile(temporaryDirectory, "served", ".mp4"), media.toByteArray());
  }

  // When each served segment's first video sample presents: its track fragment's decode time plus
  // the sample's composition offset. FFmpeg's MP4 demuxer shifts every presentation time by the
  // track's largest negative composition offset, one frame here, so ffprobe prints each one late.
  private List<Double> videoOpenings(MediaPlaylist playlist, List<byte[]> served) throws Exception {
    var initialization = bodyOf(playlist.initializationUri());
    return served.stream()
        .map(segment -> Fmp4Fixture.firstVideoPresentationTime(joined(initialization, segment)))
        .toList();
  }

  private static byte[] joined(byte[] initialization, byte[] mediaSegment) {
    var media = new ByteArrayOutputStream();
    media.writeBytes(initialization);
    media.writeBytes(mediaSegment);
    return media.toByteArray();
  }

  // From a segment's first frame to the end of its last frame.
  private List<Double> videoSecondsOf(MediaPlaylist playlist, List<byte[]> served)
      throws Exception {
    var durations = new ArrayList<Double>();
    for (var segment : served) {
      var times = videoPresentationTimes(playlist, segment);
      var first = times.stream().min(Double::compare).orElseThrow();
      var last = times.stream().max(Double::compare).orElseThrow();
      durations.add(last - first + FRAME_SECONDS);
    }

    return durations;
  }

  private List<Double> videoPresentationTimes(MediaPlaylist playlist, byte[] segment)
      throws Exception {
    return workerFixture.worker().packetTimestamps(playable(playlist, List.of(segment)));
  }

  private record AdvertisedSegment(double seconds, String uri) {}

  // What a client reads from a media playlist: the lines that RFC 8216 §4.3 defines.
  private record MediaPlaylist(
      int targetDurationSeconds, String initializationUri, List<AdvertisedSegment> segments) {

    private static final String TARGET_DURATION = "#EXT-X-TARGETDURATION:";
    private static final String MAP_URI = "#EXT-X-MAP:URI=\"";
    private static final String EXTINF = "#EXTINF:";

    static MediaPlaylist parse(String playlist) {
      var lines = playlist.lines().toList();
      var segments = new ArrayList<AdvertisedSegment>();
      for (var index = 0; index < lines.size() - 1; index++) {
        var line = lines.get(index);
        if (line.startsWith(EXTINF)) {
          var seconds = Double.parseDouble(line.substring(EXTINF.length(), line.indexOf(',')));
          segments.add(new AdvertisedSegment(seconds, lines.get(index + 1)));
        }
      }

      return new MediaPlaylist(
          Integer.parseInt(valueAfter(lines, TARGET_DURATION)),
          valueAfter(lines, MAP_URI).replace("\"", ""),
          List.copyOf(segments));
    }

    private static String valueAfter(List<String> lines, String prefix) {
      return lines.stream()
          .filter(line -> line.startsWith(prefix))
          .map(line -> line.substring(prefix.length()))
          .findFirst()
          .orElseThrow();
    }

    // Where each segment starts: the sum of the EXTINF durations before it.
    List<Double> segmentStarts() {
      var starts = new ArrayList<Double>();
      var start = 0.0;
      for (var segment : segments) {
        starts.add(start);
        start += segment.seconds();
      }

      return starts;
    }
  }
}
