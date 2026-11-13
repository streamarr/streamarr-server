package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.av1EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
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
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
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
import com.streamarr.server.fixtures.WorkerContainerFixture;
import com.streamarr.server.fixtures.WorkerContainerFixture.ProbedPackets;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Builder;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Plays a stream session that lists an encoded lower variant beside a copy as a client does,
 * against the pinned worker image: the probe task stores the source's probe outcome, the playback
 * session service starts playback in Auto, and the client reads the multivariant playlist, each
 * variant's media playlist and the segments that it plays from the stream endpoints. One worker
 * serves every case and each source is generated, probed and read once; each case starts its own
 * stream session, so every job attempt that a switch starts begins at the segment that the player
 * requests.
 */
@Tag("SmokeTest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("HLS Copied and Encoded Variant Switch Smoke Tests")
class HlsVariantSwitchSmokeTest {

  private static final int SEGMENT_SECONDS = 6;
  private static final int FRAMES_PER_SECOND = 24;
  // ffprobe reads the Matroska source's timestamps in whole milliseconds, and the served MP4's in
  // its track's timescale.
  private static final Offset<Double> MATROSKA_ROUNDING = within(0.001);
  private static final String TOKEN = "smoke-token";
  private static final Duration RESPONSE_BOUND = Duration.ofSeconds(90);
  private static final String VIDEO = "video";
  private static final String AUDIO = "audio";

  private static final Pattern VIDEO_RANGE = Pattern.compile("VIDEO-RANGE=([A-Z]+)");

  // What ffprobe reads of a frame's range and bit depth: its pixel format, its colour description
  // and, for HDR10, its mastering display and content light level.
  private static final List<String> RANGE_SIGNALLING =
      List.of(
          "pix_fmt",
          "color_range",
          "color_space",
          "color_primaries",
          "color_transfer",
          "red_x",
          "red_y",
          "green_x",
          "green_y",
          "blue_x",
          "blue_y",
          "white_point_x",
          "white_point_y",
          "min_luminance",
          "max_luminance",
          "max_content",
          "max_average");

  // 854x480 noise at 24 fps keeps the copy's bitrate above the 360p variant's, so Auto lists that
  // variant beside the copy. Bars across the top of each picture show its frame number, which a
  // 360p encode keeps although it loses the noise. The tone lasts as long as the picture, and its
  // last AAC frame ends after the last picture does.
  private static final List<String> NOISY_480P_PICTURE_AND_TONE =
      List.of(
          "-f",
          "lavfi",
          "-i",
          "testsrc2=size=854x480:duration=23:rate=24,noise=alls=40:allf=t,"
              + WorkerContainerFixture.frameNumberBars(),
          "-f",
          "lavfi",
          "-i",
          "sine=frequency=440:duration=23");

  private static final List<String> AAC_IN_MATROSKA =
      List.of("-c:a", "aac", "-shortest", "-f", "matroska");

  // Keyframes lie only at 0, 10 and 20 s: the 6 s slot [12 s, 18 s) holds none, so it joins the
  // segment before it (ADR 0037, segment list), and the copy's three segments last 10, 10 and 3 s.
  // A player that switches at each boundary crosses a merged segment in both directions and then
  // ends. The stream describes its colours as BT.709, as the worker's 8-bit SDR encode describes
  // its own.
  private static final List<String> H264_AT_3_MBPS_WITH_KEYFRAMES_TEN_SECONDS_APART =
      List.of(
          "-c:v",
          "libx264",
          "-preset",
          "veryfast",
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
          "0,10,20",
          "-x264-params",
          "colorprim=bt709:transfer=bt709:colormatrix=bt709");

  // 10-bit PQ H.265 with a mastering display and a content light level, as an HDR10 source carries
  // them. A closed GOP of 240 frames puts the keyframes at the same 0, 10 and 20 s.
  private static final String HDR10_X265_PARAMETERS =
      "keyint=240:min-keyint=240:scenecut=0:open-gop=0:log-level=error"
          + ":colorprim=bt2020:colormatrix=bt2020nc:transfer=smpte2084"
          + ":master-display=G(8500,39850)B(6550,2300)R(35400,14600)"
          + "WP(15635,16450)L(10000000,50)"
          + ":max-cll=1000,400";

  // libx264's default B-frames give the copy a reorder delay, as the worker's own encode has.
  private static final SwitchedSource SDR_WITH_B_FRAMES =
      sdrSource("sdr-with-b-frames.mkv", List.of());

  // Without B-frames the copy has no reorder delay and the worker's encode has one, so the two
  // variants agree only when each places its frames by presentation time.
  private static final SwitchedSource SDR_WITHOUT_B_FRAMES =
      sdrSource("sdr-without-b-frames.mkv", List.of("-bf", "0"));

  private static final SwitchedSource HDR10_WITH_B_FRAMES =
      hdr10Source("hdr10-with-b-frames.mkv", HDR10_X265_PARAMETERS);

  private static final SwitchedSource HDR10_WITHOUT_B_FRAMES =
      hdr10Source("hdr10-without-b-frames.mkv", HDR10_X265_PARAMETERS + ":bframes=0");

  private static final List<Variant> FROM_THE_COPY =
      List.of(Variant.COPIED, Variant.ENCODED, Variant.COPIED);

  private static final List<Variant> FROM_THE_ENCODED_VARIANT =
      List.of(Variant.ENCODED, Variant.COPIED, Variant.ENCODED);

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeMediaFileContainerInfoRepository probeOutcomes =
      new FakeMediaFileContainerInfoRepository();
  private final AuthenticatedIdentity viewer =
      StreamSessionFixture.identityFor(
          StreamSessionFixture.defaultPlaybackAuthorityBuilder().build());
  private final Map<SwitchedSource, ReadMedia> sources = new HashMap<>();
  private volatile UUID streamSessionId;
  private volatile ProducerLifecycleService producerLifecycle;
  private WorkerStreamingSmokeFixture workerFixture;
  private RemoteTranscodeExecutor transcodeExecutor;
  private LocalSegmentStore segmentStore;
  private HlsStreamingService streamingService;
  private PlaybackSessionService playbackSessions;
  private MockMvc streamEndpoints;
  private Path sourceRoot;
  private Path playedDirectory;

  @BeforeAll
  void startTheWorkerAndProbeEachSource(@TempDir Path classDirectory) throws Exception {
    sourceRoot = Files.createDirectory(classDirectory.resolve("media"));
    playedDirectory = Files.createDirectory(classDirectory.resolve("played"));
    segmentStore = new LocalSegmentStore(Files.createDirectory(classDirectory.resolve("segments")));
    workerFixture =
        WorkerStreamingSmokeFixture.builder()
            .sourceRoot(sourceRoot)
            .segmentStore(segmentStore)
            .eventPublisher(this::onWorkerEvent)
            .build();
    workerFixture.start();
    var workerSessions = workerFixture.workerSessions();
    var namespaceId = workerFixture.sourceNamespaceId();
    transcodeExecutor = new RemoteTranscodeExecutor(workerSessions, namespaceId, sourceRoot);
    var probes = new RemoteFfprobeService(workerSessions, namespaceId, sourceRoot);
    for (var switched :
        List.of(
            SDR_WITH_B_FRAMES, SDR_WITHOUT_B_FRAMES, HDR10_WITH_B_FRAMES, HDR10_WITHOUT_B_FRAMES)) {
      var source =
          workerFixture
              .worker()
              .generateMedia(
                  sourceRoot.resolve(switched.fileName()), switched.generatorArguments());
      storeProbeOutcome(source, probes);
      sources.put(switched, read(source));
    }
  }

  @BeforeEach
  void startTheStreamingService() {
    var workerSessions = workerFixture.workerSessions();
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
                        new RemoteTranscodeProperties(
                            workerFixture.sourceNamespaceId(), sourceRoot.toString()),
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
  void destroyTheStreamSessions() {
    if (streamingService == null) {
      return;
    }

    streamingService
        .getAllSessions()
        .forEach(session -> streamingService.destroySession(session.getSessionId()));
  }

  @AfterAll
  void stopTheWorker() {
    if (workerFixture != null) {
      workerFixture.close();
    }
  }

  // RFC 8216 §6.2.4: so that a client can switch between variant streams, matching content has
  // matching timestamps and discontinuity sequence numbers, and each media playlist has the same
  // target duration. The player changes variant at both boundaries of the merged segment [10 s,
  // 20 s), so the encode that its switch starts at 10 s ends where the copy that its switch starts
  // at 20 s begins, and a copy is never padded at its end.
  @ParameterizedTest(name = "{0}")
  @MethodSource("everySource")
  @DisplayName(
      "Should present every source frame and audio packet once when a player switches from the"
          + " copy to an encoded variant and back at each boundary of a merged segment list")
  void shouldPresentEverySourceFrameAndAudioPacketOnceWhenAPlayerSwitchesAndEndsOnTheCopy(
      SwitchedSource switched) throws Exception {
    var playback = playSwitching(switched, FROM_THE_COPY);
    var sourceFrames = sourceFrames(playback.source());

    assertSwitchedSegmentsPlayAsTheSource(playback);
    assertThat(presentedFrames(playback.played()))
        .as(
            "the video frames that the player presents across its switches at 10 and 20 s: every"
                + " source frame once, and none after the copy's last")
        .containsExactlyElementsOf(sourceFrames);
    assertThat(playback.played())
        .flatExtracting(ReadMedia::frameNumbers)
        .as("the source picture that each of those frames shows: every source picture once")
        .containsExactlyElementsOf(sourceFrames);
    assertEverySourceAudioPacketPlaysOnce(playback);
  }

  static Stream<Named<SwitchedSource>> everySource() {
    return Stream.of(
        Named.of("an SDR H.264 source with B-frames", SDR_WITH_B_FRAMES),
        Named.of("an SDR H.264 source without B-frames", SDR_WITHOUT_B_FRAMES),
        Named.of("an HDR10 H.265 source with B-frames", HDR10_WITH_B_FRAMES),
        Named.of("an HDR10 H.265 source without B-frames", HDR10_WITHOUT_B_FRAMES));
  }

  // ADR 0037, early ends: the encode that the player's last switch starts at 20 s repeats the
  // source's last picture at the output frame rate up to the advertised end.
  @ParameterizedTest(name = "{0}")
  @MethodSource("sourcesWithBFrames")
  @DisplayName(
      "Should present every source frame once and then hold the last picture to the advertised"
          + " end when a player switches from an encoded variant to the copy and back")
  void shouldPresentEverySourceFrameOnceThenHoldTheLastPictureWhenAPlayerEndsOnTheEncodedVariant(
      SwitchedSource switched) throws Exception {
    var playback = playSwitching(switched, FROM_THE_ENCODED_VARIANT);
    var sourceFrames = sourceFrames(playback.source());
    var heldFrames = framesFrom(sourceFrames.size(), playback.advertisedEnd());

    // The source's noise differs in every picture, and a held frame shows the last picture again.
    // The encode is lossy, so the repeated picture still changes a little. Against the pinned
    // worker, a held frame changed by 0.01 where the smallest change between two source pictures of
    // the last segment was 4.7 (8-bit H.264), and by 0.30 where it was 21.6 (10-bit H.265).
    var lastPictureChanges =
        workerFixture.worker().lumaChangeFromPreviousFrame(playback.played().getLast().path());
    var firstHeld = lastPictureChanges.size() - heldFrames.size();
    var smallestSourceChange = Collections.min(lastPictureChanges.subList(1, firstHeld));

    assertSwitchedSegmentsPlayAsTheSource(playback);
    assertThat(heldFrames)
        .as("the source's last picture ends before the advertised end, so an encode holds it")
        .isNotEmpty();
    assertThat(presentedFrames(playback.played()))
        .as(
            "the video frames that the player presents across its switches at 10 and 20 s: every"
                + " source frame once, then the frames that the encoded last segment holds")
        .containsExactlyElementsOf(concat(sourceFrames, heldFrames));
    assertThat(playback.played())
        .flatExtracting(ReadMedia::frameNumbers)
        .as(
            "the source picture that each of those frames shows: every source picture once, then"
                + " the last source picture in each held frame")
        .containsExactlyElementsOf(
            concat(sourceFrames, Collections.nCopies(heldFrames.size(), sourceFrames.getLast())));
    assertThat(lastPictureChanges.subList(firstHeld, lastPictureChanges.size()))
        .as(
            "each held frame repeats the picture before it, where the smallest change between two"
                + " source pictures of that segment is %s",
            smallestSourceChange)
        .allSatisfy(change -> assertThat(change).isLessThan(smallestSourceChange / 4));
    assertEverySourceAudioPacketPlaysOnce(playback);
  }

  // A source without B-frames plays only from the copy. Its copy has no reorder delay to misplace,
  // and the encode that the player starting on the copy plays meets a copy without one at both of
  // its boundaries. The encode that opens this play and the end that it holds carry the encode's
  // own reorder delay, which the source's B-frames do not change.
  static Stream<Named<SwitchedSource>> sourcesWithBFrames() {
    return Stream.of(
        Named.of("an SDR H.264 source with B-frames", SDR_WITH_B_FRAMES),
        Named.of("an HDR10 H.265 source with B-frames", HDR10_WITH_B_FRAMES));
  }

  // What every switching player sees before its frames, whichever variant it ends on. The copy and
  // the encode stay in the copy's codec family (ADR 0050), and each played segment opens on a
  // keyframe and signals the source's range and bit depth. Each source picture shows its own frame
  // number, so the numbers that the played pictures show name the source pictures. The audio is
  // checked after the frames, whose failure names the frame numbers that a switch lost or
  // repeated.
  private static void assertSwitchedSegmentsPlayAsTheSource(SwitchingPlayback playback) {
    var source = playback.source();
    var copied = playback.playlists().get(Variant.COPIED);
    var encoded = playback.playlists().get(Variant.ENCODED);
    var played = playback.played();

    assertThat(source.frameNumbers())
        .as("the frame number that each source picture shows, in presentation order")
        .containsExactlyElementsOf(sourceFrames(source));
    assertThat(
            playback
                .session()
                .decisionOf(StreamSession.defaultVariant())
                .transcodeMode()
                .copiesVideo())
        .as("the first-listed variant copies the video")
        .isTrue();
    assertThat(playback.multivariant().variantUris())
        .as("the multivariant playlist lists the copy first and one encoded variant below it")
        .containsExactly("stream.m3u8?t=" + TOKEN, "360p/stream.m3u8?t=" + TOKEN);
    assertThat(playback.multivariant().videoRanges())
        .as("both variants announce the source's range, so a player treats them as one ladder")
        .containsExactly(playback.switched().videoRange(), playback.switched().videoRange());
    assertThat(playback.session().decisionOf("360p").transcodeMode().copiesVideo())
        .as("the 360p variant encodes the video")
        .isFalse();
    assertThat(copied.listing().segments())
        .extracting(ListedSegment::seconds)
        .as("the copy's segments open at the keyframes 10 s apart")
        .startsWith(10.0, 10.0)
        .hasSameSizeAs(played);
    assertThat(encoded.listing())
        .as("the encoded variant's playlist lists the copy's segments")
        .isEqualTo(copied.listing());
    assertThat(playback.playedFrom(Variant.COPIED))
        .flatExtracting(ReadMedia::pictures)
        .as("the segments played from the copy decode to the source's own pictures")
        .isSubsetOf(source.pictures());
    assertThat(playback.playedFrom(Variant.ENCODED))
        .flatExtracting(ReadMedia::pictures)
        .as("the segments played from the 360p variant decode to none of the source's pictures")
        .doesNotContainAnyElementsOf(source.pictures());
    assertThat(played)
        .extracting(ReadMedia::videoCodecs)
        .as("each played segment is in the copy's codec family")
        .containsOnly(source.videoCodecs());
    assertThat(played)
        .extracting(ReadMedia::rangeSignalling)
        .as("each played segment signals the source's range and bit depth")
        .containsOnly(source.rangeSignalling());
    assertThat(played)
        .extracting(ReadMedia::decodedFrameCount)
        .as("each played segment decodes every frame it holds alone, so it opens on a keyframe")
        .containsExactlyElementsOf(played.stream().map(ReadMedia::heldFrameCount).toList());
  }

  private static void assertEverySourceAudioPacketPlaysOnce(SwitchingPlayback playback) {
    var source = playback.source();
    var played = playback.played();

    assertThat(played)
        .flatExtracting(file -> file.packets().dataHashesOf(AUDIO))
        .as("the audio packets that the player plays across its switches at 10 and 20 s")
        .containsExactlyElementsOf(source.packets().dataHashesOf(AUDIO));
    assertThat(
            played.stream()
                .flatMap(file -> file.packets().presentationTimesOf(AUDIO).stream())
                .toList())
        .as("when each of those audio packets presents")
        .zipSatisfy(
            source.packets().presentationTimesOf(AUDIO),
            (served, inSource) -> assertThat(served).isCloseTo(inSource, MATROSKA_ROUNDING));
  }

  // Plays segment N of the variant that the player takes for it, then reads every played segment.
  private SwitchingPlayback playSwitching(
      SwitchedSource switched, List<Variant> variantOfEachSegment) throws Exception {
    var source = sources.get(switched);
    var session = startAutoPlayback(source.path(), switched.declaration());
    var multivariant = multivariantPlaylist();
    var playlists =
        Map.of(
            Variant.COPIED,
            mediaPlaylist(multivariant.variantUris().getFirst()),
            Variant.ENCODED,
            mediaPlaylist(multivariant.variantUris().getLast()));
    var playedFiles = new ArrayList<Path>();
    for (var index = 0; index < variantOfEachSegment.size(); index++) {
      playedFiles.add(playable(playlists.get(variantOfEachSegment.get(index)), index));
    }

    var played = new ArrayList<ReadMedia>();
    for (var file : playedFiles) {
      played.add(read(file));
    }

    return SwitchingPlayback.builder()
        .switched(switched)
        .source(source)
        .session(session)
        .multivariant(multivariant)
        .playlists(playlists)
        .variantOfEachSegment(variantOfEachSegment)
        .played(List.copyOf(played))
        .build();
  }

  // An H.264 player in 8-bit SDR: Auto copies the source and encodes the 360p variant in H.264.
  private static SwitchedSource sdrSource(String fileName, List<String> encoderArguments) {
    var arguments = new ArrayList<>(NOISY_480P_PICTURE_AND_TONE);
    arguments.addAll(H264_AT_3_MBPS_WITH_KEYFRAMES_TEN_SECONDS_APART);
    arguments.addAll(encoderArguments);
    arguments.addAll(AAC_IN_MATROSKA);
    return SwitchedSource.builder()
        .fileName(fileName)
        .generatorArguments(List.copyOf(arguments))
        .declaration(defaultDeclarationBuilder().build())
        .videoRange("SDR")
        .build();
  }

  // A player of H.264 in 8-bit SDR and of H.265 and AV1 up to 10 bits in SDR, PQ and HLG: Auto
  // copies the source and encodes the 360p variant in the copy's range and bit depth. The codec
  // preference puts AV1 before H.265, so only the copy's codec family makes that variant H.265.
  private static SwitchedSource hdr10Source(String fileName, String x265Parameters) {
    var arguments = new ArrayList<>(NOISY_480P_PICTURE_AND_TONE);
    arguments.addAll(
        List.of(
            "-c:v",
            "libx265",
            "-preset",
            "ultrafast",
            "-pix_fmt",
            "yuv420p10le",
            "-b:v",
            "3M",
            "-x265-params",
            x265Parameters,
            "-tag:v",
            "hvc1"));
    arguments.addAll(AAC_IN_MATROSKA);
    var everyRange = List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG);
    return SwitchedSource.builder()
        .fileName(fileName)
        .generatorArguments(List.copyOf(arguments))
        .declaration(
            defaultDeclarationBuilder()
                .video(
                    List.of(
                        h264EntryBuilder().build(),
                        hevcEntryBuilder().compatibleBases(everyRange).build(),
                        av1EntryBuilder().compatibleBases(everyRange).build()))
                .build())
        .videoRange("PQ")
        .build();
  }

  // Runs the probe task of the source with the worker, as a library scan requests it.
  private void storeProbeOutcome(Path source, RemoteFfprobeService probes) throws Exception {
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
  private StreamSession startAutoPlayback(Path source, ClientCapabilityDeclaration declaration) {
    var file = mediaFiles.findFirstByFilepathUri(FilepathCodec.encode(source)).orElseThrow();
    var playback =
        PlaybackSessionFixture.acceptedPlayback(
            playbackSessions.start(
                PlaybackSessionFixture.startPlaybackBuilder()
                    .mediaFileId(file.getId())
                    .identity(viewer)
                    .capabilityDeclaration(declaration)
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

  private MultivariantPlaylist multivariantPlaylist() throws Exception {
    var response = responseTo(streamUri("multivariant.m3u8?t=" + TOKEN));

    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    return MultivariantPlaylist.parse(response.getContentAsString());
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
        Files.createTempFile(playedDirectory, "served", ".mp4"), media.toByteArray());
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

  // What a player decodes from one file, read once with the worker image's FFmpeg: the hash of
  // each picture and the frame number that it shows, the file's codecs and packets, and how its
  // first frame signals its range and bit depth. A copy can repackage a keyframe's sample, as the
  // worker's Annex B filter does for H.265 from Matroska, so the pictures are compared and not the
  // video packets. Decoding is exact, and the noise makes every picture differ.
  private ReadMedia read(Path media) throws Exception {
    var worker = workerFixture.worker();
    var firstFrame = worker.firstVideoFrame(media);
    return ReadMedia.builder()
        .path(media)
        .pictures(worker.decodedVideoFrameHashes(media))
        .frameNumbers(worker.decodedFrameNumbers(media))
        .packets(worker.probedPackets(media))
        .rangeSignalling(
            RANGE_SIGNALLING.stream()
                .filter(firstFrame::containsKey)
                .collect(Collectors.toMap(field -> field, firstFrame::get)))
        .build();
  }

  // The number of each video frame that the files present, on the 24 fps timeline and in the
  // files' order. Times count in whole frames: a switch may repeat or skip less than one output
  // frame (ADR 0050). ffprobe reports a fragmented MP4's timestamps later by its most negative
  // composition offset, the video's reorder delay, which can differ between the copy and the
  // encode. So the first sample's presentation time comes from the boxes: tfdt plus composition
  // offset (ISO/IEC 14496-12 8.8.12 and 8.8.8), less the media time of the initialization
  // segment's edit (8.6.6), which a player applies. ffprobe gives every other frame's distance from
  // that sample. A file's packets come in decode order, so each file's frames are sorted.
  private static List<Long> presentedFrames(List<ReadMedia> media) throws Exception {
    var frames = new ArrayList<Long>();
    for (var file : media) {
      var bytes = Files.readAllBytes(file.path());
      var firstSample =
          Fmp4Fixture.firstVideoPresentationTime(bytes) - Fmp4Fixture.videoEditMediaTime(bytes);
      var probed = file.packets().presentationTimesOf(VIDEO);
      probed.stream()
          .map(seconds -> frameNumber(firstSample + seconds - probed.getFirst()))
          .sorted()
          .forEach(frames::add);
    }

    return frames;
  }

  // Matroska stores each frame's presentation time itself, so ffprobe reads it unchanged.
  private static List<Long> sourceFrames(ReadMedia source) {
    return source.packets().presentationTimesOf(VIDEO).stream()
        .map(HlsVariantSwitchSmokeTest::frameNumber)
        .sorted()
        .toList();
  }

  private static long frameNumber(double seconds) {
    return Math.round(seconds * FRAMES_PER_SECOND);
  }

  // ADR 0037, early ends: an encode repeats its last picture at the output frame rate up to the
  // advertised end. No held frame starts at or after that end, and the last one keeps its full
  // duration, so it can end less than one frame after it. A copy is never padded.
  private static List<Long> framesFrom(long firstFrame, double advertisedEnd) {
    return Stream.iterate(firstFrame, frame -> frame + 1)
        .takeWhile(frame -> (double) frame / FRAMES_PER_SECOND < advertisedEnd)
        .toList();
  }

  private static List<Long> concat(List<Long> first, List<Long> second) {
    return Stream.concat(first.stream(), second.stream()).toList();
  }

  private enum Variant {
    COPIED,
    ENCODED
  }

  @Builder
  private record ReadMedia(
      Path path,
      List<String> pictures,
      List<Long> frameNumbers,
      ProbedPackets packets,
      Map<String, String> rangeSignalling) {

    List<String> videoCodecs() {
      return packets.codecsOf(VIDEO);
    }

    long decodedFrameCount() {
      return pictures.size();
    }

    long heldFrameCount() {
      return packets.presentationTimesOf(VIDEO).size();
    }
  }

  // One player's playback of a source: the playlists that it read and each segment that it played,
  // read as it decodes them.
  @Builder
  private record SwitchingPlayback(
      SwitchedSource switched,
      ReadMedia source,
      StreamSession session,
      MultivariantPlaylist multivariant,
      Map<Variant, MediaPlaylist> playlists,
      List<Variant> variantOfEachSegment,
      List<ReadMedia> played) {

    List<ReadMedia> playedFrom(Variant variant) {
      return IntStream.range(0, played.size())
          .filter(index -> variantOfEachSegment.get(index) == variant)
          .mapToObj(played::get)
          .toList();
    }

    double advertisedEnd() {
      return playlists.get(Variant.COPIED).listing().end();
    }
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
      List<ListedSegment> segments) {

    // The advertised end, where the last listed segment ends.
    double end() {
      return segments.stream().mapToDouble(ListedSegment::seconds).sum();
    }
  }

  // A source, the player's declaration, and the range that every variant then announces.
  @Builder
  private record SwitchedSource(
      String fileName,
      List<String> generatorArguments,
      ClientCapabilityDeclaration declaration,
      String videoRange) {}

  // The URI and the VIDEO-RANGE of each variant, in the multivariant playlist's order. A variant
  // without the attribute is SDR (draft-pantos-hls-rfc8216bis §4.4.6.2).
  private record MultivariantPlaylist(List<String> variantUris, List<String> videoRanges) {

    static MultivariantPlaylist parse(String playlist) {
      var variantUris = playlist.lines().filter(line -> !line.startsWith("#")).toList();
      var videoRanges =
          playlist
              .lines()
              .filter(line -> line.startsWith("#EXT-X-STREAM-INF:"))
              .map(
                  line ->
                      VIDEO_RANGE.matcher(line).results().map(found -> found.group(1)).findFirst())
              .map(range -> range.orElse("SDR"))
              .toList();
      return new MultivariantPlaylist(variantUris, videoRanges);
    }
  }

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
