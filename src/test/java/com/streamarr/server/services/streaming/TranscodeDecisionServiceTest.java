package com.streamarr.server.services.streaming;

import static com.streamarr.server.domain.streaming.AudioCodec.AAC;
import static com.streamarr.server.domain.streaming.AudioCodec.AC3;
import static com.streamarr.server.domain.streaming.AudioCodec.EAC3;
import static com.streamarr.server.domain.streaming.AudioCodec.FLAC;
import static com.streamarr.server.domain.streaming.AudioCodec.OPUS;
import static com.streamarr.server.domain.streaming.ProbedCodecFamily.AV1;
import static com.streamarr.server.domain.streaming.ProbedCodecFamily.H264;
import static com.streamarr.server.domain.streaming.ProbedCodecFamily.HEVC;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.audioEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.av1EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.declarationBuilderFor;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture.libsvtav1;
import static com.streamarr.server.fixtures.WorkerVerifiedEncoderFixture.libx264;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.AudioCapabilityEntry;
import com.streamarr.server.domain.streaming.AudioCodec;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.ChannelLayout;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import com.streamarr.server.domain.streaming.EncodeFormat;
import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.FrameRateRule;
import com.streamarr.server.domain.streaming.FrameRateRuleInputs;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PictureSize;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.VideoCapabilityEntry;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecProfileCapability;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoEncodeFeature;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoRange;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.fixtures.HardwareEncodedFamiliesAndExpectedEncode;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.StreamMeasurementFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.properties.StreamingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Transcode Decision Service Tests")
class TranscodeDecisionServiceTest {

  private static final KeyframeKnowledge KEYFRAMES_EVERY_TWO_SECONDS =
      ProbeFixture.keyframesEvery(Duration.ofSeconds(2), Duration.ofMinutes(120));

  // At the 6 s period, keyframes 10.01 s apart leave the slot [12 s, 18 s) without one.
  private static final KeyframeKnowledge KEYFRAMES_LEAVING_A_SLOT_EMPTY =
      ProbeFixture.keyframesEvery(Duration.ofMillis(10_010), Duration.ofMinutes(120));

  // At the 6 s period, the keyframes open segments at 0, 11, 17 and 24 s; the one at 12.5 s
  // presents less than 2 s after 11 s.
  private static final List<Long> KEYFRAMES_SKIPPING_ONE_TOO_SOON_AFTER_A_START =
      List.of(0L, 11_000L, 12_500L, 17_000L, 24_000L);

  private static final Set<String> EVERY_VIDEO_CODEC_FAMILY = Set.of("h264", "hevc", "av1");
  // A worker encodes AV1 in hardware, so AV1 comes before H.264 when both keep the frame rate.
  private static final Set<String> HARDWARE_AV1 = Set.of("av1");

  private static final String STREAM_COPY_REFUSALS = "streamarr.streaming.stream_copy_refusals";

  // Without a measurement, a segment's estimate is twice the copy's unmeasured cost over its
  // EXTINF duration (ADR 0037). Over 64 s, twice 8,388,608 bit/s comes to 134,217,728 bytes, the
  // 128 MiB segment cap.
  private static final long BITRATE_AT_THE_SEGMENT_CAP = 8_388_608L;

  // 74 s at 8,388,608 bit/s.
  private static final long FILE_BYTES_AT_THE_SEGMENT_CAP = 77_594_624L;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private final TranscodeDecisionService service =
      new TranscodeDecisionService(
          StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build(),
          meterRegistry);

