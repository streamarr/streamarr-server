package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.PlaybackLanguagePreferencesFixture.noLanguagePreferences;
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.streamarr.server.config.RemoteTranscodeProperties;
import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.controllers.StreamController;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.task.ProbeTaskRequest;
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
import com.streamarr.server.fixtures.UploadBudgetFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.filepath.FilepathCodec;
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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.Builder;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Plays a stream session that lists an encoded lower variant beside a copy as a client does,
 * against the pinned worker image: the probe task stores the source's probe outcome, the playback
 * session service starts playback in Auto, and the client reads the multivariant playlist, each
 * variant's media playlist and the segments that it plays from the stream endpoints.
 */
@Tag("SmokeTest")
@DisplayName("HLS Copied and Encoded Variant Switch Smoke Tests")
class HlsVariantSwitchSmokeTest {

  private static final int SEGMENT_SECONDS = 6;
  private static final int FRAMES_PER_SECOND = 24;
  // ffprobe reads the Matroska source's timestamps in whole milliseconds, and the served MP4's in
  // its track's timescale.
  private static final Offset<Double> MATROSKA_ROUNDING = within(0.001);
  private static final String TOKEN = "smoke-token";
  private static final Duration RESPONSE_BOUND = Duration.ofSeconds(90);

  // The arguments of the source's video encoder. libx264's default B-frames give the copy a reorder
  // delay, as the worker's own encode has.
  private static final List<String> WITH_B_FRAMES = List.of();

  // Without B-frames the copy has no reorder delay and the worker's encode has one, so the two
  // variants agree only when each places its frames by presentation time.
  private static final List<String> WITHOUT_B_FRAMES = List.of("-bf", "0");

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeMediaFileContainerInfoRepository probeOutcomes =
      new FakeMediaFileContainerInfoRepository();
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
    streamingService =
        HlsStreamingService.builder()
            .languagePreferences(noLanguagePreferences())
            .uploadBudget(UploadBudgetFixture.OFF)
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
                    new HlsPlaylistService(properties, UploadBudgetFixture.OFF),
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

  // RFC 8216 §6.2.4: so that a client can switch between variant streams, matching content has
  // matching timestamps and discontinuity sequence numbers, and each media playlist has the same
  // target duration. The keyframes at 0, 10, 20 and 30 s open four segments, and the player changes
  // variant at each of the three boundaries between them.
  @ParameterizedTest(name = "{0}")
  @MethodSource("switchingPlayers")
  @DisplayName(
      "Should present every source frame and audio packet once when a player switches between the"
          + " copied and an encoded variant at each boundary of a merged segment list")
  void shouldPresentEverySourceFrameAndAudioPacketOnceWhenAPlayerSwitchesAtEachMergedBoundary(
      String description, List<String> encoderArguments, List<Variant> variantOfEachSegment)
      throws Exception {
    var source =
        workerFixture
            .worker()
            .generateMedia(
                sourceRoot.resolve("keyframes-ten-seconds-apart.mkv"),
                noisy480pWithKeyframesTenSecondsApart(encoderArguments));
    storeProbeOutcome(source);

    var session = startAutoPlayback(source);
    var variantPlaylists = variantPlaylistUris();
    var copied = mediaPlaylist(variantPlaylists.getFirst());
    var encoded = mediaPlaylist(variantPlaylists.getLast());

    assertThat(session.decisionOf(StreamSession.defaultVariant()).transcodeMode().copiesVideo())
        .as("the first-listed variant copies the video")
        .isTrue();
    assertThat(variantPlaylists)
        .as("the multivariant playlist lists the copy first and one encoded variant below it")
        .containsExactly("stream.m3u8?t=" + TOKEN, "360p/stream.m3u8?t=" + TOKEN);
    assertThat(session.decisionOf("360p").transcodeMode().copiesVideo())
        .as("the 360p variant encodes the video")
        .isFalse();
    assertThat(copied.listing().segments())
        .extracting(ListedSegment::seconds)
        .as("the copy's four segments open at the keyframes 10 s apart")
        .startsWith(10.0, 10.0, 10.0)
        .hasSameSizeAs(variantOfEachSegment);
    assertThat(encoded.listing())
        .as("the encoded variant's playlist lists the copy's segments")
        .isEqualTo(copied.listing());

    var playlists = Map.of(Variant.COPIED, copied, Variant.ENCODED, encoded);
    var playedFrom =
        Map.of(Variant.COPIED, new ArrayList<Path>(), Variant.ENCODED, new ArrayList<Path>());
    var played = new ArrayList<Path>();
    for (var index = 0; index < variantOfEachSegment.size(); index++) {
      var variant = variantOfEachSegment.get(index);
      var segment = playable(playlists.get(variant), index);
      playedFrom.get(variant).add(segment);
      played.add(segment);
    }

    var sourceVideoPackets = videoPackets(List.of(source));

    assertThat(videoPackets(playedFrom.get(Variant.COPIED)))
        .as("the segments played from the copy carry the source's own video packets")
        .isSubsetOf(sourceVideoPackets);
    assertThat(videoPackets(playedFrom.get(Variant.ENCODED)))
        .as("the segments played from the 360p variant carry none of the source's video packets")
        .doesNotContainAnyElementsOf(sourceVideoPackets);
    assertThat(decodedFrameCounts(played))
        .as("each played segment decodes every frame it holds alone, so it opens on a keyframe")
        .isEqualTo(heldFrameCounts(played));
    assertThat(presentedFrames(played))
        .as("the video frames that the player presents across its switches at 10, 20 and 30 s")
        .isEqualTo(sourceFrames(source));
    assertThat(audioPackets(played))
        .as("the audio packets that the player plays across its switches at 10, 20 and 30 s")
        .isEqualTo(audioPackets(List.of(source)));
    assertThat(audioPacketTimes(played))
        .as("when each of those audio packets presents")
        .zipSatisfy(
            audioPacketTimes(List.of(source)),
            (served, inSource) -> assertThat(served).isCloseTo(inSource, MATROSKA_ROUNDING));
  }

