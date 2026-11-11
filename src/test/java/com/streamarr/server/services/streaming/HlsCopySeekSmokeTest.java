package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.declarationBuilderFor;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static com.streamarr.server.fixtures.PlaybackLanguagePreferencesFixture.noLanguagePreferences;
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.SeekPriorityFixture.seekPriorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommandBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import com.streamarr.server.domain.streaming.EncodeFormat;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.LowerVariantEncode;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.VideoRange;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.fixtures.ServerSettingsFixture;
import com.streamarr.server.fixtures.UploadBudgetFixture;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.streaming.events.JobAttemptProgressedEvent;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRegistry;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import com.streamarr.server.support.OutcomeTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real-FFmpeg proof that a stream copy seeks to the keyframe that opens the requested segment. A
 * replacement attempt must deliver the same video samples as the attempt that copied the source
 * from its start, also when its segment merges slots without a keyframe (ADR 0037). A copy whose
 * audio runs on long after its video keeps its producer while it copies that audio. An encoded
 * lower variant beside a copy starts only when the player requests one of its segments, and it
 * keeps the copy's range and bit depth.
 */
@Tag("SmokeTest")
@DisplayName("HLS Stream Copy Seek Smoke Tests")
class HlsCopySeekSmokeTest {

  private static final int SEGMENT_SECONDS = 6;
  private static final int SEEK_SEGMENT = 2;
  private static final String SEEK_SEGMENT_NAME = SegmentNames.mediaSegmentName(SEEK_SEGMENT);

  // 24 fps with keyframes forced off the 6 s grid: each 6 s interval holds one keyframe, and the
  // copy segments last 7.5, 5.5, 7.5, 5.5, 5 and 5 s.
  private static final List<String> IRREGULAR_MATROSKA =
      source(
          "rate=24",
          List.of("-force_key_frames", "0,7.5,13,20.5,26,31"),
          List.of("-f", "matroska"));

  // 4 fps with two B-frames: each keyframe decodes 0.5 s before it presents. The keyframe that
  // opens segment 2 presents at 12 s, on the boundary, and decodes at 11.5 s. A seek to 12 s
  // reads past it. An MPEG-TS source would copy only once the probe stores its start time
  // correction (ADR 0037, segment list), so the stream goes into Matroska, whose demuxer also
  // seeks by decode time.
  private static final List<String> BOUNDARY_B_FRAMES =
      source(
          "rate=4",
          List.of("-bf", "2", "-force_key_frames", "0,6,12,19,25,30"),
          List.of("-f", "matroska"));

  // 24 fps with a keyframe every 10 s, as a long-GOP encode places them: the 6 s slots [12 s, 18 s)
  // and [24 s, 30 s) hold no keyframe, so the segment list starts its segments in slots 0, 1, 3
  // and 5, and the AAC tail past 36 s joins the last segment.
  private static final List<String> LONG_GOP_MATROSKA =
      source("rate=24", List.of("-force_key_frames", "0,10,20,30"), List.of("-f", "matroska"));

  // 10 s of a still picture with a keyframe every 2 s, and 70 s of stereo AAC at 256 kb/s: the 6 s
  // slots from 12 s on hold no keyframe, so segment 1 runs from 6 s to the media end, and the 60 s
  // of audio after the video's end join it. The muxer interleaves strictly by time: by default it
  // writes the encoder's last video packets up to 10 s of audio later.
  private static final List<String> VIDEO_ENDS_LONG_BEFORE_AUDIO_MATROSKA =
      List.of(
          "-f",
          "lavfi",
          "-i",
          "color=c=gray:size=160x90:rate=24:duration=10",
          "-f",
          "lavfi",
          "-i",
          "anoisesrc=color=pink:sample_rate=48000:amplitude=0.5:duration=70",
          "-c:v",
          "libx264",
          "-pix_fmt",
          "yuv420p",
          "-g",
          "48",
          "-keyint_min",
          "48",
          "-sc_threshold",
          "0",
          "-c:a",
          "aac",
          "-ac",
          "2",
          "-b:a",
          "256k",
          "-max_interleave_delta",
          "0",
          "-f",
          "matroska");

