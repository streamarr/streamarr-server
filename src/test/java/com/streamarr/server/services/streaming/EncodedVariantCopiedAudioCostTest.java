package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.aacEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.audioEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommandBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.AudioCodec;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamMeasurementSource;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.StreamMeasurementFixture;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.services.concurrency.MutexFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What a copied audio track costs beside an encoded video (ADR 0050, ADR 0054). The source is a
 * 2-hour HEVC file, which the client's H.264 entry makes the server encode, with one 5.1 FLAC track
 * that the client plays. The probe knows no bitrate for the track, as for FLAC in a Matroska file
 * without trusted statistics tags, and the server holds a measurement of it: 3,600,000,000 bytes in
 * 84,375 frames of 4,096 samples at 48 kHz, 4 Mb/s. The encoded variants list 1,200 segments of 6
 * s, so the track's framing adds 64 bytes in each segment and 16 bytes for each frame, 1,426,800
 * bytes. Its average segment bit rate is 3,601,426,800 bytes × 8 / 7,200 s, 4,001,586 b/s rounded
 * up.
 */
@Tag("UnitTest")
@DisplayName("Encoded Variant Copied Audio Cost Tests")
class EncodedVariantCopiedAudioCostTest {

  private static final int VIDEO_STREAM = 0;
  private static final int AUDIO_STREAM = 1;
  private static final long MEASURED_AUDIO_BYTES = 3_600_000_000L;
  private static final long MEASURED_AUDIO_FRAMES = 84_375L;
  private static final long MEASURED_AUDIO_BIT_RATE = 4_001_586L;

  private FakeMediaFileRepository mediaFileRepository;
  private FakeMediaFileContainerInfoRepository probeResults;
  private HlsStreamingService service;

  @BeforeEach
  void setUp() {
    mediaFileRepository = new FakeMediaFileRepository();
    probeResults = new FakeMediaFileContainerInfoRepository();
    probeResults.setDefaultOutcome(
        ProbeFixture.completeProbeBuilder(sourceWithUnprobedSurroundFlac())
            .streamMeasurements(Optional.of(measurement()))
            .build());
    service = streamingService();
  }