  static Stream<Arguments> switchingPlayers() {
    var fromTheCopy = List.of(Variant.COPIED, Variant.ENCODED, Variant.COPIED, Variant.ENCODED);
    var fromTheEncodedVariant =
        List.of(Variant.ENCODED, Variant.COPIED, Variant.ENCODED, Variant.COPIED);
    return Stream.of(
        Arguments.of("a source with B-frames, played from the copy", WITH_B_FRAMES, fromTheCopy),
        Arguments.of(
            "a source with B-frames, played from the encoded variant",
            WITH_B_FRAMES,
            fromTheEncodedVariant),
        Arguments.of(
            "a source without B-frames, played from the copy", WITHOUT_B_FRAMES, fromTheCopy),
        Arguments.of(
            "a source without B-frames, played from the encoded variant",
            WITHOUT_B_FRAMES,
            fromTheEncodedVariant));
  }

  // 854x480 noise at 24 fps and 3 Mb/s keeps the copy's bitrate above the 360p variant's, so Auto
  // lists that variant beside the copy. Keyframes lie only at 0, 10, 20 and 30 s: the 6 s slots [12
  // s, 18 s)
  // and [24 s, 30 s) hold none, so each joins the segment before it (ADR 0037, segment list).
  private static List<String> noisy480pWithKeyframesTenSecondsApart(List<String> encoderArguments) {
    var arguments =
        new ArrayList<>(
            List.of(
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=854x480:duration=36:rate=24,noise=alls=40:allf=t",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:duration=36",
                "-c:v",
                "libx264",
                "-pix_fmt",
                "yuv420p",
                "-b:v",
                "3M",
                "-g",
                "1000",
                "-keyint_min",
                "1000",
                "-sc_threshold",
                "0",
                "-force_key_frames",
                "0,10,20,30"));
    arguments.addAll(encoderArguments);
    arguments.addAll(List.of("-c:a", "aac", "-shortest", "-f", "matroska"));
    return List.copyOf(arguments);
  }

  // Runs the probe task of the source with the worker, as a library scan requests it.
  private void storeProbeOutcome(Path source) throws Exception {
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
        .packetScans(new CapturingKeyframePacketScanRequests())
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
  }