  // A job attempt's FFmpeg reads a source named paced-* through a named pipe that a writer fills
  // at 96 KiB a second, about three times the real-time rate of the source above, as a slow mount
  // would serve it.
  private static final String PACED_SOURCE_READS =
      """
      if [[ $argument == /media/paced-* ]]; then
        pipe=/tmp/paced-source-$attempt
        mkfifo "$pipe"
        size=$(stat -c %s "$argument")
        (
          exec > "$pipe"
          for ((block = 0; block * 24576 < size; block++)); do
            dd if="$argument" bs=24576 skip=$block count=1 status=none
            sleep 0.25
          done
        ) &
        paced=()
        for value in "$@"; do
          if [[ $value == "$argument" ]]; then
            value=$pipe
          fi
          paced+=("$value")
        done
        exec ffmpeg "${paced[@]}"
      fi
      """;

  // 854x480 noise at 24 fps with a keyframe every 2 s keeps the copy's bitrate above the 360p
  // variant's, so Auto lists that variant beside the copy.
  private static final List<String> NOISY_480P =
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
          "-crf",
          "18",
          "-g",
          "48",
          "-keyint_min",
          "48",
          "-sc_threshold",
          "0",
          "-c:a",
          "aac",
          "-shortest",
          "-f",
          "matroska");

  // The same picture as 10-bit PQ H.265 with a mastering display and a content light level, as an
  // HDR10 source carries them.
  private static final List<String> NOISY_480P_HDR10 =
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
          "libx265",
          "-preset",
          "ultrafast",
          "-pix_fmt",
          "yuv420p10le",
          "-crf",
          "18",
          "-x265-params",
          "keyint=48:min-keyint=48:scenecut=0:bframes=0:log-level=error"
              + ":colorprim=bt2020:colormatrix=bt2020nc:transfer=smpte2084"
              + ":master-display=G(8500,39850)B(6550,2300)R(35400,14600)"
              + "WP(15635,16450)L(10000000,50)"
              + ":max-cll=1000,400",
          "-tag:v",
          "hvc1",
          "-c:a",
          "aac",
          "-shortest",
          "-f",
          "mp4");

  private FakeMediaFileRepository mediaFileRepository;
  private FakeMediaFileContainerInfoRepository probeRepository;
  private LocalSegmentStore segmentStore;
  private HlsStreamingService streamingService;
  private HlsPlaylistService playlistService;
  private SegmentDeliveryCoordinator coordinator;
  private RemoteFfprobeService probes;
  private RemoteTranscodeExecutor transcodeExecutor;
  private WorkerStreamingSmokeFixture workerFixture;
  private volatile JobAttemptProgressListener progressListener;
  private Path sourceRoot;
  @TempDir private Path temporaryDirectory;

  @BeforeEach
  void setUp() throws Exception {
    sourceRoot = Files.createDirectory(temporaryDirectory.resolve("media"));
    segmentStore =
        new LocalSegmentStore(Files.createDirectory(temporaryDirectory.resolve("segments")));
    workerFixture =
        WorkerStreamingSmokeFixture.builder()
            .sourceRoot(sourceRoot)
            .segmentStore(segmentStore)
            .ffmpegScript(PACED_SOURCE_READS)
            .eventPublisher(this::restartStallClockOnProgress)
            .build();
    workerFixture.start();
    probes =
        new RemoteFfprobeService(
            workerFixture.workerSessions(), workerFixture.sourceNamespaceId(), sourceRoot);
    transcodeExecutor =
        new RemoteTranscodeExecutor(
            workerFixture.workerSessions(), workerFixture.sourceNamespaceId(), sourceRoot);
    var properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(3)
            .targetSegmentDuration(Duration.ofSeconds(SEGMENT_SECONDS))
            .sessionTimeout(Duration.ofSeconds(60))
            .producerStallThreshold(Duration.ofSeconds(10))
            .build();
    var sessionRegistry = new InMemoryStreamSessionRegistry();
    var producerLifecycle =
        ProducerLifecycleService.builder()
            .transcodeExecutor(transcodeExecutor)
            .eventPublisher(new CapturingEventPublisher())
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(sessionRegistry)
            .sessionMutex(new MutexFactory<>())
            .serverSettings(ServerSettingsFixture.defaultServerSettings())
            .seekPriority(
                seekPriorityBuilder()
                    .transcodeExecutor(transcodeExecutor)
                    .segmentStore(segmentStore)
                    .runtimeRegistry(sessionRegistry)
                    .properties(properties)
                    .build())
            .build();
    progressListener = new JobAttemptProgressListener(producerLifecycle);
    coordinator =
        SegmentDeliveryCoordinator.builder()
            .segmentStore(segmentStore)
            .producerLifecycle(producerLifecycle)
            .heldRequests(workerFixture.workerSessions().heldSegmentRequests())
            .eventPublisher(new CapturingEventPublisher())
            .build();
    mediaFileRepository = new FakeMediaFileRepository();
    probeRepository = new FakeMediaFileContainerInfoRepository();
    streamingService =
        HlsStreamingService.builder()
            .languagePreferences(noLanguagePreferences())
            .uploadBudget(UploadBudgetFixture.OFF)
            .meterRegistry(new SimpleMeterRegistry())
            .mediaFileRepository(mediaFileRepository)
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .playbackProbeService(playbackProbeServiceBuilder(probeRepository).build())
            .transcodeDecisionService(
                new TranscodeDecisionService(properties, new SimpleMeterRegistry()))
            .qualityLadderService(new QualityLadderService())
            .properties(properties)
            .authorityGate((_, _) -> true)
            .runtimeRegistry(sessionRegistry)
            .producerLifecycle(producerLifecycle)
            .deliveryCoordinator(coordinator)
            .build();
    playlistService = new HlsPlaylistService(properties, UploadBudgetFixture.OFF);
  }

  // The worker's progress reports, and the segment data it uploads, restart the job attempt's stall
  // clock, as the application's listener does (ADR 0037).
  private void restartStallClockOnProgress(Object event) {
    if (event instanceof JobAttemptProgressedEvent progressed && progressListener != null) {
      progressListener.onJobAttemptProgressed(progressed);
    }
  }

  @AfterEach
  void tearDown() {
    try {
      if (streamingService != null) {
        streamingService
            .getAllSessions()
            .forEach(session -> streamingService.destroySession(session.getSessionId()));
      }
    } finally {
      try {
        if (workerFixture != null) {
          workerFixture.close();
        }
      } finally {
        if (segmentStore != null) {
          segmentStore.shutdown();
        }
      }
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("irregularKeyframeSources")
  @DisplayName(
      "Should deliver the requested segment from its stored keyframe when a stream copy seeks")
  void shouldDeliverTheRequestedSegmentFromItsStoredKeyframeWhenAStreamCopySeeks(
      String fileName,
      List<String> mediaArguments,
      KeyframePositionSource keyframeSource,
      List<Double> copySegmentSeconds)
      throws Exception {
    var source = workerFixture.worker().generateMedia(sourceRoot.resolve(fileName), mediaArguments);
    var positions = storeProbeWithKeyframes(source);
    var session = createSession(source);

    assertThat(positions.source()).isEqualTo(keyframeSource);
    var sessionId = session.getSessionId();

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(gridSlotCount(session))
        .as("the AAC tail reaches a grid slot past the last keyframe")
        .isEqualTo(copySegmentSeconds.size() + 1);
    assertThat(
            extinfSeconds(
                playlistService.generateMediaPlaylist(
                    session, StreamSession.defaultVariant(), "smoke-token")))
        .zipSatisfy(
            copySegmentSeconds,
            (advertised, expected) -> assertThat(advertised).isCloseTo(expected, within(0.1)));

    var lastSegment = SegmentNames.mediaSegmentName(copySegmentSeconds.size() - 1);
    await()
        .atMost(Duration.ofSeconds(60))
        .until(() -> segmentStore.segmentExists(sessionId, lastSegment));
    var fromStart = videoPresentationTimes(sessionId);

    killProducerAndDropSegmentsFrom(session, SEEK_SEGMENT, copySegmentSeconds.size());
    var delivery =
        coordinator.deliver(sessionId, StreamSession.defaultVariant(), SEEK_SEGMENT_NAME);

    assertThat(delivery).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(videoPresentationTimes(sessionId))
        .as("the replacement's segment 2 holds the same video as the copy from the start")
        .isEqualTo(fromStart);
  }

  @Test
  @DisplayName(
      "Should start a lower variant's encode at its segment's start when the player first requests"
          + " the segment beside a running copy")
  void shouldStartALowerVariantsEncodeAtItsSegmentsStartWhenThePlayerFirstRequestsItBesideACopy()
      throws Exception {
    var source =
        workerFixture.worker().generateMedia(sourceRoot.resolve("noisy-480p.mkv"), NOISY_480P);
    storeProbeWithKeyframes(source);
    var session = createSession(source);
    var sessionId = session.getSessionId();
    var lowerSegment = "360p/" + SegmentNames.mediaSegmentName(1);

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(session.getVariants()).extracting(QualityVariant::label).contains("360p");
    await()
        .atMost(Duration.ofSeconds(60))
        .until(() -> segmentStore.segmentExists(sessionId, SegmentNames.mediaSegmentName(0)));
    assertThat(transcodeExecutor.isRunning(sessionId, StreamSession.defaultVariant())).isTrue();
    assertThat(transcodeExecutor.isRunning(sessionId, "360p")).isFalse();

    var delivery = coordinator.deliver(sessionId, "360p", lowerSegment);

    assertThat(delivery).isInstanceOf(SegmentDelivery.Ready.class);
    var initialization = segmentStore.readSegment(sessionId, "360p/init.mp4");
    assertThat(Fmp4Fixture.avcCodecString(initialization)).isEqualTo("avc1.64001E");
    var media = new ByteArrayOutputStream();
    media.writeBytes(initialization);
    media.writeBytes(segmentStore.readSegment(sessionId, lowerSegment));
    assertThat(Fmp4Fixture.firstVideoPresentationTime(media.toByteArray()))
        .as("the encode forces a keyframe at the start of the copy's segment 1")
        .isCloseTo(SEGMENT_SECONDS, within(0.05));
  }

  @Test
  @DisplayName(
      "Should encode a lower variant in 10-bit PQ H.265 with the source's static HDR metadata when"
          + " the player requests it beside an HDR10 copy")
  void shouldEncodeALowerVariantInTenBitPqH265WhenThePlayerRequestsItBesideAnHdr10Copy()
      throws Exception {
    var source =
        workerFixture
            .worker()
            .generateMedia(sourceRoot.resolve("noisy-480p-hdr10.mp4"), NOISY_480P_HDR10);
    storeProbeWithKeyframes(source);
    var session = createSession(source, h264AndHdrH265Declaration());
    var sessionId = session.getSessionId();
    var lowerSegment = "360p/" + SegmentNames.mediaSegmentName(1);

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(session.getTranscodeDecision().lowerVariantEncode())
        .contains(
            new LowerVariantEncode(
                EncodeCodecProfile.HEVC_MAIN_10, EncodeFormat.hdr(VideoRange.PQ)));
    assertThat(session.getVariants()).extracting(QualityVariant::label).contains("360p");
    await()
        .atMost(Duration.ofSeconds(60))
        .until(() -> segmentStore.segmentExists(sessionId, SegmentNames.mediaSegmentName(0)));

    var delivery = coordinator.deliver(sessionId, "360p", lowerSegment);

    assertThat(delivery).isInstanceOf(SegmentDelivery.Ready.class);
    var initialization = segmentStore.readSegment(sessionId, "360p/init.mp4");
    var producedCodecString = Fmp4Fixture.hevcCodecString(initialization);
    assertThat(producedCodecString).startsWith("hvc1.2.4.");
    assertThat(streamInfLines(session))
        .as("every variant announces PQ, and the 360p variant the codec string it delivers")
        .allSatisfy(line -> assertThat(line).contains("VIDEO-RANGE=PQ"))
        .anySatisfy(line -> assertThat(line).contains("CODECS=\"" + producedCodecString + ","));
    var media = new ByteArrayOutputStream();
    media.writeBytes(initialization);
    media.writeBytes(segmentStore.readSegment(sessionId, lowerSegment));
    assertThat(Fmp4Fixture.firstVideoPresentationTime(media.toByteArray()))
        .as("the encode forces a keyframe at the start of the copy's segment 1")
        .isCloseTo(SEGMENT_SECONDS, within(0.05));
    var decodable =
        Files.write(
            Files.createTempFile(temporaryDirectory, "lower-variant", ".mp4"), media.toByteArray());
    assertThat(workerFixture.worker().firstVideoFrame(decodable))
        .containsEntry("pix_fmt", "yuv420p10le")
        .containsEntry("color_primaries", "bt2020")
        .containsEntry("color_transfer", "smpte2084")
        .containsEntry("max_luminance", "10000000/10000")
        .containsEntry("max_content", "1000")
        .containsEntry("max_average", "400");
  }

  static Stream<Arguments> irregularKeyframeSources() {
    return Stream.of(
        Arguments.of(
            "irregular-keyframes.mkv",
            IRREGULAR_MATROSKA,
            KeyframePositionSource.CONTAINER_INDEX,
            List.of(7.5, 5.5, 7.5, 5.5, 5.0, 5.0)),
        Arguments.of(
            "boundary-keyframes.mkv",
            BOUNDARY_B_FRAMES,
            KeyframePositionSource.CONTAINER_INDEX,
            List.of(6.0, 6.0, 7.0, 6.0, 5.0, 6.0)));
  }

  @ParameterizedTest(name = "seek to segment {0}")
  @ValueSource(ints = {1, 3})
  @DisplayName(
      "Should copy a source whose keyframes lie 10 s apart on a merged segment list and seek into"
          + " and past a merged segment")
  void shouldCopyASourceWhoseKeyframesLieTenSecondsApartOnAMergedSegmentListAndSeekIntoAndPastIt(
      int seekSegment) throws Exception {
    var source =
        workerFixture.worker().generateMedia(sourceRoot.resolve("long-gop.mkv"), LONG_GOP_MATROSKA);
    storeProbeWithKeyframes(source);
    var session = createSession(source);
    var sessionId = session.getSessionId();

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(
            extinfSeconds(
                playlistService.generateMediaPlaylist(
                    session, StreamSession.defaultVariant(), "smoke-token")))
        .zipSatisfy(
            List.of(10.0, 10.0, 10.0, 6.0),
            (advertised, expected) -> assertThat(advertised).isCloseTo(expected, within(0.1)));
    await()
        .atMost(Duration.ofSeconds(60))
        .until(() -> segmentStore.segmentExists(sessionId, SegmentNames.mediaSegmentName(3)));
    var firstPresentations = new ArrayList<Double>();
    for (var index = 0; index < 4; index++) {
      var name = SegmentNames.mediaSegmentName(index);
      assertThat(coordinator.deliver(sessionId, StreamSession.defaultVariant(), name))
          .isInstanceOf(SegmentDelivery.Ready.class);
      firstPresentations.add(
          videoPresentationTimes(sessionId, name).stream().min(Double::compare).orElseThrow());
    }

    assertThat(firstPresentations)
        .as("each segment opens at the keyframe that its listed slot holds")
        .zipSatisfy(
            List.of(0.0, 10.0, 20.0, 30.0),
            (opening, expected) -> assertThat(opening).isCloseTo(expected, within(0.1)));
    var seekSegmentName = SegmentNames.mediaSegmentName(seekSegment);
    var fromStart = videoPresentationTimes(sessionId, seekSegmentName);

    killProducerAndDropSegmentsFrom(session, seekSegment, 4);
    var delivery = coordinator.deliver(sessionId, StreamSession.defaultVariant(), seekSegmentName);

    assertThat(delivery).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(videoPresentationTimes(sessionId, seekSegmentName))
        .as("the replacement's segment holds the same video as the copy from the start")
        .isEqualTo(fromStart);
  }

  // The worker reads the source at about three times real time, so copying the 60 s of audio after
  // the video's end takes about 20 s, twice the 10 s stall threshold, while segment 1 stays open.
  @Test
  @DisplayName(
      "Should deliver every segment from the first job attempt when a copy's audio runs on long"
          + " after its video ends")
  void shouldDeliverEverySegmentFromTheFirstJobAttemptWhenACopysAudioRunsOnLongAfterItsVideoEnds()
      throws Exception {
    var source =
        workerFixture
            .worker()
            .generateMedia(
                sourceRoot.resolve("paced-audio-tail.mkv"), VIDEO_ENDS_LONG_BEFORE_AUDIO_MATROSKA);
    storeProbeWithoutStreamEndTimes(probeWithKeyframes(source));
    var session = createSession(source);
    var sessionId = session.getSessionId();
    var firstAttempt = session.getHandle().orElseThrow().attemptId();

    assertThat(session.getTranscodeDecision().transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(
            extinfSeconds(
                playlistService.generateMediaPlaylist(
                    session, StreamSession.defaultVariant(), "smoke-token")))
        .zipSatisfy(
            List.of(6.0, 64.0),
            (advertised, expected) -> assertThat(advertised).isCloseTo(expected, within(0.1)));
    var lastSegment = SegmentNames.mediaSegmentName(1);
    var delivery =
        CompletableFuture.supplyAsync(
            () -> coordinator.deliver(sessionId, StreamSession.defaultVariant(), lastSegment));

    assertThat(delivery)
        .succeedsWithin(Duration.ofSeconds(90))
        .isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(session.getHandle().orElseThrow().attemptId())
        .as("no stall replaced the producer while it copied the audio after the video's end")
        .isEqualTo(firstAttempt);
    assertThat(audioPacketHashes(sessionId, List.of(SegmentNames.mediaSegmentName(0), lastSegment)))
        .as("the two segments carry every source audio packet once, in order")
        .isEqualTo(hashesOnly(workerFixture.worker().audioPacketHashes(source)));
  }

  private List<String> audioPacketHashes(UUID sessionId, List<String> segmentNames)
      throws Exception {
    var hashes = new ArrayList<String>();
    for (var segmentName : segmentNames) {
      var media =
          Files.write(
              Files.createTempFile(temporaryDirectory, "segment", ".mp4"),
              Fmp4Fixture.withInitializationSegment(segmentStore, sessionId, segmentName));
      hashes.addAll(hashesOnly(workerFixture.worker().audioPacketHashes(media)));
    }

    return hashes;
  }

  // ffprobe's CSV ends a packet's line with a separator for each side data entry it carries.
  private static List<String> hashesOnly(List<String> packetLines) {
    return packetLines.stream().map(line -> line.replace(",", "")).toList();
  }

  // The AAC encoder's priming samples make each container about 23 ms longer than its 36 s video.
  // That tail forms a seventh grid interval without a keyframe, which joins the last copy segment.
  private static List<String> source(
      String frameRate, List<String> keyframeArguments, List<String> formatArguments) {
    var arguments =
        new ArrayList<>(
            List.of(
                "-f",
                "lavfi",
                "-i",
                "testsrc=size=160x90:duration=36:" + frameRate,
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:duration=36",
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

  private KeyframePositions storeProbeWithKeyframes(Path source) {
    var success = probeWithKeyframes(source);
    probeRepository.setDefaultOutcome(success);
    return ((KeyframeKnowledge.Known) success.keyframes()).positions();
  }

  // A source without an index makes the probe ask for a packet scan. The scan is a new attempt,
  // so it carries a new attempt ID, as the worker contract requires.
  private ProbeOutcome.Success probeWithKeyframes(Path source) {
    var requestBuilder =
        ProbeExecutionRequest.builder()
            .sourcePath(source)
            .probeVersion(ProbeVersion.CURRENT)
            .priority(ProbePriority.BACKGROUND);
    var outcome = probes.probe(requestBuilder.attemptId(UUID.randomUUID()).build());
    if (outcome instanceof ProbeOutcome.Success success
        && success.keyframes() instanceof KeyframeKnowledge.PacketScanRequired) {
      outcome = probes.scanKeyframePackets(requestBuilder.attemptId(UUID.randomUUID()).build());
    }

    assertThat(outcome).isInstanceOf(ProbeOutcome.Success.class);
    var success = (ProbeOutcome.Success) outcome;
    assertThat(success.keyframes()).isInstanceOf(KeyframeKnowledge.Known.class);
    return success;
  }

  // A copy runs past its video's end only when the probe states no stream end times, as for a
  // container header that states none: a stated video end before the audio's makes the session
  // encode the video and hold its last frame (ADR 0037, early ends).
  private void storeProbeWithoutStreamEndTimes(ProbeOutcome.Success stored) {
    var streams =
        stored.streams().stream()
            .map(stream -> stream.toBuilder().endTime(Optional.empty()).build())
            .toList();
    probeRepository.setDefaultOutcome(
        new ProbeOutcome.Success(
            stored.container(),
            streams,
            stored.keyframes(),
            stored.chosenVideoStream(),
            stored.streamTitlesOmitted(),
            stored.streamMeasurements()));
  }

  private StreamSession createSession(Path source) throws Exception {
    return createSession(source, declarationBuilderFor(ProbedCodecFamily.H264).build());
  }

  // H.264 in 8-bit SDR beside H.265 up to 10 bits in SDR, PQ and HLG.
  private static ClientCapabilityDeclaration h264AndHdrH265Declaration() {
    return declarationBuilderFor(ProbedCodecFamily.H264)
        .video(
            List.of(
                h264EntryBuilder().build(),
                hevcEntryBuilder()
                    .compatibleBases(
                        List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG))
                    .build()))
        .build();
  }

  private StreamSession createSession(Path source, ClientCapabilityDeclaration declaration)
      throws Exception {
    var file =
        mediaFileRepository.save(
            MediaFile.builder()
                .filepathUri(FilepathCodec.encode(source))
                .filename(source.getFileName().toString())
                .status(MediaFileStatus.MATCHED)
                .size(Files.size(source))
                .build());
    return OutcomeTestSupport.accepted(
        streamingService.createSession(
            createStreamSessionCommandBuilder(file.getId(), UUID.randomUUID())
                .capabilityDeclaration(declaration)
                .build()));
  }

  private void killProducerAndDropSegmentsFrom(
      StreamSession session, int firstMissingIndex, int segmentCount) throws Exception {
    workerFixture.worker().killProducer(session.getHandle().orElseThrow().attemptId());
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                !transcodeExecutor.isRunning(
                    session.getSessionId(), StreamSession.defaultVariant()));
    var outputDirectory = segmentStore.getOutputDirectory(session.getSessionId());
    for (var index = firstMissingIndex; index < segmentCount; index++) {
      Files.deleteIfExists(outputDirectory.resolve(SegmentNames.mediaSegmentName(index)));
    }
  }

  private List<Double> videoPresentationTimes(UUID sessionId) throws Exception {
    return videoPresentationTimes(sessionId, SEEK_SEGMENT_NAME);
  }

  private List<Double> videoPresentationTimes(UUID sessionId, String segmentName) throws Exception {
    var media =
        Files.write(
            Files.createTempFile(temporaryDirectory, "segment", ".mp4"),
            Fmp4Fixture.withInitializationSegment(segmentStore, sessionId, segmentName));
    return workerFixture.worker().packetTimestamps(media);
  }

  // The slots that the media reaches into, counted in whole milliseconds as the plain grid counts.
  private static long gridSlotCount(StreamSession session) {
    return Math.ceilDiv(session.getMediaProbe().duration().toMillis(), SEGMENT_SECONDS * 1000L);
  }

  private List<String> streamInfLines(StreamSession session) {
    return playlistService
        .generateMultivariantPlaylist(session, "smoke-token")
        .lines()
        .filter(line -> line.startsWith("#EXT-X-STREAM-INF:"))
        .toList();
  }

  private static List<Double> extinfSeconds(String playlist) {
    return playlist
        .lines()
        .filter(line -> line.startsWith("#EXTINF:"))
        .map(line -> Double.parseDouble(line.substring("#EXTINF:".length(), line.indexOf(','))))
        .toList();
  }
}