  @Test
  @DisplayName(
      "Should cost a copied audio track at its measured average segment bit rate when the video is"
          + " encoded and the probe knows no audio bitrate")
  void shouldCostCopiedAudioAtItsMeasuredAverageSegmentBitRateWhenTheVideoIsEncoded() {
    var source =
        sourceWithUnprobedSurroundFlac().toBuilder()
            .streamMeasurements(Optional.of(measurement()))
            .build();

    var decision =
        new TranscodeDecisionService(streamingProperties(), new SimpleMeterRegistry())
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaringSurroundFlac())
                    .encodableVideoCodecFamilies(Set.of("h264"))
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().bitrate()).isEqualTo(MEASURED_AUDIO_BIT_RATE);
  }

  // ADR 0054: the cost uses the average segment bit rate, which counts the track's bytes and its
  // framing, and not a bitrate that the probe read from the file.
  @Test
  @DisplayName(
      "Should cost a copied audio track at its measured average segment bit rate when the video is"
          + " encoded and the probe knows another audio bitrate")
  void shouldCostCopiedAudioAtItsMeasuredAverageWhenTheProbeKnowsAnotherAudioBitrate() {
    var source =
        sourceWithUnprobedSurroundFlac().toBuilder()
            .audioBitrate(OptionalLong.of(1_500_000L))
            .streamMeasurements(Optional.of(measurement()))
            .build();

    var audio = decideAudioBesideEncodedVideo(source);

    assertThat(audio.mode()).isEqualTo(AudioMode.COPY);
    assertThat(audio.bitrate()).isEqualTo(MEASURED_AUDIO_BIT_RATE);
  }

  @Test
  @DisplayName(
      "Should cost a copied audio track at its probed bitrate when the video is encoded and the"
          + " measurement holds no audio stream")
  void shouldCostCopiedAudioAtItsProbedBitrateWhenTheMeasurementHoldsNoAudioStream() {
    var videoOnly =
        new StreamMeasurements(
            StreamMeasurementSource.BITRATE_SCAN,
            List.of(
                StreamMeasurementFixture.measuredStreamBuilder(VIDEO_STREAM, 4_050_000_000L)
                    .packetCount(172_800)
                    .measuredDuration(7_200_000)
                    .build()));
    var source =
        sourceWithUnprobedSurroundFlac().toBuilder()
            .audioBitrate(OptionalLong.of(1_500_000L))
            .streamMeasurements(Optional.of(videoOnly))
            .build();

    var audio = decideAudioBesideEncodedVideo(source);

    assertThat(audio.mode()).isEqualTo(AudioMode.COPY);
    assertThat(audio.bitrate()).isEqualTo(1_500_000L);
  }

  @Test
  @DisplayName(
      "Should advertise the copied audio's measured average in every encoded variant's"
          + " AVERAGE-BANDWIDTH when the probe knows no audio bitrate")
  void shouldAdvertiseCopiedAudiosMeasuredAverageInEveryEncodedVariantsAverageBandwidth() {
    var session = createSession(StreamingOptions.builder().quality(VideoQuality.AUTO).build());

    var playlist = multivariantPlaylist(session);

    assertThat(session.getTranscodeDecision().audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(session.getVariants()).isNotEmpty();
    assertThat(attribute("AVERAGE-BANDWIDTH", playlist))
        .containsExactlyElementsOf(
            session.getVariants().stream()
                .map(variant -> variant.videoBitrate() + MEASURED_AUDIO_BIT_RATE)
                .toList());
  }

  // RFC 8216 section 4.3.4.2: BANDWIDTH is the peak segment bit rate, which is never below the
  // average that the variant's segments deliver.
  @Test
  @DisplayName(
      "Should advertise a BANDWIDTH at or above the encoded video plus the copied audio's measured"
          + " average when the probe knows no audio bitrate")
  void shouldAdvertiseBandwidthAtOrAboveEncodedVideoPlusCopiedAudiosMeasuredAverage() {
    var session = createSession(StreamingOptions.builder().quality(VideoQuality.AUTO).build());

    var bandwidths = attribute("BANDWIDTH", multivariantPlaylist(session));

    assertThat(session.getTranscodeDecision().audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(bandwidths).hasSameSizeAs(session.getVariants());
    for (int i = 0; i < bandwidths.size(); i++) {
      assertThat(bandwidths.get(i))
          .as("BANDWIDTH of the %s variant", session.getVariants().get(i).label())
          .isGreaterThanOrEqualTo(
              session.getVariants().get(i).videoBitrate() + MEASURED_AUDIO_BIT_RATE);
    }
  }

  // ADR 0050: the copy alone exceeds 2,000 kb/s, so the audio steps down. FLAC has no multichannel
  // steps of its own, so the first step that leaves the 360p tier's 800 kb/s is the server's AC-3
  // 5.1 encode at 448 kb/s, beside which the 480p and 360p tiers fit.
  @Test
  @DisplayName(
      "Should lower the audio below a copied track whose measured average exceeds the maximum"
          + " bitrate when the video is encoded and the probe knows no audio bitrate")
  void shouldLowerAudioBelowCopiedTrackWhoseMeasuredAverageExceedsTheMaximumBitrate() {
    var session =
        createSession(
            StreamingOptions.builder().quality(VideoQuality.AUTO).maxBitrate(2_000_000).build());

    assertThat(session.getVariants()).isNotEmpty();
    assertThat(session.getVariants())
        .as("each offered variant's video bitrate plus what its audio delivers")
        .allSatisfy(
            variant ->
                assertThat(variant.videoBitrate() + deliveredAudioBitRate(session))
                    .isLessThanOrEqualTo(2_000_000L));
    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(
            AudioDecision::mode,
            AudioDecision::codec,
            AudioDecision::channels,
            AudioDecision::bitrate)
        .containsExactly(AudioMode.TRANSCODE, "ac3", 6, 448_000L);
    assertThat(session.getVariants())
        .extracting(QualityVariant::label)
        .containsExactlyInAnyOrder("480p", "360p");
  }

  // A copied track delivers its measured bytes whatever bitrate the decision names; an encode
  // delivers the bitrate that the decision gives its encoder.
  private static long deliveredAudioBitRate(StreamSession session) {
    var audio = session.getTranscodeDecision().audioDecision();
    if (audio.mode() == AudioMode.COPY) {
      return MEASURED_AUDIO_BIT_RATE;
    }

    return audio.bitrate();
  }

  private static AudioDecision decideAudioBesideEncodedVideo(MediaProbe source) {
    var decision =
        new TranscodeDecisionService(streamingProperties(), new SimpleMeterRegistry())
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaringSurroundFlac())
                    .encodableVideoCodecFamilies(Set.of("h264"))
                    .build())
            .orElseThrow();
    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    return decision.audioDecision();
  }

  private static MediaProbe sourceWithUnprobedSurroundFlac() {
    var probe = defaultProbeBuilder().videoCodec("hevc").audioCodec("flac");
    var streams =
        List.of(
            ProbeFixture.videoStreamBuilder(probe.build()).index(VIDEO_STREAM).build(),
            ProbeFixture.audioStreamBuilder("flac")
                .index(AUDIO_STREAM)
                .channels(OptionalInt.of(6))
                .bitrate(OptionalLong.empty())
                .build());
    return probe
        .audioChannels(OptionalInt.of(6))
        .audioBitrate(OptionalLong.empty())
        .streams(streams)
        .build();
  }

  private static StreamMeasurements measurement() {
    return new StreamMeasurements(
        StreamMeasurementSource.BITRATE_SCAN,
        List.of(
            StreamMeasurementFixture.measuredStreamBuilder(VIDEO_STREAM, 4_050_000_000L)
                .packetCount(172_800)
                .measuredDuration(7_200_000)
                .build(),
            StreamMeasurementFixture.measuredStreamBuilder(AUDIO_STREAM, MEASURED_AUDIO_BYTES)
                .packetCount(MEASURED_AUDIO_FRAMES)
                .measuredDuration(7_200_000)
                .build()));
  }

  private static ClientCapabilityDeclaration declaringSurroundFlac() {
    return defaultDeclarationBuilder()
        .audio(
            List.of(
                aacEntryBuilder().build(),
                audioEntryBuilder(AudioCodec.FLAC).maxChannels(6).build(),
                audioEntryBuilder(AudioCodec.AC3).maxChannels(6).build()))
        .build();
  }

  private StreamSession createSession(StreamingOptions options) {
    var file =
        mediaFileRepository.save(
            MediaFile.builder()
                .filepathUri("file:///media/movies/test.mkv")
                .filename("test.mkv")
                .status(MediaFileStatus.MATCHED)
                .size(1_000_000L)
                .build());
    return accepted(
        service.createSession(
            createStreamSessionCommandBuilder(file.getId(), UUID.randomUUID())
                .options(options)
                .capabilityDeclaration(declaringSurroundFlac())
                .build()));
  }

  private static String multivariantPlaylist(StreamSession session) {
    return new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");
  }

  private static List<Long> attribute(String name, String playlist) {
    return Pattern.compile("[:,]" + name + "=(\\d+)")
        .matcher(playlist)
        .results()
        .map(match -> Long.parseLong(match.group(1)))
        .toList();
  }

  private static StreamingProperties streamingProperties() {
    return StreamingProperties.builder()
        .maxConcurrentTranscodes(3)
        .targetSegmentDuration(Duration.ofSeconds(6))
        .sessionTimeout(Duration.ofSeconds(60))
        .build();
  }

  private HlsStreamingService streamingService() {
    var properties = streamingProperties();
    var transcodeExecutor = new FakeTranscodeExecutor();
    var segmentStore = new FakeSegmentStore();
    var runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    var meters = new SimpleMeterRegistry();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(runtimeRegistry)
            .heldRequests(transcodeExecutor.heldSegmentRequests())
            .sessionMutex(new MutexFactory<>())
            .build();
    return HlsStreamingService.builder()
        .mediaFileRepository(mediaFileRepository)
        .transcodeExecutor(transcodeExecutor)
        .segmentStore(segmentStore)
        .playbackProbeService(
            playbackProbeServiceBuilder(probeResults)
                .eventPublisher(new CapturingEventPublisher())
                .build())
        .transcodeDecisionService(new TranscodeDecisionService(properties, meters))
        .qualityLadderService(new QualityLadderService())
        .properties(properties)
        .authorityGate(new FakePlaybackAuthorityGate())
        .runtimeRegistry(runtimeRegistry)
        .producerLifecycle(rig.lifecycle())
        .deliveryCoordinator(rig.coordinator())
        .meterRegistry(meters)
        .languagePreferences((profileId, file) -> List.of())
        .build();
  }
}