  // Starts playback in Auto as the client's mutation does. The playback token that the client then
  // sends to the stream endpoints names the stream session of the format attempt.
  private StreamSession startAutoPlayback(Path source) {
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

  // The URI of each variant's media playlist, in the multivariant playlist's order.
  private List<String> variantPlaylistUris() throws Exception {
    var response = responseTo(streamUri("multivariant.m3u8?t=" + TOKEN));

    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    return response.getContentAsString().lines().filter(line -> !line.startsWith("#")).toList();
  }

  private MediaPlaylist mediaPlaylist(String playlistUri) throws Exception {
    var uri = streamUri(playlistUri);
    var response = responseTo(uri);

    assertThat(response.getStatus()).as("the status of %s", uri).isEqualTo(HttpStatus.OK.value());
    return MediaPlaylist.parse(uri, response.getContentAsString());
  }

  private URI streamUri(String relativeUri) {
    return URI.create("/api/stream/%s/".formatted(streamSessionId)).resolve(relativeUri);
  }

  // The playlist's initialization segment and one of its media segments, requested by the URIs
  // that the playlist lists, as one file that a player reads.
  private Path playable(MediaPlaylist playlist, int segmentIndex) throws Exception {
    var media = new ByteArrayOutputStream();
    media.writeBytes(bodyOf(playlist.resolve(playlist.initializationUri())));
    media.writeBytes(bodyOf(playlist.resolve(playlist.segmentUris().get(segmentIndex))));
    return Files.write(
        Files.createTempFile(temporaryDirectory, "served", ".mp4"), media.toByteArray());
  }

  private byte[] bodyOf(URI uri) throws Exception {
    var response = responseTo(uri);

    assertThat(response.getStatus()).as("the status of %s", uri).isEqualTo(HttpStatus.OK.value());
    return response.getContentAsByteArray();
  }

  // A segment request waits for its segment, so each response is bounded.
  private MockHttpServletResponse responseTo(URI uri) throws Exception {
    return CompletableFuture.supplyAsync(
            () -> {
              try {
                return streamEndpoints.perform(get(uri)).andReturn().getResponse();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            })
        .get(RESPONSE_BOUND.toSeconds(), TimeUnit.SECONDS);
  }

  // The number of each video frame that the files present, on the 24 fps timeline and in the
  // files' order. Times count in whole frames: a switch may repeat or skip less than one output
  // frame (ADR 0050). ffprobe reports a fragmented MP4's timestamps later by its most negative
  // composition offset, the video's reorder delay, which can differ between the copy and the
  // encode. So the first sample's composition time comes from the boxes (tfdt plus composition
  // offset, ISO/IEC 14496-12 8.8.12 and 8.8.8), without an edit list, and ffprobe gives every
  // other frame's distance from that sample. A file's packets come in decode order, so each file's
  // frames are sorted.
  private List<Long> presentedFrames(List<Path> media) throws Exception {
    var frames = new ArrayList<Long>();
    for (var file : media) {
      var firstSample = Fmp4Fixture.firstVideoPresentationTime(Files.readAllBytes(file));
      var probed = workerFixture.worker().packetTimestamps(file);
      probed.stream()
          .map(seconds -> frameNumber(firstSample + seconds - probed.getFirst()))
          .sorted()
          .forEach(frames::add);
    }

    return frames;
  }

  // Matroska stores each frame's presentation time itself, so ffprobe reads it unchanged.
  private List<Long> sourceFrames(Path source) throws Exception {
    return workerFixture.worker().packetTimestamps(source).stream()
        .map(HlsVariantSwitchSmokeTest::frameNumber)
        .sorted()
        .toList();
  }

  private static long frameNumber(double seconds) {
    return Math.round(seconds * FRAMES_PER_SECOND);
  }

  // How many video frames FFmpeg decodes from each file read alone.
  private List<Long> decodedFrameCounts(List<Path> media) throws Exception {
    var counts = new ArrayList<Long>();
    for (var file : media) {
      counts.add(workerFixture.worker().decodedVideoFrameCount(file));
    }

    return counts;
  }

  private List<Long> heldFrameCounts(List<Path> media) throws Exception {
    var counts = new ArrayList<Long>();
    for (var file : media) {
      counts.add((long) workerFixture.worker().packetTimestamps(file).size());
    }

    return counts;
  }

  private List<String> videoPackets(List<Path> media) throws Exception {
    var packets = new ArrayList<String>();
    for (var file : media) {
      packets.addAll(hashesOnly(workerFixture.worker().videoPacketHashes(file)));
    }

    return packets;
  }

  private List<String> audioPackets(List<Path> media) throws Exception {
    var packets = new ArrayList<String>();
    for (var file : media) {
      packets.addAll(hashesOnly(workerFixture.worker().audioPacketHashes(file)));
    }

    return packets;
  }

  // ffprobe's CSV ends a packet's line with a separator for each side data entry that it carries.
  private static List<String> hashesOnly(List<String> packetLines) {
    return packetLines.stream().map(line -> line.replace(",", "")).toList();
  }

  private List<Double> audioPacketTimes(List<Path> media) throws Exception {
    var times = new ArrayList<Double>();
    for (var file : media) {
      times.addAll(workerFixture.worker().audioPacketTimestamps(file));
    }

    return times;
  }

  private enum Variant {
    COPIED,
    ENCODED
  }

  private record ListedSegment(double seconds, boolean afterDiscontinuity) {}

  // What lets a client take segment N from either variant. RFC 8216 §6.2.4 requires the same
  // target duration and playlist type in every variant's media playlist, and matching timestamps
  // and discontinuity sequence numbers for matching content. Equal media sequence numbers and
  // segment durations then make segment N hold the same media in both.
  @Builder
  private record Listing(
      int targetDuration,
      Optional<String> playlistType,
      int mediaSequence,
      int discontinuitySequence,
      List<ListedSegment> segments) {}

  // What a client reads from a media playlist (RFC 8216 §4.1 and §4.3).
  @Builder
  private record MediaPlaylist(
      URI uri, String initializationUri, List<String> segmentUris, Listing listing) {

    private static final String TARGET_DURATION = "#EXT-X-TARGETDURATION:";
    private static final String PLAYLIST_TYPE = "#EXT-X-PLAYLIST-TYPE:";
    private static final String MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:";
    private static final String DISCONTINUITY_SEQUENCE = "#EXT-X-DISCONTINUITY-SEQUENCE:";
    private static final String DISCONTINUITY = "#EXT-X-DISCONTINUITY";
    private static final String MAP_URI = "#EXT-X-MAP:URI=\"";
    private static final String EXTINF = "#EXTINF:";

    static MediaPlaylist parse(URI uri, String playlist) {
      var lines = playlist.lines().toList();
      var segments = new ArrayList<ListedSegment>();
      var segmentUris = new ArrayList<String>();
      var afterDiscontinuity = false;
      for (var index = 0; index < lines.size() - 1; index++) {
        var line = lines.get(index);
        if (line.equals(DISCONTINUITY)) {
          afterDiscontinuity = true;
        }

        if (line.startsWith(EXTINF)) {
          var seconds = Double.parseDouble(line.substring(EXTINF.length(), line.indexOf(',')));
          segments.add(new ListedSegment(seconds, afterDiscontinuity));
          segmentUris.add(lines.get(index + 1));
          afterDiscontinuity = false;
        }
      }

      // RFC 8216 §4.3.3.2 and §4.3.3.3: a playlist without the tag has the sequence number 0.
      return MediaPlaylist.builder()
          .uri(uri)
          .initializationUri(valueAfter(lines, MAP_URI).orElseThrow().replace("\"", ""))
          .segmentUris(List.copyOf(segmentUris))
          .listing(
              Listing.builder()
                  .targetDuration(
                      valueAfter(lines, TARGET_DURATION).map(Integer::parseInt).orElseThrow())
                  .playlistType(valueAfter(lines, PLAYLIST_TYPE))
                  .mediaSequence(valueAfter(lines, MEDIA_SEQUENCE).map(Integer::parseInt).orElse(0))
                  .discontinuitySequence(
                      valueAfter(lines, DISCONTINUITY_SEQUENCE).map(Integer::parseInt).orElse(0))
                  .segments(List.copyOf(segments))
                  .build())
          .build();
    }

    private static Optional<String> valueAfter(List<String> lines, String prefix) {
      return lines.stream()
          .filter(line -> line.startsWith(prefix))
          .map(line -> line.substring(prefix.length()))
          .findFirst();
    }

    // RFC 8216 §4.1: a relative URI resolves against the URI of the playlist that contains it.
    URI resolve(String listedUri) {
      return uri.resolve(listedUri);
    }
  }
}