  @Test
  @DisplayName("Should encode a copyable video when the format attempt requires encoding")
  void shouldEncodeACopyableVideoWhenTheFormatAttemptRequiresEncoding() {
    var source = probe("h264", "aac");

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(Set.of("h264"))
                    .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.encodeCodecProfile()).contains(EncodeCodecProfile.H264_HIGH);
    assertThat(decision.videoSource()).isEqualTo(source.videoPlaybackFacts());
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
  }

  @Test
  @DisplayName(
      "Should encode the video when the source exceeds the viewer's limits and the format attempt"
          + " may copy or encode")
  void
      shouldEncodeTheVideoWhenTheSourceExceedsTheViewersLimitsAndTheFormatAttemptMayCopyOrEncode() {
    var decision = decideOverAFixed720p();

    assertThat(decision.map(TranscodeDecision::transcodeMode))
        .contains(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(meterRegistry.find(STREAM_COPY_REFUSALS).counter()).isNull();
  }

  // A copyable 1920×1080 H.264 source against the fixed 720p's 1280×720 box.
  private static ViewerLimits limitsIn(VideoQuality quality) {
    return ViewerLimits.of(StreamingOptions.builder().quality(quality).build());
  }

  private Optional<TranscodeDecision> decideOverAFixed720p() {
    var limits =
        ViewerLimits.of(StreamingOptions.builder().quality(VideoQuality.HIGH_720P).build());

    return service.decide(
        TranscodeDecisionRequest.builder()
            .source(probe("h264", "aac"))
            .capabilityDeclaration(declaring(H264))
            .encodableVideoCodecFamilies(Set.of("h264"))
            .viewerLimits(limits)
            .build());
  }

  @Test
  @DisplayName(
      "Should copy the video when the source exceeds the viewer's limits and has no usable frame"
          + " rate")
  void shouldCopyTheVideoWhenTheSourceExceedsTheViewersLimitsAndHasNoUsableFrameRate() {
    var limits =
        ViewerLimits.of(StreamingOptions.builder().quality(VideoQuality.HIGH_720P).build());
    var refusedRule = VideoPlaybackFactsFixture.refusedRule();
    var source =
        probeBuilder("h264", "aac")
            .framerate(OptionalDouble.empty())
            .videoPlaybackFacts(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .streamIndex(0)
                        .codecString(Optional.of(SDR_CODEC_STRINGS.get("h264")))
                        .frameRateRule(refusedRule)
                        .build()))
            .build();

    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaring(H264))
                .encodableVideoCodecFamilies(Set.of("h264"))
                .viewerLimits(limits)
                .build());

    assertThat(decision.map(TranscodeDecision::transcodeMode)).contains(TranscodeMode.REMUX);
  }

  @Test
  @DisplayName("Should copy video and negotiate audio when no video encoder is available")
  void shouldCopyVideoAndNegotiateAudioWhenNoVideoEncoderIsAvailable() {
    var source = probe("h264", "flac");

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(Set.of())
                    .excludedEncodeCodecFamilies(Set.of("h264"))
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.videoSource()).isEqualTo(source.videoPlaybackFacts());
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName("Should refuse a decision request when it carries no capability declaration")
  void shouldRefuseADecisionRequestWhenItCarriesNoCapabilityDeclaration() {
    var request =
        TranscodeDecisionRequest.builder()
            .source(probe("h264", "aac"))
            .encodableVideoCodecFamilies(Set.of("h264"));

    assertThatThrownBy(request::build)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("capabilityDeclaration");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("excludedEncodeFamilies")
  @DisplayName("Should choose only untried encodes when previous codec families are excluded")
  void shouldChooseOnlyUntriedEncodesWhenPreviousCodecFamiliesAreExcluded(
      String scenario, Set<String> excluded, Optional<String> expectedFamily) {
    var excludedFamilies = new HashSet<>(excluded);
    var request =
        TranscodeDecisionRequest.builder()
            .source(probe("hevc", "aac"))
            .capabilityDeclaration(declaring(AV1, H264))
            .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
            .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
            .excludedEncodeCodecFamilies(excludedFamilies)
            .build();
    excludedFamilies.clear();

    var decision = service.decide(request);

    assertThat(decision.map(TranscodeDecision::videoCodecFamily)).isEqualTo(expectedFamily);
  }

  static Stream<Arguments> excludedEncodeFamilies() {
    return Stream.of(
        Arguments.of("AV1 tried, H.264 next", Set.of("av1"), Optional.of("h264")),
        Arguments.of(
            "AV1 and H.264 tried, no encode left", Set.of("av1", "h264"), Optional.empty()));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("keyframesThatRuleOutAStreamCopy")
  @DisplayName("Should count a refused stream copy by its reason when the keyframes rule it out")
  void shouldCountARefusedStreamCopyByItsReasonWhenTheKeyframesRuleItOut(
      KeyframeKnowledge keyframes, String reason) {
    var source =
        probeBuilder("h264", "aac").duration(Duration.ofSeconds(20)).keyframes(keyframes).build();

    service
        .decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaring(H264))
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .build())
        .orElseThrow();

    assertThat(meterRegistry.get(STREAM_COPY_REFUSALS).counters())
        .singleElement()
        .satisfies(
            counter -> {
              assertThat(counter.getId().getTag("reason")).isEqualTo(reason);
              assertThat(counter.count()).isEqualTo(1.0);
            });
  }

  // Each source lasts 20 s, so the playlist advertises segments at 0, 6, 12 and 18 s.
  static Stream<Arguments> keyframesThatRuleOutAStreamCopy() {
    return Stream.of(
        Arguments.of(new KeyframeKnowledge.NotReported(), "keyframes_not_reported"),
        Arguments.of(
            new KeyframeKnowledge.PacketScanRequired(KeyframePacketScanReason.NO_CONTAINER_INDEX),
            "packet_scan_required"),
        Arguments.of(
            new KeyframeKnowledge.Unavailable(KeyframesUnavailableReason.NO_KEYFRAMES),
            "keyframes_unavailable"),
        Arguments.of(millisecondKeyframes(-4_000, -2_000), "no_opening_keyframe"),
        Arguments.of(
            millisecondKeyframes(0, 7_000, 6_500, 12_000, 18_000), "presentation_time_regressed"),
        Arguments.of(
            new KeyframeKnowledge.Known(
                KeyframePositions.builder()
                    .streamIndex(0)
                    .timeBaseNumerator(1001)
                    .timeBaseDenominator(24_000)
                    .source(KeyframePositionSource.PACKET_SCAN)
                    .keyframes(List.of(new KeyframePosition(Long.MAX_VALUE / 1000, 0)))
                    .build()),
            "presentation_time_out_of_range"),
        Arguments.of(
            new KeyframeKnowledge.Known(
                KeyframePositions.builder()
                    .streamIndex(0)
                    .timeBaseNumerator(1001)
                    .timeBaseDenominator(24_000)
                    .source(KeyframePositionSource.PACKET_SCAN)
                    .keyframes(List.of(new KeyframePosition(0, Long.MIN_VALUE / 1000)))
                    .build()),
            "decode_time_out_of_range"));
  }

  @Test
  @DisplayName("Should count no refusal when the keyframes allow a stream copy")
  void shouldCountNoRefusalWhenTheKeyframesAllowAStreamCopy() {
    var source =
        probeBuilder("h264", "aac")
            .duration(Duration.ofSeconds(20))
            .keyframes(millisecondKeyframes(0, 6_000, 12_000, 18_000))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(meterRegistry.find(STREAM_COPY_REFUSALS).counters()).isEmpty();
  }

  @Test
  @DisplayName("Should count no refusal when the client cannot play the source's video codec")
  void shouldCountNoRefusalWhenTheClientCannotPlayTheSourcesVideoCodec() {
    var source = h264Probe(new KeyframeKnowledge.NotReported(), "aac");

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(AV1))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(meterRegistry.find(STREAM_COPY_REFUSALS).counters()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {6, 3})
  @DisplayName("Should copy the video when a slot of the source holds no keyframe")
  void shouldCopyTheVideoWhenASlotOfTheSourceHoldsNoKeyframe(int segmentSeconds) {
    var properties =
        StreamingProperties.builder()
            .targetSegmentDuration(Duration.ofSeconds(segmentSeconds))
            .build();
    var source = h264Probe(KEYFRAMES_LEAVING_A_SLOT_EMPTY, "aac");

    var decision =
        new TranscodeDecisionService(properties, meterRegistry)
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(meterRegistry.find(STREAM_COPY_REFUSALS).counters()).isEmpty();
  }

  // Keyframes at 0, 10, 11 and 20 s put the keyframes at 10 and 11 s in one 6 s slot, so segment 1
  // holds their two intervals: 48 video samples, whose framing (864 bytes) and heap cost (6,144
  // bytes) leave 134,210,720 bytes of the 128 MiB segment cap for their samples.
  @ParameterizedTest
  @CsvSource({"67105360, REMUX, 0", "67105361, VIDEO_TRANSCODE, 1"})
  @DisplayName(
      "Should encode the video from the start when a measured merged segment would exceed the"
          + " segment cap")
  void shouldEncodeTheVideoFromTheStartWhenAMeasuredMergedSegmentWouldExceedTheSegmentCap(
      long secondIntervalBytes, TranscodeMode expectedMode, double expectedRefusals) {
    var source =
        StreamMeasurementFixture.CopySource.builder()
            .duration(Duration.ofSeconds(26))
            .keyframeMillis(List.of(0L, 10_000L, 11_000L, 20_000L))
            .videoBytes(List.of(1_000L, 67_105_360L, secondIntervalBytes, 1_000L))
            .build()
            .probeFrom(probeBuilder("h264", null));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(expectedMode);
    assertThat(meterRegistry.counter(STREAM_COPY_REFUSALS, "reason", "exceeds_segment_cap").count())
        .isEqualTo(expectedRefusals);
  }

  // FFmpeg corrects the start time of a source whose demuxer allows timestamp discontinuities, so a
  // copy places its keyframes at their stored ticks only with the probe's start time correction,
  // which no stored probe outcome carries yet (ADR 0037, segment list). MP4, MOV, Matroska and AVI
  // demuxers allow none.
  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "mpegts, VIDEO_TRANSCODE, 1",
    "mpeg, VIDEO_TRANSCODE, 1",
    "ogg, VIDEO_TRANSCODE, 1",
    "'mov,mp4,m4a,3gp,3g2,mj2', REMUX, 0",
    "'matroska,webm', REMUX, 0",
    "avi, REMUX, 0"
  })
  @DisplayName(
      "Should encode the video when the source's demuxer allows timestamp discontinuities and the"
          + " probe stored no start time correction")
  void shouldEncodeTheVideoWhenTheSourcesDemuxerAllowsTimestampDiscontinuities(
      String containerFormat, TranscodeMode expectedMode, double expectedRefusals) {
    var source = probeBuilder("h264", null).containerFormat(Optional.of(containerFormat)).build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(expectedMode);
    assertThat(
            meterRegistry
                .counter(STREAM_COPY_REFUSALS, "reason", "no_start_time_correction")
                .count())
        .isEqualTo(expectedRefusals);
  }

  @Test
  @DisplayName(
      "Should give the missing start time correction as the reason when an MPEG-TS source's video"
          + " is encoded")
  void shouldGiveTheMissingStartTimeCorrectionAsTheReasonWhenAnMpegTsSourcesVideoIsEncoded() {
    var source = probeBuilder("h264", null).containerFormat(Optional.of("mpegts")).build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.reasons()).containsExactly(DecisionReason.START_TIME_CORRECTION_UNKNOWN);
  }

  // The copied AC-3 track's frames join each segment's video: segment 0's 200 audio frames add
  // 1,000,000 bytes, their framing and their heap cost to a video segment that fits alone.
  @Test
  @DisplayName(
      "Should count the copied audio with the video when a measured segment would exceed the"
          + " segment cap")
  void shouldCountTheCopiedAudioWithTheVideoWhenAMeasuredSegmentWouldExceedTheSegmentCap() {
    var source =
        StreamMeasurementFixture.CopySource.builder()
            .duration(Duration.ofSeconds(12))
            .keyframeMillis(List.of(0L, 6_000L))
            .videoBytes(List.of(133_500_000L, 1_000L))
            .audioBytes(List.of(1_000_000L, 1_000L))
            .audioPackets(List.of(200L, 10L))
            .build()
            .probeFrom(probeBuilder("h264", "ac3"));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaringAudio(audio(AAC, 6), audio(AC3, 6)))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.reasons()).containsExactly(DecisionReason.SEGMENT_EXCEEDS_CAP);
  }

  // Segment 0's video fits the cap alone with 50,000 bytes to spare. The FLAC track is encoded to
  // AAC, and 6 s of its frames at 1.2 times their bitrate, with their framing and heap cost, take
  // more than that (ADR 0054).
  @Test
  @DisplayName(
      "Should count the encoded audio with the video when a measured segment would exceed the"
          + " segment cap")
  void shouldCountTheEncodedAudioWithTheVideoWhenAMeasuredSegmentWouldExceedTheSegmentCap() {
    var source =
        StreamMeasurementFixture.CopySource.builder()
            .duration(Duration.ofSeconds(12))
            .keyframeMillis(List.of(0L, 6_000L))
            .videoBytes(List.of(134_164_176L, 1_000L))
            .build()
            .probeFrom(probeBuilder("h264", "flac"));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.FULL_TRANSCODE);
    assertThat(meterRegistry.counter(STREAM_COPY_REFUSALS, "reason", "exceeds_segment_cap").count())
        .isEqualTo(1.0);
  }

  // The keyframe at 12.5 s presents less than 2 s after segment 1's start at 11 s, so it opens no
  // segment, and segment 1 runs from 11 s to 17 s across the slots that start at 6 s and at 12 s.
  // The worker takes each segment's audio from its exact start to the next one's: segment 1 also
  // holds the copied AC-3 interval from 12.5 s to 17 s, whose 1,000,000 bytes in 200 frames join a
  // video segment that fits alone.
  @Test
  @DisplayName(
      "Should count the copied audio up to the next segment's exact start when a keyframe that"
          + " opens no segment lies past the next segment's slot start")
  void shouldCountTheCopiedAudioUpToTheNextSegmentsExactStartWhenAKeyframeOpensNoSegment() {
    var source =
        StreamMeasurementFixture.CopySource.builder()
            .duration(Duration.ofSeconds(30))
            .keyframeMillis(KEYFRAMES_SKIPPING_ONE_TOO_SOON_AFTER_A_START)
            .videoBytes(List.of(1_000L, 1_000L, 133_500_000L, 1_000L, 1_000L))
            .audioBytes(List.of(1_000L, 1_000L, 1_000_000L, 1_000L, 1_000L))
            .audioPackets(List.of(10L, 10L, 200L, 10L, 10L))
            .build()
            .probeFrom(probeBuilder("h264", "ac3"));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaringAudio(audio(AAC, 6), audio(AC3, 6)))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.reasons()).containsExactly(DecisionReason.SEGMENT_EXCEEDS_CAP);
  }

  // Segment 0 runs from zero to the keyframe at 11 s, so the FLAC track encoded to AAC fills 11 s
  // of its interval, not the 6 s of its first slot. Its video, with framing and heap cost, leaves
  // 200,000 bytes of the cap: more than 6 s of the encoded frames at 1.2 times their bitrate take,
  // and less than 11 s take (ADR 0054).
  @Test
  @DisplayName(
      "Should count the encoded audio up to the next segment's exact start when that start lies"
          + " past the next slot")
  void shouldCountTheEncodedAudioUpToTheNextSegmentsExactStartWhenThatStartLiesPastTheNextSlot() {
    var source =
        StreamMeasurementFixture.CopySource.builder()
            .duration(Duration.ofSeconds(30))
            .keyframeMillis(KEYFRAMES_SKIPPING_ONE_TOO_SOON_AFTER_A_START)
            .videoBytes(List.of(134_014_176L, 1_000L, 1_000L, 1_000L, 1_000L))
            .build()
            .probeFrom(probeBuilder("h264", "flac"));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.FULL_TRANSCODE);
    assertThat(meterRegistry.counter(STREAM_COPY_REFUSALS, "reason", "exceeds_segment_cap").count())
        .isEqualTo(1.0);
  }

  // One measured interval does not follow the source's two keyframes, so the server cannot size
  // the copy's segments. The worker's own check then bounds them (ADR 0037).
  @Test
  @DisplayName(
      "Should copy the video when the measured video's intervals do not follow the keyframes")
  void shouldCopyTheVideoWhenTheMeasuredVideosIntervalsDoNotFollowTheKeyframes() {
    var source =
        StreamMeasurementFixture.CopySource.builder()
            .duration(Duration.ofSeconds(12))
            .keyframeMillis(List.of(0L, 6_000L))
            .videoBytes(List.of(200_000_000L))
            .build()
            .probeFrom(probeBuilder("h264", null));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  @Test
  @DisplayName("Should copy the video when an unmeasured segment's estimate equals the segment cap")
  void shouldCopyTheVideoWhenAnUnmeasuredSegmentsEstimateEqualsTheSegmentCap() {
    var source =
        unmeasuredSourceWithA64SecondSegment(null).bitrate(BITRATE_AT_THE_SEGMENT_CAP).build();

    var decision = decideForAnH264Client(source);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(meterRegistry.find(STREAM_COPY_REFUSALS).counters()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should encode the video from the start when an unmeasured segment's estimate exceeds the"
          + " segment cap")
  void shouldEncodeTheVideoFromTheStartWhenAnUnmeasuredSegmentsEstimateExceedsTheSegmentCap() {
    var source =
        unmeasuredSourceWithA64SecondSegment(null).bitrate(BITRATE_AT_THE_SEGMENT_CAP + 1).build();

    var decision = decideForAnH264Client(source);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.reasons()).containsExactly(DecisionReason.SEGMENT_EXCEEDS_CAP);
    assertThat(
            meterRegistry
                .counter(STREAM_COPY_REFUSALS, "reason", "estimate_exceeds_segment_cap")
                .count())
        .isEqualTo(1.0);
  }

  // The FLAC track is encoded to stereo AAC at 128,000 bit/s, which the container bitrate does not
  // hold, so the unmeasured cost adds it (ADR 0054). The two come to one bit per second over the
  // bitrate at which the 64 s segment's estimate equals the segment cap.
  @Test
  @DisplayName(
      "Should count the encoded audio's bitrate when an unmeasured segment's estimate exceeds the"
          + " segment cap")
  void shouldCountTheEncodedAudiosBitrateWhenAnUnmeasuredSegmentsEstimateExceedsTheSegmentCap() {
    var source =
        unmeasuredSourceWithA64SecondSegment("flac")
            .bitrate(BITRATE_AT_THE_SEGMENT_CAP + 1 - 128_000L)
            .build();

    var decision = decideForAnH264Client(source);

    assertThat(decision.audioDecision().bitrate()).isEqualTo(128_000L);
    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.FULL_TRANSCODE);
    assertThat(decision.reasons()).contains(DecisionReason.SEGMENT_EXCEEDS_CAP);
  }

  // The measurement sizes both segments at 1,000 bytes of video, so the container bitrate, at
  // which the 64 s segment's estimate would exceed the segment cap, decides nothing.
  @Test
  @DisplayName(
      "Should copy the video when its measured segments fit the segment cap and the container"
          + " bitrate would exceed it")
  void
      shouldCopyTheVideoWhenItsMeasuredSegmentsFitTheSegmentCapAndTheContainerBitrateWouldExceedIt() {
    var source =
        StreamMeasurementFixture.CopySource.builder()
            .duration(Duration.ofSeconds(74))
            .keyframeMillis(List.of(0L, 10_000L))
            .videoBytes(List.of(1_000L, 1_000L))
            .build()
            .probeFrom(probeBuilder("h264", null).bitrate(BITRATE_AT_THE_SEGMENT_CAP + 1));

    var decision = decideForAnH264Client(source);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  // The probe knows neither the copied video's bitrate nor the container bitrate, so the file's
  // size over its 74 s duration stands in for the container bitrate. One byte over 77,594,624
  // bytes rounds that rate up to 8,388,609 bit/s.
  @Test
  @DisplayName(
      "Should encode the video from the start when the probe knows no bitrate and the file's size"
          + " puts a segment's estimate over the segment cap")
  void
      shouldEncodeTheVideoFromTheStartWhenTheProbeKnowsNoBitrateAndTheFilesSizePutsAnEstimateOverTheSegmentCap() {
    var source =
        unmeasuredSourceWithA64SecondSegment(null)
            .bitrate(0)
            .sourceSnapshot(Optional.of(snapshotOf(FILE_BYTES_AT_THE_SEGMENT_CAP + 1)))
            .build();

    var decision = decideForAnH264Client(source);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(
            meterRegistry
                .counter(STREAM_COPY_REFUSALS, "reason", "estimate_exceeds_segment_cap")
                .count())
        .isEqualTo(1.0);
  }

  // 77,594,624 bytes over 74 s is 8,388,608 bit/s, at which the 64 s segment's estimate equals the
  // segment cap.
  @Test
  @DisplayName(
      "Should copy the video when the probe knows no bitrate and the file's size puts a segment's"
          + " estimate at the segment cap")
  void shouldCopyTheVideoWhenTheProbeKnowsNoBitrateAndTheFilesSizePutsAnEstimateAtTheSegmentCap() {
    var source =
        unmeasuredSourceWithA64SecondSegment(null)
            .bitrate(0)
            .sourceSnapshot(Optional.of(snapshotOf(FILE_BYTES_AT_THE_SEGMENT_CAP)))
            .build();

    var decision = decideForAnH264Client(source);

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  private static SourceFileSnapshot snapshotOf(long size) {
    return new SourceFileSnapshot(size, Instant.EPOCH);
  }

  /**
   * A 74 s source without a measurement whose keyframes at 0 and 10 s open a 10 s segment and a 64
   * s segment. The caller sets the container bitrate.
   */
  private MediaProbe.MediaProbeBuilder unmeasuredSourceWithA64SecondSegment(String audioCodec) {
    return probeBuilder("h264", audioCodec)
        .duration(Duration.ofSeconds(74))
        .keyframes(millisecondKeyframes(0, 10_000));
  }

  private TranscodeDecision decideForAnH264Client(MediaProbe source) {
    return service
        .decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaring(H264))
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .build())
        .orElseThrow();
  }

  @Test
  @DisplayName(
      "Should encode both streams when the audio needs encoding and the keyframes are not reported")
  void shouldEncodeBothStreamsWhenTheAudioNeedsEncodingAndTheKeyframesAreNotReported() {
    var source = h264Probe(new KeyframeKnowledge.NotReported(), "flac");

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.FULL_TRANSCODE);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
  }

  @Test
  @DisplayName(
      "Should encode with the preferred codec when the source's keyframes rule out a stream copy")
  void shouldEncodeWithThePreferredCodecWhenTheSourcesKeyframesRuleOutAStreamCopy() {
    var source = h264Probe(new KeyframeKnowledge.NotReported(), "aac");

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(AV1, H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
  }

  @Test
  @DisplayName("Should copy the video when no keyframe opens the final partial segment")
  void shouldCopyTheVideoWhenNoKeyframeOpensTheFinalPartialSegment() {
    // The grid interval [18 s, 18.5 s) holds no keyframe. It joins the copy segment that the
    // keyframe at 12 s opens, which runs to 18.5 s.
    var source =
        probeBuilder("h264", "aac")
            .duration(Duration.ofMillis(18_500))
            .keyframes(ProbeFixture.keyframesEvery(Duration.ofSeconds(6), Duration.ofSeconds(12)))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(meterRegistry.find(STREAM_COPY_REFUSALS).counters()).isEmpty();
  }

  @Test
  @DisplayName("Should copy the video when the two final slots hold no keyframe")
  void shouldCopyTheVideoWhenTheTwoFinalSlotsHoldNoKeyframe() {
    // Over 20 s, neither [12 s, 18 s) nor [18 s, 20 s) holds a keyframe. Both merge into the
    // segment that the keyframe at 6 s opens, which runs 14 s: no duration bound limits it.
    var source =
        probeBuilder("h264", "aac")
            .duration(Duration.ofSeconds(20))
            .keyframes(millisecondKeyframes(0, 6_000))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  @Test
  @DisplayName("Should encode the video when no keyframe presents before the media end")
  void shouldEncodeTheVideoWhenNoKeyframePresentsBeforeTheMediaEnd() {
    var source =
        probeBuilder("h264", "aac")
            .duration(Duration.ofMillis(400))
            .keyframes(millisecondKeyframes(500))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(
            meterRegistry
                .get(STREAM_COPY_REFUSALS)
                .tag("reason", "no_opening_keyframe")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("Should copy the video when a keyframe opens the final partial segment")
  void shouldCopyTheVideoWhenAKeyframeOpensTheFinalPartialSegment() {
    var source =
        probeBuilder("h264", "aac")
            .duration(Duration.ofMillis(18_500))
            .keyframes(ProbeFixture.keyframesEvery(Duration.ofSeconds(6), Duration.ofSeconds(18)))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  @ParameterizedTest
  @MethodSource("keyframesThatAreNotKnown")
  @DisplayName("Should encode the video when the source's keyframe positions are not known")
  void shouldEncodeTheVideoWhenTheSourcesKeyframePositionsAreNotKnown(KeyframeKnowledge keyframes) {
    var source = h264Probe(keyframes, "aac");

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  static Stream<KeyframeKnowledge> keyframesThatAreNotKnown() {
    return ProbeFixture.keyframesThatAreNotKnown();
  }

  @Test
  @DisplayName("Should copy the video when every segment interval holds a keyframe")
  void shouldCopyTheVideoWhenEverySegmentIntervalHoldsAKeyframe() {
    var source =
        h264Probe(
            ProbeFixture.keyframesEvery(Duration.ofMillis(5_005), Duration.ofMinutes(120)), "aac");

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  // Positions in a millisecond time base, in decode order, each decoded 1 ms after the one before.
  private static KeyframeKnowledge millisecondKeyframes(long... presentationTimes) {
    var keyframes = new ArrayList<KeyframePosition>();
    for (var index = 0; index < presentationTimes.length; index++) {
      keyframes.add(new KeyframePosition(presentationTimes[index], index));
    }

    return new KeyframeKnowledge.Known(
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1)
            .timeBaseDenominator(1_000)
            .source(KeyframePositionSource.PACKET_SCAN)
            .keyframes(keyframes)
            .build());
  }

  private MediaProbe h264Probe(KeyframeKnowledge keyframes, String audioCodec) {
    return probeBuilder("h264", audioCodec).keyframes(keyframes).build();
  }

  private MediaProbe probe(String videoCodec, String audioCodec) {
    return probeBuilder(videoCodec, audioCodec).build();
  }

  private MediaProbe.MediaProbeBuilder probeBuilder(String videoCodec, String audioCodec) {
    return MediaProbe.builder()
        .keyframes(KEYFRAMES_EVERY_TWO_SECONDS)
        .duration(Duration.ofMinutes(120))
        .framerate(OptionalDouble.of(23.976))
        .width(1920)
        .height(1080)
        .videoCodec(videoCodec)
        .audioCodec(audioCodec)
        .streams(
            audioCodec == null
                ? List.of()
                : List.of(ProbeFixture.audioStreamBuilder(audioCodec).index(1).build()))
        .bitrate(5_000_000L)
        .audioChannels(OptionalInt.of(2))
        .audioBitrate(OptionalLong.of(128_000L))
        .videoPlaybackFacts(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .streamIndex(0)
                    .codecString(Optional.ofNullable(SDR_CODEC_STRINGS.get(videoCodec)))
                    .build()));
  }

  // The codec string that the probe stores for an 8-bit SDR source of each codec.
  private static final Map<String, String> SDR_CODEC_STRINGS =
      Map.of("h264", "avc1.640028", "hevc", "hvc1.1.6.L120.90", "av1", "av01.0.08M.08");

  private static ClientCapabilityDeclaration declaring(ProbedCodecFamily... codecFamilies) {
    return declarationBuilderFor(codecFamilies).build();
  }

  private static ClientCapabilityDeclaration declaringAudio(AudioCapabilityEntry... entries) {
    return defaultDeclarationBuilder().audio(List.of(entries)).build();
  }

  private static AudioCapabilityEntry audio(AudioCodec codec, int maxChannels) {
    return audioEntryBuilder(codec).maxChannels(maxChannels).build();
  }

  // --- Existing tests (adapted) ---

  @Test
  @DisplayName("Should remux when video codec matches and audio is AAC")
  void shouldRemuxWhenVideoCodecMatchesAndAudioIsAac() {
    var source = probe("h264", "aac");
    var declaration = declaring(H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName("Should audio transcode when video matches but audio needs conversion")
  void shouldAudioTranscodeWhenVideoMatchesButAudioNeedsConversion() {
    var source =
        probeBuilder("h264", "flac")
            .audioChannels(OptionalInt.of(2))
            .audioBitrate(OptionalLong.empty())
            .build();
    var declaration = declaring(H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName("Should video transcode when video incompatible but audio is compatible")
  void shouldVideoTranscodeWhenVideoIncompatibleButAudioIsCompatible() {
    var source = probe("hevc", "aac");
    var declaration = declaring(H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @ParameterizedTest(name = "hardware encoders for {0}")
  @ArgumentsSource(HardwareEncodedFamiliesAndExpectedEncode.class)
  @DisplayName(
      "Should prefer H.264 over AV1 unless a worker that can run the encode encodes AV1 in hardware"
          + " when the client declares both")
  void shouldPreferH264OverAv1UnlessAWorkerEncodesAv1InHardwareWhenTheClientDeclaresBoth(
      Set<String> hardwareEncodedFamilies, String expectedCodecFamily) {
    var source = probe("hevc", "aac");
    var declaration = declaring(AV1, H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .hardwareEncodableVideoCodecFamilies(hardwareEncodedFamilies)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo(expectedCodecFamily);
  }

  // ADR 0044: the software continuation keeps the codec that the client already plays.
  @Test
  @DisplayName(
      "Should encode the named codec family with the software encoder when a hardware encoder"
          + " failed, even where a worker encodes another family in hardware")
  void shouldEncodeTheNamedFamilyInSoftwareWhenAHardwareEncoderFailed() {
    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(probe("hevc", "aac"))
                    .capabilityDeclaration(declaring(AV1, H264))
                    .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .hardwareEncodableVideoCodecFamilies(Set.of("av1"))
                    .videoEncoders(List.of(libx264()))
                    .softwareContinuation(
                        Optional.of(new SoftwareContinuation("h264", EncodeFormat.SDR_8_BIT)))
                    .build())
            .orElseThrow();

    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.softwareEncoder()).isTrue();
  }

  @Test
  @DisplayName("Should decide nothing when no worker encodes the software continuation's family")
  void shouldDecideNothingWhenNoWorkerEncodesTheSoftwareContinuationsFamily() {
    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(probe("hevc", "aac"))
                .capabilityDeclaration(declaring(AV1, H264))
                .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
                .encodableVideoCodecFamilies(Set.of("av1"))
                .softwareContinuation(
                    Optional.of(new SoftwareContinuation("h264", EncodeFormat.SDR_8_BIT)))
                .build());

    assertThat(decision).isEmpty();
  }

  @Test
  @DisplayName("Should encode with the offered encoder when no hardware encoder failed")
  void shouldEncodeWithTheOfferedEncoderWhenNoHardwareEncoderFailed() {
    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(probe("hevc", "aac"))
                    .capabilityDeclaration(declaring(AV1, H264))
                    .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.softwareEncoder()).isFalse();
  }

  // A codec family whose hardware encoder failed earlier in the playback session encodes on its
  // software encoder, so it no longer comes first as a codec family encoded in hardware (ADR 0009).
  @Test
  @DisplayName(
      "Should encode H.264 before a codec family when its hardware encoder failed earlier in the"
          + " playback session")
  void shouldEncodeH264BeforeACodecFamilyWhenItsHardwareEncoderFailedEarlier() {
    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(probe("hevc", "aac"))
                    .capabilityDeclaration(declaring(AV1, H264))
                    .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .hardwareEncodableVideoCodecFamilies(Set.of("av1"))
                    .excludedHardwareEncodeCodecFamilies(Set.of("av1"))
                    .videoEncoders(List.of(libsvtav1()))
                    .build())
            .orElseThrow();

    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
    assertThat(decision.softwareEncoder()).isFalse();
  }

  @Test
  @DisplayName(
      "Should encode a codec family with the software encoder when its hardware encoder failed"
          + " earlier in the playback session")
  void shouldEncodeACodecFamilyWithTheSoftwareEncoderWhenItsHardwareEncoderFailedEarlier() {
    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(probe("hevc", "aac"))
                    .capabilityDeclaration(declaring(AV1))
                    .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .hardwareEncodableVideoCodecFamilies(Set.of("av1"))
                    .excludedHardwareEncodeCodecFamilies(Set.of("av1"))
                    .videoEncoders(List.of(libsvtav1()))
                    .build())
            .orElseThrow();

    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
    assertThat(decision.softwareEncoder()).isTrue();
  }

  // A worker runs a software encode only on a software encoder of its codec family (ADR 0044), so
  // without one the codec family has no encode, not even in 8-bit SDR.
  @Test
  @DisplayName(
      "Should decide nothing when a codec family's hardware encoder failed earlier in the playback"
          + " session and no software encoder of the family is listed")
  void
      shouldDecideNothingWhenNoSoftwareEncoderIsListedForAFamilyWhoseHardwareEncoderFailedEarlier() {
    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(probe("hevc", "aac"))
                .capabilityDeclaration(declaring(AV1))
                .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .hardwareEncodableVideoCodecFamilies(Set.of("av1"))
                .excludedHardwareEncodeCodecFamilies(Set.of("av1"))
                .videoEncoders(List.of(libx264()))
                .build());

    assertThat(decision).isEmpty();
  }

  // ADR 0013: a software continuation keeps the failed encode's range and bit depth, so the
  // decision plans that format alone and nothing when no software encoder writes it.
  @Test
  @DisplayName(
      "Should decide nothing when no software encoder writes the range of the PQ encode that a"
          + " software continuation continues")
  void shouldDecideNothingWhenNoSoftwareEncoderWritesTheSoftwareContinuationsRange() {
    var decision =
        service.decide(
            softwareContinuationOfA10BitSource(
                    CompatibleBase.PQ,
                    EncodeFormat.hdr(VideoRange.PQ),
                    Set.of(VideoEncodeFeature.TEN_BIT))
                .build());

    assertThat(decision).isEmpty();
  }

  @Test
  @DisplayName(
      "Should plan PQ at 10 bits with the software encoder when it writes the range of the PQ"
          + " encode that a software continuation continues")
  void shouldPlanPqAt10BitsWhenTheSoftwareEncoderWritesTheSoftwareContinuationsRange() {
    var decision =
        service
            .decide(
                softwareContinuationOfA10BitSource(
                        CompatibleBase.PQ,
                        EncodeFormat.hdr(VideoRange.PQ),
                        Set.of(VideoEncodeFeature.TEN_BIT, VideoEncodeFeature.PQ))
                    .build())
            .orElseThrow();

    assertThat(decision.videoEncodeFormat()).isEqualTo(EncodeFormat.hdr(VideoRange.PQ));
    assertThat(decision.softwareEncoder()).isTrue();
  }

  @Test
  @DisplayName(
      "Should plan 8-bit SDR when a software continuation continues an 8-bit SDR encode of an"
          + " HDR10 source and the software encoder writes PQ")
  void shouldPlan8BitSdrWhenASoftwareContinuationContinuesAnSdrEncodeAndTheSoftwareWritesPq() {
    var decision =
        service
            .decide(
                softwareContinuationOfA10BitSource(
                        CompatibleBase.PQ,
                        EncodeFormat.SDR_8_BIT,
                        Set.of(VideoEncodeFeature.TEN_BIT, VideoEncodeFeature.PQ))
                    .build())
            .orElseThrow();

    assertThat(decision.videoEncodeFormat()).isEqualTo(EncodeFormat.SDR_8_BIT);
  }

  // 8-bit SDR keeps the range of a 10-bit SDR encode (ADR 0013).
  @Test
  @DisplayName(
      "Should plan 8-bit SDR with the software encoder when a software continuation continues a"
          + " 10-bit SDR encode and no software encoder writes 10 bits")
  void shouldPlan8BitSdrWhenASoftwareContinuationContinuesA10BitSdrEncodeWithout10BitSoftware() {
    var decision =
        service
            .decide(
                softwareContinuationOfA10BitSource(
                        CompatibleBase.SDR, EncodeFormat.SDR_10_BIT, Set.of())
                    .build())
            .orElseThrow();

    assertThat(decision.videoEncodeFormat()).isEqualTo(EncodeFormat.SDR_8_BIT);
    assertThat(decision.softwareEncoder()).isTrue();
  }

  // An AV1 encode of a 10-bit HEVC source in the compatible base for a client that declares AV1
  // with SDR and PQ at 10 bits, continued on a software AV1 encoder that lists the encode features.
  private TranscodeDecisionRequest.TranscodeDecisionRequestBuilder
      softwareContinuationOfA10BitSource(
          CompatibleBase sourceBase,
          EncodeFormat failedEncodeFormat,
          Set<VideoEncodeFeature> softwareAv1Features) {
    var tenBit =
        VideoPlaybackFactsFixture.sdrH264FactsBuilder()
            .streamIndex(0)
            .codecFamily(Optional.of(HEVC))
            .bitDepth(OptionalInt.of(10))
            .codecString(Optional.of("hvc1.2.4.L120.90"))
            .dynamicRange(VideoPlaybackFactsFixture.rangeWithoutEnhancement(sourceBase))
            .build();
    var hdrAv1 =
        av1EntryBuilder().compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ)).build();
    var softwareAv1 =
        WorkerVerifiedEncoder.builder()
            .backend(
                EncoderBackend.builder()
                    .codecFamily("av1")
                    .encoder("libsvtav1")
                    .kind(EncoderBackend.Kind.SOFTWARE)
                    .build())
            .keyframeVerified(true)
            .encodeFeatures(softwareAv1Features)
            .build();
    return TranscodeDecisionRequest.builder()
        .source(probeBuilder("hevc", "aac").videoPlaybackFacts(Optional.of(tenBit)).build())
        .capabilityDeclaration(defaultDeclarationBuilder().video(List.of(hdrAv1)).build())
        .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
        .encodableVideoCodecFamilies(Set.of("av1"))
        .videoEncoders(List.of(softwareAv1))
        .softwareContinuation(Optional.of(new SoftwareContinuation("av1", failedEncodeFormat)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("entryFrameRatesHardwareEncodedFamiliesAndExpectedEncode")
  @DisplayName(
      "Should encode the codec family that keeps the higher frame rate before preferring a codec"
          + " family when the families' entries allow different frame rates")
  void
      shouldEncodeTheCodecFamilyThatKeepsTheHigherFrameRateBeforePreferringACodecFamilyWhenTheFamiliesEntriesAllowDifferentRates(
          FamilyFrameRates families) {
    var source =
        probeVersion5H264(
            new PictureSize(1920, 1080),
            VideoPlaybackFactsFixture.declaredRateRule(new Fraction(60, 1)));
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    av1EntryBuilder().maxFrameRate(families.av1MaxFrameRate()).build(),
                    h264EntryBuilder().maxFrameRate(families.h264MaxFrameRate()).build()))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .hardwareEncodableVideoCodecFamilies(families.hardwareEncodedFamilies())
                    .build())
            .orElseThrow();

    assertThat(decision.videoCodecFamily()).isEqualTo(families.encodedFamily());
  }

  // ADR 0049 keeps the higher frame rate first, and ADR 0009's preference breaks a tie: a family
  // that a worker encodes in hardware, then H.264.
  static Stream<FamilyFrameRates> entryFrameRatesHardwareEncodedFamiliesAndExpectedEncode() {
    return Stream.of(
        FamilyFrameRates.builder()
            .av1MaxFrameRate(30)
            .h264MaxFrameRate(60)
            .hardwareEncodedFamilies(Set.of("av1"))
            .encodedFamily("h264")
            .build(),
        FamilyFrameRates.builder()
            .av1MaxFrameRate(60)
            .h264MaxFrameRate(30)
            .hardwareEncodedFamilies(Set.of())
            .encodedFamily("av1")
            .build(),
        FamilyFrameRates.builder()
            .av1MaxFrameRate(30)
            .h264MaxFrameRate(30)
            .hardwareEncodedFamilies(Set.of("av1"))
            .encodedFamily("av1")
            .build(),
        FamilyFrameRates.builder()
            .av1MaxFrameRate(30)
            .h264MaxFrameRate(30)
            .hardwareEncodedFamilies(Set.of())
            .encodedFamily("h264")
            .build());
  }

  @Builder
  record FamilyFrameRates(
      double av1MaxFrameRate,
      double h264MaxFrameRate,
      Set<String> hardwareEncodedFamilies,
      String encodedFamily) {}

  @Test
  @DisplayName(
      "Should encode H.264 when the client prefers AV1 and no worker-verified encoder encodes AV1")
  void shouldEncodeH264WhenClientPrefersAv1AndNoWorkerVerifiedEncoderEncodesAv1() {
    var source = probe("hevc", "aac");
    var declaration = declaring(AV1, H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(Set.of("h264", "hevc"))
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
  }

  @Test
  @DisplayName(
      "Should return no decision when no worker-verified encoder supports the client codecs")
  void shouldReturnNoDecisionWhenNoWorkerVerifiedEncoderSupportsTheClientCodecs() {
    var source = probe("hevc", "aac");
    var declaration = declaring(AV1);

    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaration)
                .encodableVideoCodecFamilies(Set.of())
                .build());

    assertThat(decision).isEmpty();
  }

  @Test
  @DisplayName(
      "Should copy an AV1 source when the client declares AV1 and no worker-verified encoder encodes AV1")
  void shouldCopyAv1SourceWhenClientDeclaresAv1AndNoWorkerVerifiedEncoderEncodesAv1() {
    var source = probe("av1", "aac");
    var declaration = declaring(AV1, H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(Set.of("h264"))
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
    assertThat(decision.encodeCodecProfile()).isEmpty();
  }

  @Test
  @DisplayName("Should remux AV1 source when client supports AV1 and audio is AAC")
  void shouldRemuxAv1SourceWhenClientSupportsAv1AndAudioIsAac() {
    var source = probe("av1", "aac");
    var declaration = declaring(AV1, H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
  }

  @Test
  @DisplayName("Should audio transcode AV1 source when audio is not AAC")
  void shouldAudioTranscodeAv1SourceWhenAudioIsNotAac() {
    var source =
        probeBuilder("av1", "dts")
            .audioChannels(OptionalInt.of(6))
            .audioBitrate(OptionalLong.of(1_500_000L))
            .build();
    var declaration = declaring(AV1);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName("Should return no decision when the client supports no eligible encoding codec")
  void shouldReturnNoDecisionWhenTheClientSupportsNoEligibleEncodingCodec() {
    var source = probe("h264", "aac");
    var declaration = declaring(HEVC);

    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaration)
                .encodableVideoCodecFamilies(Set.of("h264", "av1"))
                .build());

    assertThat(decision).isEmpty();
  }

  // --- Video-only (no audio stream) ---

  @Test
  @DisplayName("Should decide audio mode none when source has no audio stream")
  void shouldDecideAudioModeNoneWhenSourceHasNoAudioStream() {
    var source =
        probeBuilder("h264", null)
            .audioChannels(OptionalInt.empty())
            .audioBitrate(OptionalLong.empty())
            .build();
    var declaration = declaring(H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.NONE);
    assertThat(decision.audioDecision().codec()).isNull();
    assertThat(decision.audioDecision().channels()).isZero();
  }

  // --- Surround sound test scenarios ---

  @Test
  @DisplayName("Should copy AC-3 audio when client supports it")
  void shouldCopyAc3AudioWhenClientSupportsIt() {
    var source =
        probeBuilder("h264", "ac3")
            .audioChannels(OptionalInt.of(6))
            .audioBitrate(OptionalLong.of(384_000L))
            .build();
    var declaration = declaringAudio(audio(AAC, 6), audio(AC3, 6));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("ac3");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should copy E-AC-3 audio when client supports it")
  void shouldCopyEac3AudioWhenClientSupportsIt() {
    var source =
        probeBuilder("h264", "eac3")
            .audioChannels(OptionalInt.of(6))
            .audioBitrate(OptionalLong.of(640_000L))
            .build();
    var declaration = declaringAudio(audio(AAC, 8), audio(EAC3, 8));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("eac3");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should fall back to stereo AAC when the client declares only AAC audio")
  void shouldFallBackToStereoAacWhenTheClientDeclaresOnlyAacAudio() {
    var source =
        probeBuilder("h264", "flac")
            .audioChannels(OptionalInt.of(2))
            .audioBitrate(OptionalLong.empty())
            .build();
    var declaration = declaring(H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should transcode to AC-3 when channel cap forces transcode")
  void shouldTranscodeToAc3WhenChannelCapForcesTranscode() {
    var source =
        probeBuilder("h264", "ac3")
            .audioChannels(OptionalInt.of(8))
            .audioBitrate(OptionalLong.of(640_000L))
            .build();
    var declaration = declaringAudio(audio(AAC, 2), audio(AC3, 6));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("ac3");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should copy FLAC audio when remuxing H264 and the client supports FLAC")
  void shouldCopyFlacAudioWhenRemuxingH264AndClientSupportsFlac() {
    var source =
        probeBuilder("h264", "flac")
            .audioChannels(OptionalInt.of(6))
            .audioBitrate(OptionalLong.empty())
            .build();
    var declaration = declaringAudio(audio(AAC, 6), audio(FLAC, 6));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("flac");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should transcode MP3 audio to AAC when the client declares AAC")
  void shouldTranscodeMp3AudioToAacWhenTheClientDeclaresAac() {
    var source =
        probeBuilder("h264", "mp3")
            .audioChannels(OptionalInt.of(2))
            .audioBitrate(OptionalLong.of(128_000L))
            .build();
    var declaration = declaringAudio(audio(AAC, 2));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
  }

  @Test
  @DisplayName(
      "Should video transcode with audio copy when video incompatible but audio compatible")
  void shouldVideoTranscodeWithAudioCopyWhenVideoIncompatibleButAudioCompatible() {
    var source =
        probeBuilder("hevc", "ac3")
            .audioChannels(OptionalInt.of(6))
            .audioBitrate(OptionalLong.of(384_000L))
            .build();
    var declaration = declaringAudio(audio(AAC, 2), audio(AC3, 6));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("ac3");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should full transcode when both video and audio need transcoding")
  void shouldFullTranscodeWhenBothVideoAndAudioNeedTranscoding() {
    var source =
        probeBuilder("hevc", "flac")
            .audioChannels(OptionalInt.of(6))
            .audioBitrate(OptionalLong.empty())
            .build();
    var declaration = declaringAudio(audio(AAC, 2));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.FULL_TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should copy multichannel AAC when remuxing H264")
  void shouldCopyMultichannelAacWhenRemuxingH264() {
    var source =
        probeBuilder("h264", "aac")
            .audioChannels(OptionalInt.of(6))
            .audioBitrate(OptionalLong.of(384_000L))
            .build();
    var declaration = declaringAudio(audio(AAC, 6));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should transcode to 5.1 E-AC-3 when the surround source has more channels")
  void shouldTranscodeTo51Eac3WhenTheSurroundSourceHasMoreChannels() {
    var source =
        probeBuilder("h264", "dts")
            .audioChannels(OptionalInt.of(8))
            .audioBitrate(OptionalLong.of(640_000L))
            .build();
    var declaration = declaringAudio(audio(EAC3, 8), audio(AAC, 8));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("eac3");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
    assertThat(decision.audioDecision().bitrate()).isEqualTo(768_000L);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fivePointOneEncodeBitrates")
  @DisplayName(
      "Should encode 5.1 audio at the highest bitrate that real media uses for the codec when the"
          + " client declares that codec")
  void
      shouldEncode51AudioAtTheHighestBitrateThatRealMediaUsesForTheCodecWhenTheClientDeclaresThatCodec(
          String scenario,
          ClientCapabilityDeclaration declaration,
          String expectedCodec,
          long expectedBitrate) {
    var source =
        probeBuilder("h264", "dts")
            .audioChannels(OptionalInt.of(6))
            .audioBitrate(OptionalLong.of(1_500_000L))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo(expectedCodec);
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
    assertThat(decision.audioDecision().bitrate()).isEqualTo(expectedBitrate);
  }

  static Stream<Arguments> fivePointOneEncodeBitrates() {
    return Stream.of(
        Arguments.of(
            "E-AC-3 at 768 kb/s", declaringAudio(audio(AAC, 6), audio(EAC3, 6)), "eac3", 768_000L),
        Arguments.of(
            "AC-3 at 640 kb/s", declaringAudio(audio(AAC, 6), audio(AC3, 6)), "ac3", 640_000L),
        Arguments.of("AAC at 384 kb/s", declaringAudio(audio(AAC, 6)), "aac", 384_000L));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"AC3, 640000", "EAC3, 768000"})
  @DisplayName(
      "Should announce the codec's highest 5.1 bitrate when a copied 5.1 track has no probed"
          + " bitrate")
  void shouldAnnounceTheCodecsHighest51BitrateWhenACopied51TrackHasNoProbedBitrate(
      AudioCodec codec, long expectedBitrate) {
    var source =
        probeBuilder("h264", codec.probedName())
            .audioChannels(OptionalInt.of(6))
            .audioBitrate(OptionalLong.empty())
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaringAudio(audio(AAC, 6), audio(codec, 6)))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().bitrate()).isEqualTo(expectedBitrate);
  }

  @Test
  @DisplayName(
      "Should announce at least E-AC-3's 5.1 bitrate when a copied 7.1 E-AC-3 track has no probed"
          + " bitrate")
  void shouldAnnounceAtLeastEac3s51BitrateWhenACopied71Eac3TrackHasNoProbedBitrate() {
    var source =
        probeBuilder("h264", "eac3")
            .audioChannels(OptionalInt.of(8))
            .audioBitrate(OptionalLong.empty())
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaringAudio(audio(AAC, 8), audio(EAC3, 8)))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().channels()).isEqualTo(8);
    assertThat(decision.audioDecision().bitrate()).isEqualTo(768_000L);
  }

  // A copy of more than 8 channels keeps at least the 512 kb/s that a 7.1 copy announces.
  @Test
  @DisplayName(
      "Should announce at least 512 kb/s when a copied track of more than 8 channels has no probed"
          + " bitrate")
  void shouldAnnounceAtLeast512KbpsWhenACopiedTrackOfMoreThan8ChannelsHasNoProbedBitrate() {
    var source =
        probeBuilder("h264", "aac")
            .audioChannels(OptionalInt.of(10))
            .audioBitrate(OptionalLong.empty())
            .build();

    var decision = decideAudio(source, declaringAudio(audio(AAC, 16)));

    assertThat(decision.mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.channels()).isEqualTo(10);
    assertThat(decision.bitrate()).isEqualTo(512_000L);
  }

  @Test
  @DisplayName("Should transcode to multichannel AAC when only AAC is supported")
  void shouldTranscodeToMultichannelAacWhenOnlyAacIsSupported() {
    var source =
        probeBuilder("h264", "dts")
            .audioChannels(OptionalInt.of(6))
            .audioBitrate(OptionalLong.of(1_500_000L))
            .build();
    var declaration = declaringAudio(audio(AAC, 6));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.AUDIO_TRANSCODE);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  // ADR 0050's table: AAC writes 5.0 and 5.1, AC-3 and E-AC-3 write 5.0(side) and 5.1(side).
  // An AAC encode has at most six channels, and a 6.1 track loses its back centre into the
  // surrounds in AAC, AC-3 and E-AC-3. The server's own encode of 3 or 4 channels stays stereo.
  @ParameterizedTest(name = "{0}")
  @MethodSource({"tracksAndTheirEncodes", "sevenPointZeroEncodes"})
  @DisplayName("Should name the encoder's own output layout when the server encodes the track")
  void shouldNameTheEncodersOwnOutputLayoutWhenTheServerEncodesTheTrack(TrackEncode trackEncode) {
    var source =
        probeBuilder("h264", "dts")
            .audioChannels(OptionalInt.of(trackEncode.trackChannels()))
            .audioChannelLayout(trackEncode.trackLayout())
            .audioBitrate(OptionalLong.of(1_500_000L))
            .build();

    var decision = decideAudio(source, trackEncode.declaration());

    assertThat(decision).isEqualTo(trackEncode.encode());
  }

  static Stream<TrackEncode> tracksAndTheirEncodes() {
    return Stream.of(
        TrackEncode.builder()
            .scenario("mono to AAC mono")
            .trackChannels(1)
            .trackLayout(Optional.of("mono"))
            .declaration(declaringAudio(audio(AAC, 2)))
            .encode(encodeIn(ChannelLayout.MONO).codec("aac").bitrate(96_000L).build())
            .build(),
        TrackEncode.builder()
            .scenario("stereo to AAC stereo")
            .trackChannels(2)
            .trackLayout(Optional.of("stereo"))
            .declaration(declaringAudio(audio(AAC, 2)))
            .encode(encodeIn(ChannelLayout.STEREO).codec("aac").bitrate(128_000L).build())
            .build(),
        TrackEncode.builder()
            .scenario("quad to AAC stereo although AAC accepts 8 channels")
            .trackChannels(4)
            .trackLayout(Optional.of("quad"))
            .declaration(declaringAudio(audio(AAC, 8), audio(EAC3, 8)))
            .encode(encodeIn(ChannelLayout.STEREO).codec("aac").bitrate(128_000L).build())
            .build(),
        TrackEncode.builder()
            .scenario("3 channels of an unknown layout to AAC stereo")
            .trackChannels(3)
            .trackLayout(Optional.empty())
            .declaration(declaringAudio(audio(AAC, 8), audio(EAC3, 8)))
            .encode(encodeIn(ChannelLayout.STEREO).codec("aac").bitrate(128_000L).build())
            .build(),
        TrackEncode.builder()
            .scenario("5.0 to AAC 5.0")
            .trackChannels(5)
            .trackLayout(Optional.of("5.0"))
            .declaration(declaringAudio(audio(AAC, 6)))
            .encode(encodeIn(ChannelLayout.FIVE_POINT_ZERO).codec("aac").bitrate(384_000L).build())
            .build(),
        TrackEncode.builder()
            .scenario("4.1 to E-AC-3 5.0(side)")
            .trackChannels(5)
            .trackLayout(Optional.of("4.1"))
            .declaration(declaringAudio(audio(AAC, 2), audio(EAC3, 6)))
            .encode(
                encodeIn(ChannelLayout.FIVE_POINT_ZERO_SIDE)
                    .codec("eac3")
                    .bitrate(768_000L)
                    .build())
            .build(),
        TrackEncode.builder()
            .scenario("5.1(side) to AAC 5.1")
            .trackChannels(6)
            .trackLayout(Optional.of("5.1(side)"))
            .declaration(declaringAudio(audio(AAC, 6)))
            .encode(encodeIn(ChannelLayout.FIVE_POINT_ONE).codec("aac").bitrate(384_000L).build())
            .build(),
        TrackEncode.builder()
            .scenario("5.1 to AC-3 5.1(side)")
            .trackChannels(6)
            .trackLayout(Optional.of("5.1"))
            .declaration(declaringAudio(audio(AAC, 2), audio(AC3, 6)))
            .encode(
                encodeIn(ChannelLayout.FIVE_POINT_ONE_SIDE).codec("ac3").bitrate(640_000L).build())
            .build(),
        TrackEncode.builder()
            .scenario("7.1 to AAC 5.1 although AAC accepts 8 channels")
            .trackChannels(8)
            .trackLayout(Optional.of("7.1"))
            .declaration(declaringAudio(audio(AAC, 8)))
            .encode(encodeIn(ChannelLayout.FIVE_POINT_ONE).codec("aac").bitrate(384_000L).build())
            .build(),
        TrackEncode.builder()
            .scenario("6.1 to AAC 5.1 although AAC accepts 8 channels")
            .trackChannels(7)
            .trackLayout(Optional.of("6.1"))
            .declaration(declaringAudio(audio(AAC, 8)))
            .encode(encodeIn(ChannelLayout.FIVE_POINT_ONE).codec("aac").bitrate(384_000L).build())
            .build(),
        TrackEncode.builder()
            .scenario("6.1 to AC-3 5.1(side)")
            .trackChannels(7)
            .trackLayout(Optional.of("6.1"))
            .declaration(declaringAudio(audio(AAC, 2), audio(AC3, 8)))
            .encode(
                encodeIn(ChannelLayout.FIVE_POINT_ONE_SIDE).codec("ac3").bitrate(640_000L).build())
            .build(),
        TrackEncode.builder()
            .scenario("7.1 to E-AC-3 5.1(side)")
            .trackChannels(8)
            .trackLayout(Optional.of("7.1"))
            .declaration(declaringAudio(audio(AAC, 8), audio(EAC3, 8)))
            .encode(
                encodeIn(ChannelLayout.FIVE_POINT_ONE_SIDE).codec("eac3").bitrate(768_000L).build())
            .build());
  }

  // In AAC, AC-3 and E-AC-3 a 7.0 track encodes in 5.0: its four surrounds fold into one pair, and
  // the encode adds no low-frequency channel that the track lacks. It keeps the codec's 5.1
  // bitrate. A 7-channel track whose layout the probe did not store still encodes in 5.1.
  static Stream<TrackEncode> sevenPointZeroEncodes() {
    var sevenPointZero = TrackEncode.builder().trackChannels(7).trackLayout(Optional.of("7.0"));
    return Stream.of(
        sevenPointZero
            .scenario("7.0 to AAC 5.0")
            .declaration(declaringAudio(audio(AAC, 8)))
            .encode(encodeIn(ChannelLayout.FIVE_POINT_ZERO).codec("aac").bitrate(384_000L).build())
            .build(),
        sevenPointZero
            .scenario("7.0 to AAC 5.0 under an AAC maximum of 6")
            .declaration(declaringAudio(audio(AAC, 6)))
            .encode(encodeIn(ChannelLayout.FIVE_POINT_ZERO).codec("aac").bitrate(384_000L).build())
            .build(),
        sevenPointZero
            .scenario("7.0 to AC-3 5.0(side)")
            .declaration(declaringAudio(audio(AAC, 2), audio(AC3, 8)))
            .encode(
                encodeIn(ChannelLayout.FIVE_POINT_ZERO_SIDE).codec("ac3").bitrate(640_000L).build())
            .build(),
        sevenPointZero
            .scenario("7.0 to E-AC-3 5.0(side)")
            .declaration(declaringAudio(audio(AAC, 2), audio(EAC3, 8)))
            .encode(
                encodeIn(ChannelLayout.FIVE_POINT_ZERO_SIDE)
                    .codec("eac3")
                    .bitrate(768_000L)
                    .build())
            .build(),
        TrackEncode.builder()
            .scenario("7 channels of an unknown layout to AAC 5.1")
            .trackChannels(7)
            .trackLayout(Optional.empty())
            .declaration(declaringAudio(audio(AAC, 8)))
            .encode(encodeIn(ChannelLayout.FIVE_POINT_ONE).codec("aac").bitrate(384_000L).build())
            .build());
  }

  @Builder
  record TrackEncode(
      String scenario,
      int trackChannels,
      Optional<String> trackLayout,
      ClientCapabilityDeclaration declaration,
      AudioDecision encode) {

    @Override
    public String toString() {
      return scenario;
    }
  }

  private AudioDecision decideAudio(MediaProbe source, ClientCapabilityDeclaration declaration) {
    return service
        .decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaration)
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .build())
        .orElseThrow()
        .audioDecision();
  }

  private static AudioDecision.AudioDecisionBuilder encodeIn(ChannelLayout layout) {
    return AudioDecision.builder()
        .mode(AudioMode.TRANSCODE)
        .channels(layout.getChannels())
        .channelLayout(Optional.of(layout));
  }

  @ParameterizedTest(name = "{0} source channels")
  @ValueSource(ints = {3, 4})
  @DisplayName("Should encode stereo AAC when the source has more channels than the client plays")
  void shouldEncodeStereoAacWhenTheSourceHasMoreChannelsThanTheClientPlays(int sourceChannels) {
    var source =
        probeBuilder("h264", "aac")
            .audioChannels(OptionalInt.of(sourceChannels))
            .audioBitrate(OptionalLong.of(256_000L))
            .build();
    var declaration = declaringAudio(audio(AAC, 2));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
  }

  // ADR 0050: a copied AAC 7.1 track stays a copy, although an AAC encode has at most six
  // channels.
  @ParameterizedTest(name = "{0} source channels")
  @ValueSource(ints = {3, 4, 5, 7, 8})
  @DisplayName("Should announce the source's channel count when the client plays every channel")
  void shouldAnnounceTheSourcesChannelCountWhenTheClientPlaysEveryChannel(int sourceChannels) {
    var source =
        probeBuilder("h264", "aac")
            .audioChannels(OptionalInt.of(sourceChannels))
            .audioBitrate(OptionalLong.of(256_000L))
            .build();
    var declaration = declaringAudio(audio(AAC, 8));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().channels()).isEqualTo(sourceChannels);
  }

  @Test
  @DisplayName("Should copy stereo AAC when the client plays stereo AAC")
  void shouldCopyStereoAacWhenTheClientPlaysStereoAac() {
    var source =
        probeBuilder("h264", "aac")
            .audioChannels(OptionalInt.of(2))
            .audioBitrate(OptionalLong.of(128_000L))
            .build();
    var declaration = declaringAudio(audio(AAC, 2));

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
  }

  // --- Capability entries ---

  @Test
  @DisplayName(
      "Should encode the video when the only entry for the source's codec family does not apply to HLS")
  void shouldEncodeTheVideoWhenTheOnlyEntryForTheSourcesCodecFamilyDoesNotApplyToHls() {
    var declaration =
        defaultDeclarationBuilder()
            .video(List.of(h264EntryBuilder().hls(false).build(), av1EntryBuilder().build()))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(probe("h264", "aac"))
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("av1");
    assertThat(decision.encodeCodecProfile()).contains(EncodeCodecProfile.AV1_MAIN);
  }

  @Test
  @DisplayName("Should encode the video when the probe named no video codec")
  void shouldEncodeTheVideoWhenTheProbeNamedNoVideoCodec() {
    var source = probeBuilder("h264", "aac").videoCodec(null).build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaring(H264))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
  }

  @Test
  @DisplayName("Should encode with no codec family when its entry does not apply to HLS")
  void shouldEncodeWithNoCodecFamilyWhenItsEntryDoesNotApplyToHls() {
    var declaration =
        defaultDeclarationBuilder().video(List.of(h264EntryBuilder().hls(false).build())).build();

    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(probe("hevc", "aac"))
                .capabilityDeclaration(declaration)
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .build());

    assertThat(decision).isEmpty();
  }

  // Original copies by the same rule as Auto and encodes when no entry accepts the copy (ADR 0052).
  @ParameterizedTest(name = "{0}")
  @MethodSource("copiesAgainstTwoEntries")
  @DisplayName(
      "Should copy the video only when one entry accepts every property of the copy, never two"
          + " entries together")
  void shouldCopyTheVideoOnlyWhenOneEntryAcceptsEveryPropertyOfTheCopyNeverTwoEntriesTogether(
      String scenario, VideoQuality quality, MediaProbe source, Optional<TranscodeMode> expected) {
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    h264EntryBuilder().maxWidth(3840).maxHeight(2160).maxFrameRate(30).build(),
                    h264EntryBuilder().maxWidth(1920).maxHeight(1080).maxFrameRate(60).build()))
            .build();

    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaration)
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .viewerLimits(limitsIn(quality))
                .build());

    assertThat(decision.map(TranscodeDecision::transcodeMode)).isEqualTo(expected);
  }

  static Stream<Arguments> copiesAgainstTwoEntries() {
    var uhd = new PictureSize(3840, 2160);
    var hd = new PictureSize(1920, 1080);
    var at30 = VideoPlaybackFactsFixture.declaredRateRule(new Fraction(30, 1));
    var at60 = VideoPlaybackFactsFixture.declaredRateRule(new Fraction(60, 1));
    var uhdAt30 = probeVersion5H264(uhd, at30);
    var hdAt60 = probeVersion5H264(hd, at60);
    var uhdAt60 = probeVersion5H264(uhd, at60);
    var copy = Optional.of(TranscodeMode.REMUX);
    var encode = Optional.of(TranscodeMode.VIDEO_TRANSCODE);
    return Stream.of(
        Arguments.of("Auto, 2160p30", VideoQuality.AUTO, uhdAt30, copy),
        Arguments.of("Auto, 1080p60", VideoQuality.AUTO, hdAt60, copy),
        Arguments.of("Auto, 2160p60", VideoQuality.AUTO, uhdAt60, encode),
        Arguments.of("Original, 2160p30", VideoQuality.ORIGINAL, uhdAt30, copy),
        Arguments.of("Original, 2160p60", VideoQuality.ORIGINAL, uhdAt60, encode));
  }

  // Timestamp rounding lets a measured rate sit slightly above the source's standard rate.
  @ParameterizedTest(name = "{0}")
  @MethodSource("sourcesMeasuredWithinHalfAPercentAboveTheEntrysMaximum")
  @DisplayName(
      "Should copy video when its measured frame rate is within half a percent above the entry's"
          + " maximum")
  void shouldCopyVideoWhenItsMeasuredFrameRateIsWithinHalfAPercentAboveTheEntrysMaximum(
      String scenario, FrameRateRule frameRateRule, VideoQuality quality) {
    var outputFrameRate = frameRateRule.outputFrameRate().orElseThrow().toDouble();
    var source = probeVersion5H264(new PictureSize(1920, 1080), frameRateRule);
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    h264EntryBuilder()
                        .maxWidth(1920)
                        .maxHeight(1080)
                        .maxFrameRate(outputFrameRate)
                        .build()))
            .build();

    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaration)
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .viewerLimits(limitsIn(quality))
                .build());

    assertThat(decision.map(TranscodeDecision::transcodeMode)).contains(TranscodeMode.REMUX);
  }

  static Stream<Arguments> sourcesMeasuredWithinHalfAPercentAboveTheEntrysMaximum() {
    var rules =
        Stream.of(
            Map.entry(
                "60.25 fps motion against 60",
                VideoPlaybackFactsFixture.packetScanRule(60.25, new Fraction(60, 1))),
            Map.entry(
                "30.1 fps motion against 30",
                VideoPlaybackFactsFixture.packetScanRule(30.1, new Fraction(30, 1))),
            Map.entry(
                "25.125 fps motion exactly half a percent above 25",
                VideoPlaybackFactsFixture.packetScanRule(25.125, new Fraction(25, 1))),
            Map.entry(
                "50.25 fps motion exactly half a percent above 50",
                VideoPlaybackFactsFixture.packetScanRule(50.25, new Fraction(50, 1))),
            Map.entry(
                "30.15 fps motion exactly half a percent above 30",
                VideoPlaybackFactsFixture.packetScanRule(30.15, new Fraction(30, 1))),
            Map.entry(
                "60.3 fps motion exactly half a percent above 60",
                VideoPlaybackFactsFixture.packetScanRule(60.3, new Fraction(60, 1))));
    return rules.flatMap(
        rule ->
            Stream.of(
                Arguments.of("Auto, " + rule.getKey(), rule.getValue(), VideoQuality.AUTO),
                Arguments.of(
                    "Original, " + rule.getKey(), rule.getValue(), VideoQuality.ORIGINAL)));
  }

  // A declared 29.97 or 59.94 lies just below the exact NTSC rate, 30000/1001 or 60000/1001.
  @ParameterizedTest(name = "{0}")
  @MethodSource("exactNtscRatesAgainstTheirRoundedMaximums")
  @DisplayName(
      "Should copy video at the exact NTSC rate when the entry declares that rate rounded to two"
          + " decimals")
  void shouldCopyVideoAtTheExactNtscRateWhenTheEntryDeclaresThatRateRoundedToTwoDecimals(
      String scenario, Fraction ntscRate, double declaredMaximum) {
    var source =
        probeVersion5H264(
            new PictureSize(1920, 1080), VideoPlaybackFactsFixture.declaredRateRule(ntscRate));
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    h264EntryBuilder()
                        .maxWidth(1920)
                        .maxHeight(1080)
                        .maxFrameRate(declaredMaximum)
                        .build()))
            .build();

    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaration)
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .build());

    assertThat(decision.map(TranscodeDecision::transcodeMode)).contains(TranscodeMode.REMUX);
  }

  static Stream<Arguments> exactNtscRatesAgainstTheirRoundedMaximums() {
    return Stream.of(
        Arguments.of("30000/1001 against 29.97", new Fraction(30_000, 1_001), 29.97),
        Arguments.of("60000/1001 against 59.94", new Fraction(60_000, 1_001), 59.94));
  }

  // ADR 0037's frame-rate rule snaps the rate that the source's frames run at to a listed output
  // frame rate, which only an encode runs at. A copy keeps every source frame at its own rate.
  @ParameterizedTest(name = "{0}")
  @MethodSource("sourcesWhoseFramesRunFasterThanTheirOutputFrameRate")
  @DisplayName(
      "Should not copy video whose frames run faster than the entry's maximum frame rate when its"
          + " output frame rate fits that maximum")
  void
      shouldNotCopyVideoWhoseFramesRunFasterThanTheEntrysMaximumFrameRateWhenItsOutputFrameRateFitsThatMaximum(
          String scenario,
          FrameRateRule frameRateRule,
          VideoQuality quality,
          Optional<TranscodeMode> expected) {
    var outputFrameRate = frameRateRule.outputFrameRate().orElseThrow().toDouble();
    var source = probeVersion5H264(new PictureSize(1920, 1080), frameRateRule);
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    h264EntryBuilder()
                        .maxWidth(1920)
                        .maxHeight(1080)
                        .maxFrameRate(outputFrameRate)
                        .build()))
            .build();

    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaration)
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .viewerLimits(limitsIn(quality))
                .build());

    assertThat(decision.map(TranscodeDecision::transcodeMode)).isEqualTo(expected);
  }

  static Stream<Arguments> sourcesWhoseFramesRunFasterThanTheirOutputFrameRate() {
    var rules =
        Stream.of(
            Map.entry(
                "120 fps motion divided to 60",
                VideoPlaybackFactsFixture.packetScanRule(120, new Fraction(60, 1))),
            Map.entry(
                "37.5 fps motion rounded to 30",
                VideoPlaybackFactsFixture.packetScanRule(37.5, new Fraction(30, 1))),
            Map.entry(
                "50.26 fps motion just over half a percent above 50",
                VideoPlaybackFactsFixture.packetScanRule(50.26, new Fraction(50, 1))),
            Map.entry(
                "120 fps average rate divided to 60 after a scan found fewer than 2 frames",
                VideoPlaybackFactsFixture.declaredRateFallbackRule(
                    FrameRateRuleInputs.builder()
                        .averageRate(Optional.of(new Fraction(120, 1)))
                        .baseRate(Optional.of(new Fraction(120, 1)))
                        .build(),
                    new Fraction(60, 1))));
    return rules.flatMap(
        rule ->
            Stream.of(
                Arguments.of(
                    "Auto, " + rule.getKey(),
                    rule.getValue(),
                    VideoQuality.AUTO,
                    Optional.of(TranscodeMode.VIDEO_TRANSCODE)),
                Arguments.of(
                    "Original, " + rule.getKey(),
                    rule.getValue(),
                    VideoQuality.ORIGINAL,
                    Optional.of(TranscodeMode.VIDEO_TRANSCODE))));
  }

  @Test
  @DisplayName(
      "Should copy a probe version 4 outcome when an HLS entry names its codec family whatever its"
          + " maximums")
  void shouldCopyAProbeVersion4OutcomeWhenAnHlsEntryNamesItsCodecFamilyWhateverItsMaximums() {
    var version4 = probeBuilder("h264", "aac").width(7680).height(4320).build();
    var declaration =
        defaultDeclarationBuilder()
            .video(List.of(h264EntryBuilder().maxWidth(1280).maxHeight(720).build()))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(version4)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
  }

  @Test
  @DisplayName(
      "Should encode a probe version 5 outcome when its picture exceeds the entry's maximum size")
  void shouldEncodeAProbeVersion5OutcomeWhenItsPictureExceedsTheEntrysMaximumSize() {
    var declaration =
        defaultDeclarationBuilder()
            .video(List.of(h264EntryBuilder().maxWidth(1280).maxHeight(720).build()))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(
                        probeVersion5H264(
                            new PictureSize(1920, 1080),
                            VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1))))
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
  }

  // An 8-bit 4:2:0 SDR H.264 High profile source at level 5.1 that probe version 5 described.
  private static MediaProbe probeVersion5H264(PictureSize size, FrameRateRule frameRateRule) {
    return MediaProbe.builder()
        .keyframes(KEYFRAMES_EVERY_TWO_SECONDS)
        .duration(Duration.ofMinutes(120))
        .framerate(OptionalDouble.of(frameRateRule.outputFrameRate().orElseThrow().toDouble()))
        .width(size.width())
        .height(size.height())
        .videoCodec("h264")
        .audioCodec("aac")
        .streams(List.of(ProbeFixture.audioStreamBuilder("aac").index(1).build()))
        .bitrate(5_000_000L)
        .audioChannels(OptionalInt.of(2))
        .videoPlaybackFacts(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .streamIndex(0)
                    .frameRateRule(frameRateRule)
                    .videoProperties(
                        Optional.of(
                            VideoPlaybackFactsFixture.h264HighPropertiesBuilder()
                                .codecLevel(OptionalInt.of(51))
                                .build()))
                    .build()))
        .build();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("entriesWithoutTheEncodeProfile")
  @DisplayName(
      "Should skip an encode codec when its entry does not list the encode's codec profile")
  void shouldSkipAnEncodeCodecWhenItsEntryDoesNotListTheEncodesCodecProfile(
      String skippedFamily, VideoCapabilityEntry withoutEncodeProfile, String expectedFamily) {
    var otherEntry =
        "av1".equals(skippedFamily) ? h264EntryBuilder().build() : av1EntryBuilder().build();
    var declaration =
        defaultDeclarationBuilder().video(List.of(withoutEncodeProfile, otherEntry)).build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(probe("hevc", "aac"))
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .hardwareEncodableVideoCodecFamilies(Set.of(skippedFamily))
                    .build())
            .orElseThrow();

    assertThat(decision.videoCodecFamily()).isEqualTo(expectedFamily);
  }

  static Stream<Arguments> entriesWithoutTheEncodeProfile() {
    return Stream.of(
        Arguments.of(
            "av1",
            av1EntryBuilder()
                .codecProfiles(
                    List.of(
                        VideoCodecProfileCapability.builder()
                            .profile(VideoCodecProfile.AV1_HIGH)
                            .maxLevel(51)
                            .maxTier(Optional.of(VideoCodecTier.MAIN))
                            .build()))
                .build(),
            "h264"),
        Arguments.of(
            "h264",
            h264EntryBuilder()
                .codecProfiles(
                    List.of(
                        VideoCodecProfileCapability.builder()
                            .profile(VideoCodecProfile.H264_MAIN)
                            .maxLevel(52)
                            .build()))
                .build(),
            "av1"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("av1EntriesThatFitNoEncode")
  @DisplayName(
      "Should skip an encode codec when none of its entries fits an encoded variant of the source")
  void shouldSkipAnEncodeCodecWhenNoneOfItsEntriesFitsAnEncodedVariantOfTheSource(
      String scenario, VideoCapabilityEntry av1Entry) {
    var declaration =
        defaultDeclarationBuilder().video(List.of(av1Entry, h264EntryBuilder().build())).build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(probe("hevc", "aac"))
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .hardwareEncodableVideoCodecFamilies(HARDWARE_AV1)
                    .build())
            .orElseThrow();

    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
  }

  static Stream<Arguments> av1EntriesThatFitNoEncode() {
    return Stream.of(
        Arguments.of(
            "an AV1 entry without SDR",
            av1EntryBuilder().compatibleBases(List.of(CompatibleBase.PQ)).build()),
        Arguments.of("an AV1 entry below 8 bits", av1EntryBuilder().maxBitDepth(7).build()),
        Arguments.of(
            "an AV1 entry whose maximum level is below every AV1 level",
            av1EntryBuilder()
                .codecProfiles(
                    List.of(
                        VideoCodecProfileCapability.builder()
                            .profile(VideoCodecProfile.AV1_MAIN)
                            .maxLevel(19)
                            .maxTier(Optional.of(VideoCodecTier.MAIN))
                            .build()))
                .build()));
  }

  @Test
  @DisplayName(
      "Should encode a family whose entries are smaller and slower than the source when it sizes"
          + " and divides the variant to fit")
  void
      shouldEncodeAFamilyWhoseEntriesAreSmallerAndSlowerThanTheSourceWhenItSizesAndDividesTheVariantToFit() {
    var source =
        probeBuilder("hevc", "aac")
            .framerate(OptionalDouble.of(60))
            .videoPlaybackFacts(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .streamIndex(0)
                        .frameRateRule(
                            VideoPlaybackFactsFixture.declaredRateRule(new Fraction(60, 1)))
                        .build()))
            .build();
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(h264EntryBuilder().maxWidth(1280).maxHeight(720).maxFrameRate(30).build()))
            .build();

    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaration)
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .build());

    assertThat(decision).map(TranscodeDecision::videoCodecFamily).contains("h264");
  }

  @ParameterizedTest
  @CsvSource({"0, 1080", "1920, 0", "1920, 1", "1, 3"})
  @DisplayName(
      "Should choose an encode codec by its codec profile when no encoder can encode the source's"
          + " picture at any size")
  void shouldChooseAnEncodeCodecByItsCodecProfileWhenNoEncoderCanEncodeTheSourcesPictureAtAnySize(
      int width, int height) {
    var source = probeBuilder("hevc", "aac").width(width).height(height).build();

    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaring(H264))
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .build());

    assertThat(decision).map(TranscodeDecision::videoCodecFamily).contains("h264");
  }

  @Test
  @DisplayName(
      "Should skip an encode codec when its entries shrink the source below the smallest picture")
  void shouldSkipAnEncodeCodecWhenItsEntriesShrinkTheSourceBelowTheSmallestPicture() {
    var source = probeBuilder("hevc", "aac").width(4096).height(2).build();
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    av1EntryBuilder().maxWidth(1280).maxHeight(720).build(),
                    h264EntryBuilder().maxWidth(4096).build()))
            .build();

    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(source)
                .capabilityDeclaration(declaration)
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .hardwareEncodableVideoCodecFamilies(HARDWARE_AV1)
                .build());

    assertThat(decision).map(TranscodeDecision::videoCodecFamily).contains("h264");
  }

  @Test
  @DisplayName("Should return no decision when no entry of any family fits an encoded variant")
  void shouldReturnNoDecisionWhenNoEntryOfAnyFamilyFitsAnEncodedVariant() {
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    h264EntryBuilder().compatibleBases(List.of(CompatibleBase.PQ)).build(),
                    av1EntryBuilder().maxBitDepth(7).build()))
            .build();

    var decision =
        service.decide(
            TranscodeDecisionRequest.builder()
                .source(probe("hevc", "aac"))
                .capabilityDeclaration(declaration)
                .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                .build());

    assertThat(decision).isEmpty();
  }

  @Test
  @DisplayName(
      "Should skip an encode codec when only an entry that does not apply to HLS lists its codec profile")
  void shouldSkipAnEncodeCodecWhenOnlyAnEntryThatDoesNotApplyToHlsListsItsCodecProfile() {
    var hlsEntryWithoutAv1Main =
        av1EntryBuilder()
            .codecProfiles(
                List.of(
                    VideoCodecProfileCapability.builder()
                        .profile(VideoCodecProfile.AV1_HIGH)
                        .maxLevel(51)
                        .maxTier(Optional.of(VideoCodecTier.MAIN))
                        .build()))
            .build();
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    hlsEntryWithoutAv1Main,
                    av1EntryBuilder().hls(false).build(),
                    h264EntryBuilder().build()))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(probe("hevc", "aac"))
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.videoCodecFamily()).isEqualTo("h264");
  }

  @Test
  @DisplayName("Should encode a 5.1 Opus track when the client declares Opus at two channels")
  void shouldEncodeA51OpusTrackWhenTheClientDeclaresOpusAtTwoChannels() {
    var source =
        probeBuilder("h264", "opus")
            .audioChannels(OptionalInt.of(6))
            .audioBitrate(OptionalLong.of(384_000L))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaringAudio(audio(AAC, 2), audio(OPUS, 2)))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should encode the audio when its codec's only entry does not apply to HLS")
  void shouldEncodeTheAudioWhenItsCodecsOnlyEntryDoesNotApplyToHls() {
    var source =
        probeBuilder("h264", "ac3")
            .audioChannels(OptionalInt.of(6))
            .audioBitrate(OptionalLong.of(384_000L))
            .build();
    var declaration =
        declaringAudio(audio(AAC, 2), audioEntryBuilder(AC3).maxChannels(6).hls(false).build());

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Should copy the audio when any of several entries for its codec takes every channel")
  void shouldCopyTheAudioWhenAnyOfSeveralEntriesForItsCodecTakesEveryChannel() {
    var source =
        probeBuilder("h264", "eac3")
            .audioChannels(OptionalInt.of(6))
            .audioBitrate(OptionalLong.of(640_000L))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(
                        declaringAudio(audio(AAC, 2), audio(EAC3, 6), audio(EAC3, 2)))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName(
      "Should encode multichannel audio with the next codec when the preferred codec's entry is stereo")
  void shouldEncodeMultichannelAudioWithTheNextCodecWhenThePreferredCodecsEntryIsStereo() {
    var source =
        probeBuilder("h264", "dts")
            .audioChannels(OptionalInt.of(8))
            .audioBitrate(OptionalLong.of(1_500_000L))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(
                        declaringAudio(audio(AAC, 2), audio(EAC3, 2), audio(AC3, 6)))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("ac3");
    assertThat(decision.audioDecision().channels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should encode mono AAC when the AAC entry accepts one channel")
  void shouldEncodeMonoAacWhenTheAacEntryAcceptsOneChannel() {
    var source =
        probeBuilder("h264", "dts")
            .audioChannels(OptionalInt.of(6))
            .audioBitrate(OptionalLong.of(1_500_000L))
            .build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaringAudio(audio(AAC, 1)))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(1);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("channelCountsThatAreNotKnown")
  @DisplayName("Should default to stereo when the source reports no channel count")
  void shouldDefaultToStereoWhenTheSourceReportsNoChannelCount(OptionalInt sourceChannels) {
    var source =
        probeBuilder("h264", "aac")
            .audioChannels(sourceChannels)
            .audioBitrate(OptionalLong.empty())
            .build();
    var declaration = declaring(H264);

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.COPY);
    assertThat(decision.audioDecision().channels()).isEqualTo(2);
    assertThat(decision.audioDecision().bitrate()).isEqualTo(128_000L);
  }

  static Stream<OptionalInt> channelCountsThatAreNotKnown() {
    return Stream.of(OptionalInt.empty(), OptionalInt.of(0));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("channelCountsThatAreNotKnown")
  @DisplayName(
      "Should encode mono AAC when the source reports no channel count and the AAC entry accepts one channel")
  void shouldEncodeMonoAacWhenTheSourceReportsNoChannelCountAndTheAacEntryAcceptsOneChannel(
      OptionalInt sourceChannels) {
    var source = probeBuilder("h264", "aac").audioChannels(sourceChannels).build();

    var decision =
        service
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(source)
                    .capabilityDeclaration(declaringAudio(audio(AAC, 1)))
                    .encodableVideoCodecFamilies(EVERY_VIDEO_CODEC_FAMILY)
                    .build())
            .orElseThrow();

    assertThat(decision.audioDecision().mode()).isEqualTo(AudioMode.TRANSCODE);
    assertThat(decision.audioDecision().codec()).isEqualTo("aac");
    assertThat(decision.audioDecision().channels()).isEqualTo(1);
  }
}
