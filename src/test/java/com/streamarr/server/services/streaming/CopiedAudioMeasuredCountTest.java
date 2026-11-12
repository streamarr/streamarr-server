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
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.MeasuredStream;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamMeasurementSource;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What a copied audio track counts once the server holds a measurement of it (ADR 0050, ADR 0054).
 *
 * <p>The source runs 2 hours and holds one 5.1 FLAC track that the client plays. The probe knows no
 * bitrate for the track, as in a Matroska file without statistics tags that the probe trusts, so
 * the decision plans it at the nominal 384 kb/s. The measurement holds 3,600,000,000 bytes.
 *
 * <p>The encoded variants' media playlist lists 1,200 media segments of 6 s, which is 7,200 s. Over
 * that playlist the track's bytes come to 3,600,000,000 × 8 / 7,200 s = 4,000,000 b/s.
 */
@Tag("UnitTest")
@DisplayName("Copied Audio Measured Count Tests")
class CopiedAudioMeasuredCountTest {

  private static final int VIDEO_STREAM = 0;
  private static final int AUDIO_STREAM = 1;
  private static final int SECOND_AUDIO_STREAM = 2;
  private static final long NOMINAL_BITRATE = 384_000L;
  private static final long COUNTED_BITRATE = 4_000_000L;

  @Test
  @DisplayName(
      "Should count a copied track at its measured bytes over the media playlist when the video is"
          + " encoded")
  void shouldCountACopiedTrackAtItsMeasuredBytesOverTheMediaPlaylistWhenTheVideoIsEncoded() {
    var decision = decide(measured(sourceThatIsEncoded()), ViewerLimits.builder().build());

    assertThat(decision.transcodeMode().copiesVideo()).isFalse();
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().bitrate()).isEqualTo(COUNTED_BITRATE);
  }

  @Test
  @DisplayName(
      "Should count a copied track at its measured bytes over the media playlist when the video is"
          + " copied")
  void shouldCountACopiedTrackAtItsMeasuredBytesOverTheMediaPlaylistWhenTheVideoIsCopied() {
    var decision = decide(measured(sourceThatIsCopied()), ViewerLimits.builder().build());

    assertThat(decision.transcodeMode().copiesVideo()).isTrue();
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().bitrate()).isEqualTo(COUNTED_BITRATE);
  }

  // A measurement is exact. A probed bitrate can come from statistics tags that a muxer wrote.
  @Test
  @DisplayName(
      "Should count a copied track at its measured bytes over the media playlist when the probe"
          + " knows another bitrate")
  void
      shouldCountACopiedTrackAtItsMeasuredBytesOverTheMediaPlaylistWhenTheProbeKnowsAnotherBitrate() {
    var source =
        measured(sourceThatIsEncoded()).toBuilder()
            .audioBitrate(OptionalLong.of(1_500_000L))
            .build();

    var decision = decide(source, ViewerLimits.builder().build());

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().bitrate()).isEqualTo(COUNTED_BITRATE);
  }

  // ADR 0054: a track that ends early adds less than its own measured bitrate. The track holds
  // 1,800,000,000 bytes in its 1 hour, which is 4,000 kb/s. Over the 2 hours of the media playlist
  // they come to 1,800,000,000 × 8 / 7,200 s = 2,000,000 b/s.
  @Test
  @DisplayName(
      "Should count a copied track over the media playlist's duration when the track is shorter"
          + " than the media playlist")
  void
      shouldCountACopiedTrackOverTheMediaPlaylistsDurationWhenTheTrackIsShorterThanTheMediaPlaylist() {
    var track =
        StreamMeasurementFixture.measuredStreamBuilder(AUDIO_STREAM, 1_800_000_000L)
            .measuredDuration(3_600_000)
            .build();

    var decision = decide(measured(sourceThatIsEncoded(), track), ViewerLimits.builder().build());

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().bitrate()).isEqualTo(2_000_000L);
  }

  // The file holds two 5.1 FLAC tracks, and the stream session plays the second. That track's
  // 1,800,000,000 bytes come to 1,800,000,000 × 8 / 7,200 s = 2,000,000 b/s. The first track's
  // 3,600,000,000 bytes would come to 4,000,000 b/s.
  @Test
  @DisplayName(
      "Should count the stream session's audio track when it is not the file's first audio track")
  void shouldCountTheStreamSessionsAudioTrackWhenItIsNotTheFilesFirstAudioTrack() {
    var oneTrack = sourceThatIsEncoded();
    var secondTrack =
        ProbeFixture.audioStreamBuilder("flac")
            .index(SECOND_AUDIO_STREAM)
            .channels(OptionalInt.of(6))
            .bitrate(OptionalLong.empty())
            .build();
    var measurement =
        new StreamMeasurements(
            StreamMeasurementSource.BITRATE_SCAN,
            List.of(
                measuredVideo(),
                measuredTrack(AUDIO_STREAM, 3_600_000_000L),
                measuredTrack(SECOND_AUDIO_STREAM, 1_800_000_000L)));
    var source =
        oneTrack.toBuilder()
            .streams(Stream.concat(oneTrack.streams().stream(), Stream.of(secondTrack)).toList())
            .streamMeasurements(Optional.of(measurement))
            .build()
            .withSessionAudioTrackAt(1);

    var decision = decide(source, ViewerLimits.builder().build());

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().bitrate()).isEqualTo(2_000_000L);
  }

  @Test
  @DisplayName("Should count a copied track at its nominal bitrate when the source has no length")
  void shouldCountACopiedTrackAtItsNominalBitrateWhenTheSourceHasNoLength() {
    var source = measured(sourceThatIsEncoded().toBuilder().duration(Duration.ZERO).build());

    var decision = decide(source, ViewerLimits.builder().build());

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().bitrate()).isEqualTo(NOMINAL_BITRATE);
  }

  @Test
  @DisplayName("Should count a copied track at its nominal bitrate when no measurement holds it")
  void shouldCountACopiedTrackAtItsNominalBitrateWhenNoMeasurementHoldsIt() {
    var videoAlone =
        new StreamMeasurements(StreamMeasurementSource.BITRATE_SCAN, List.of(measuredVideo()));
    var source =
        sourceThatIsEncoded().toBuilder().streamMeasurements(Optional.of(videoAlone)).build();

    var decision = decide(source, ViewerLimits.builder().build());

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().bitrate()).isEqualTo(NOMINAL_BITRATE);
  }

  // Without a FLAC entry the session encodes the track in AC-3 5.1 at 640 kb/s, which its encoder
  // takes whatever the source track holds.
  @Test
  @DisplayName(
      "Should keep an audio encode's bitrate when the server measured the track that it encodes")
  void shouldKeepAnAudioEncodesBitrateWhenTheServerMeasuredTheTrackThatItEncodes() {
    var declaration =
        defaultDeclarationBuilder()
            .audio(
                List.of(
                    aacEntryBuilder().build(),
                    audioEntryBuilder(AudioCodec.AC3).maxChannels(6).build()))
            .build();

    var decision =
        new TranscodeDecisionService(streamingProperties(), new SimpleMeterRegistry())
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(measured(sourceThatIsEncoded()))
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(Set.of("h264"))
                    .build())
            .orElseThrow();

    assertThat(decision.audioDecision())
        .extracting(AudioDecision::mode, AudioDecision::codec, AudioDecision::bitrate)
        .containsExactly(AudioMode.TRANSCODE, "ac3", 640_000L);
  }

  // ADR 0050: a step counts only when it is smaller than the steps before it. A 5.1 AC-3 track at
  // 448 kb/s holds 403,200,000 bytes in 2 hours, which is 448,000 b/s, so an AC-3 5.1 encode at
  // 448 kb/s is no step below it. 1,250 kb/s less 448 kb/s leaves 802 kb/s, which the 360p tier's
  // 800 kb/s fits.
  @Test
  @DisplayName(
      "Should keep a copied track when the 360p tier fits beside it and its codec's encode at its"
          + " bitrate would fit too")
  void
      shouldKeepACopiedTrackWhenThe360pTierFitsBesideItAndItsCodecsEncodeAtItsBitrateWouldFitToo() {
    var probed = withSurroundTrack(sourceThatIsEncoded(), "ac3", OptionalLong.of(448_000L));
    var track =
        StreamMeasurementFixture.measuredStreamBuilder(AUDIO_STREAM, 403_200_000L)
            .packetCount(225_000)
            .measuredDuration(7_200_000)
            .build();

    var session = createSession(measured(probed, track), withMaximumBitrate(1_250_000));

    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(AudioDecision::mode, AudioDecision::codec, AudioDecision::bitrate)
        .containsExactly(AudioMode.COPY, "ac3", 448_000L);
    assertThat(session.getTranscodeDecision().reasons())
        .doesNotContain(DecisionReason.AUDIO_LOWERED_FOR_BITRATE_LIMIT);
  }

  // ADR 0050: 5,000 kb/s less the track's 4,000 kb/s leaves 1,000 kb/s, which only the 360p tier's
  // 800 kb/s fits. The server's AC-3 5.1 encode at 448 kb/s lets the 720p tier's 3,000 kb/s fit,
  // and lower multichannel audio that lets a larger picture fit is taken. At the nominal 384 kb/s,
  // the ladder would list the 720p tier beside a track that delivers 4,000 kb/s.
  @Test
  @DisplayName(
      "Should lower the audio for a larger picture when a copied track's measured bytes leave only"
          + " the 360p tier")
  void shouldLowerTheAudioForALargerPictureWhenACopiedTracksMeasuredBytesLeaveOnlyThe360pTier() {
    var session = createSession(measured(sourceThatIsEncoded()), withMaximumBitrate(5_000_000));

    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(
            AudioDecision::mode,
            AudioDecision::codec,
            AudioDecision::channels,
            AudioDecision::bitrate)
        .containsExactly(AudioMode.TRANSCODE, "ac3", 6, 448_000L);
    assertThat(session.getTranscodeDecision().reasons())
        .contains(DecisionReason.AUDIO_LOWERED_FOR_BITRATE_LIMIT);
    assertThat(session.getVariants()).extracting(QualityVariant::label).contains("720p");
    assertThat(session.getVariants())
        .allSatisfy(
            variant ->
                assertThat(variant.videoBitrate() + 448_000L)
                    .as("video and audio of the %s variant", variant.label())
                    .isLessThanOrEqualTo(5_000_000L));
  }

  @Test
  @DisplayName(
      "Should keep a copied track and name no bitrate limit when every tier fits beside its"
          + " measured bytes")
  void shouldKeepACopiedTrackAndNameNoBitrateLimitWhenEveryTierFitsBesideItsMeasuredBytes() {
    var unbounded = createSession(measured(sourceThatIsEncoded()), withoutMaximumBitrate());

    var session = createSession(measured(sourceThatIsEncoded()), withMaximumBitrate(20_000_000));

    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(AudioDecision::mode, AudioDecision::bitrate)
        .containsExactly(AudioMode.COPY, COUNTED_BITRATE);
    assertThat(session.getTranscodeDecision().reasons())
        .doesNotContain(DecisionReason.AUDIO_LOWERED_FOR_BITRATE_LIMIT);
    assertThat(session.getVariants()).isNotEmpty().isEqualTo(unbounded.getVariants());
  }

  @Test
  @DisplayName(
      "Should advertise the copied track's measured bytes in every encoded variant's"
          + " AVERAGE-BANDWIDTH when the video is encoded")
  void
      shouldAdvertiseTheCopiedTracksMeasuredBytesInEveryEncodedVariantsAverageBandwidthWhenTheVideoIsEncoded() {
    var session = createSession(measured(sourceThatIsEncoded()), withoutMaximumBitrate());

    var playlist =
        new HlsPlaylistService(streamingProperties()).generateMultivariantPlaylist(session, "t");

    assertThat(session.getTranscodeDecision().audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(session.getVariants()).isNotEmpty();
    assertThat(attribute("AVERAGE-BANDWIDTH", playlist))
        .containsExactlyElementsOf(
            session.getVariants().stream()
                .map(variant -> variant.videoBitrate() + COUNTED_BITRATE)
                .toList());
  }

  // ADR 0050: 2,000 kb/s less the track's 4,000 kb/s leaves no room for the 360p tier's 800 kb/s,
  // so the audio steps down. FLAC has no multichannel steps of its own, so the first step is the
  // server's AC-3 5.1 encode at 448 kb/s, which leaves 1,552 kb/s for the video.
  @Test
  @DisplayName(
      "Should lower the audio when a copied track's measured bytes leave no room for the 360p"
          + " tier")
  void shouldLowerTheAudioWhenACopiedTracksMeasuredBytesLeaveNoRoomForThe360pTier() {
    var session = createSession(measured(sourceThatIsEncoded()), withMaximumBitrate(2_000_000));

    assertThat(session.getTranscodeDecision().audioDecision())
        .extracting(
            AudioDecision::mode,
            AudioDecision::codec,
            AudioDecision::channels,
            AudioDecision::bitrate)
        .containsExactly(AudioMode.TRANSCODE, "ac3", 6, 448_000L);
    assertThat(session.getTranscodeDecision().reasons())
        .contains(DecisionReason.AUDIO_LOWERED_FOR_BITRATE_LIMIT);
    assertThat(session.getVariants())
        .isNotEmpty()
        .allSatisfy(
            variant ->
                assertThat(variant.videoBitrate() + 448_000L)
                    .as("video and audio of the %s variant", variant.label())
                    .isLessThanOrEqualTo(2_000_000L));
    assertThat(session.getVariants()).extracting(QualityVariant::label).contains("360p");
  }

  // The copy of both streams costs about 8,500 kb/s. The steps below the track's 4,000 kb/s start
  // with the server's AC-3 5.1 encode at 448 kb/s, beside which the copied video costs under 5,000
  // kb/s. Below the nominal 384 kb/s, the first step would be AAC stereo at 128 kb/s.
  @Test
  @DisplayName(
      "Should step a copied track to the highest multichannel step that fits when a video copy"
          + " beside it exceeds the maximum bitrate")
  void
      shouldStepACopiedTrackToTheHighestMultichannelStepThatFitsWhenAVideoCopyBesideItExceedsTheMaximumBitrate() {
    var decision =
        decide(
            measured(sourceThatIsCopied()),
            ViewerLimits.builder().maxBitrate(OptionalLong.of(5_200_000L)).build());

    assertThat(decision.transcodeMode().copiesVideo()).isTrue();
    assertThat(decision.audioDecision())
        .extracting(
            AudioDecision::mode,
            AudioDecision::codec,
            AudioDecision::channels,
            AudioDecision::bitrate)
        .containsExactly(AudioMode.TRANSCODE, "ac3", 6, 448_000L);
    assertThat(decision.reasons()).contains(DecisionReason.AUDIO_LOWERED_FOR_BITRATE_LIMIT);
  }

  @Test
  @DisplayName(
      "Should keep a copied track and name no bitrate limit when a video copy beside it fits the"
          + " maximum bitrate")
  void shouldKeepACopiedTrackAndNameNoBitrateLimitWhenAVideoCopyBesideItFitsTheMaximumBitrate() {
    var decision =
        decide(
            measured(sourceThatIsCopied()),
            ViewerLimits.builder().maxBitrate(OptionalLong.of(9_000_000L)).build());

    assertThat(decision.transcodeMode().copiesVideo()).isTrue();
    assertThat(decision.audioDecision())
        .extracting(AudioDecision::mode, AudioDecision::codec)
        .containsExactly(AudioMode.COPY, "flac");
    assertThat(decision.reasons()).doesNotContain(DecisionReason.AUDIO_LOWERED_FOR_BITRATE_LIMIT);
  }

  private static TranscodeDecision decide(MediaProbe source, ViewerLimits limits) {
    return new TranscodeDecisionService(streamingProperties(), new SimpleMeterRegistry())
        .decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaringSurroundFlac())
                .encodableVideoCodecFamilies(Set.of("h264"))
                .viewerLimits(limits)
                .build())
        .orElseThrow();
  }

  // The client's H.264 entry makes the server encode an HEVC source.
  private static MediaProbe sourceThatIsEncoded() {
    return withUnprobedSurroundFlac(defaultProbeBuilder().videoCodec("hevc").build());
  }

  private static MediaProbe sourceThatIsCopied() {
    return withUnprobedSurroundFlac(defaultProbeBuilder().build());
  }

  private static MediaProbe withUnprobedSurroundFlac(MediaProbe probe) {
    return withSurroundTrack(probe, "flac", OptionalLong.empty());
  }

  private static MediaProbe withSurroundTrack(
      MediaProbe probe, String codec, OptionalLong bitrate) {
    var streams =
        List.of(
            ProbeFixture.videoStreamBuilder(probe).index(VIDEO_STREAM).build(),
            ProbeFixture.audioStreamBuilder(codec)
                .index(AUDIO_STREAM)
                .channels(OptionalInt.of(6))
                .bitrate(bitrate)
                .build());
    return probe.toBuilder()
        .audioCodec(codec)
        .audioChannels(OptionalInt.of(6))
        .audioBitrate(bitrate)
        .streams(streams)
        .build();
  }

  // 84,375 frames of 4,096 samples at 48 kHz last 2 hours.
  private static MediaProbe measured(MediaProbe source) {
    return measured(source, measuredTrack(AUDIO_STREAM, 3_600_000_000L));
  }

  private static MeasuredStream measuredTrack(int stream, long bytes) {
    return StreamMeasurementFixture.measuredStreamBuilder(stream, bytes)
        .packetCount(84_375)
        .measuredDuration(7_200_000)
        .build();
  }

  private static MediaProbe measured(MediaProbe source, MeasuredStream track) {
    var measurement =
        new StreamMeasurements(
            StreamMeasurementSource.BITRATE_SCAN, List.of(measuredVideo(), track));
    return source.toBuilder().streamMeasurements(Optional.of(measurement)).build();
  }

  // 4,050,000,000 bytes over 2 hours is 4,500 kb/s.
  private static MeasuredStream measuredVideo() {
    return StreamMeasurementFixture.measuredStreamBuilder(VIDEO_STREAM, 4_050_000_000L)
        .packetCount(172_800)
        .measuredDuration(7_200_000)
        .build();
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

  private static StreamingOptions withMaximumBitrate(int maxBitrate) {
    return StreamingOptions.builder().quality(VideoQuality.AUTO).maxBitrate(maxBitrate).build();
  }

  private static StreamingOptions withoutMaximumBitrate() {
    return StreamingOptions.builder().quality(VideoQuality.AUTO).build();
  }

  private static StreamSession createSession(MediaProbe source, StreamingOptions options) {
    var mediaFileRepository = new FakeMediaFileRepository();
    var probeResults = new FakeMediaFileContainerInfoRepository();
    probeResults.setDefaultOutcome(
        ProbeFixture.completeProbeBuilder(source)
            .streamMeasurements(source.streamMeasurements())
            .build());
    var file =
        mediaFileRepository.save(
            MediaFile.builder()
                .filepathUri("file:///media/movies/test.mkv")
                .filename("test.mkv")
                .status(MediaFileStatus.MATCHED)
                .size(1_000_000L)
                .build());
    return accepted(
        streamingService(mediaFileRepository, probeResults)
            .createSession(
                createStreamSessionCommandBuilder(file.getId(), UUID.randomUUID())
                    .options(options)
                    .capabilityDeclaration(declaringSurroundFlac())
                    .build()));
  }

  private static HlsStreamingService streamingService(
      FakeMediaFileRepository mediaFileRepository,
      FakeMediaFileContainerInfoRepository probeResults) {
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
        .languagePreferences((_, _) -> List.of())
        .build();
  }
}
