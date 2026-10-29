package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.ChosenVideoStream;
import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.ColorPrimaries;
import com.streamarr.server.domain.streaming.ColorRange;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.DolbyVisionRpuHeader;
import com.streamarr.server.domain.streaming.DolbyVisionRpuPlacement;
import com.streamarr.server.domain.streaming.MatrixCoefficients;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TransferCharacteristics;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * How the transcode decision uses the facts that probe version 5 adds (streamarr/streamarr-server
 * #394). Each source is the chosen video stream of a stored outcome whose keyframes open every grid
 * interval, read by a client that names H.264 and HEVC, so only the stream's facts decide whether
 * the video is copied.
 */
@Tag("UnitTest")
@DisplayName("Transcode Decision Frame Facts Tests")
class TranscodeDecisionFrameFactsTest {

  private static final Duration MEDIA_DURATION = Duration.ofMinutes(10);

  private final TranscodeDecisionService service =
      new TranscodeDecisionService(
          StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build(),
          new SimpleMeterRegistry());

  @Test
  @DisplayName(
      "Should copy a record-less Dolby Vision profile 5 stream tagged BT.709 when probe version 4"
          + " describes it")
  void shouldCopyARecordLessDolbyVisionProfile5StreamTaggedBt709WhenProbeVersion4DescribesIt() {
    var profile5 =
        hevcStream()
            .dynamicRange(
                Optional.of(
                    new VideoDynamicRange(
                        CompatibleBase.SDR,
                        Presence.UNSPECIFIED,
                        new DolbyVision.Present(Optional.empty(), Presence.UNSPECIFIED))))
            .build();

    assertThat(decide("mov,mp4,m4a,3gp,3g2,mj2", profile5)).isEqualTo(TranscodeMode.REMUX);
  }

  @ParameterizedTest
  @ValueSource(strings = {"mov,mp4,m4a,3gp,3g2,mj2", "mpegts"})
  @DisplayName(
      "Should encode a record-less Dolby Vision profile 5 stream tagged BT.709 when probe version 5"
          + " reads its first RPU")
  void shouldEncodeARecordLessDolbyVisionProfile5StreamTaggedBt709WhenProbeVersion5ReadsItsFirstRpu(
      String containerFormat) {
    var profile5 =
        hevcStream()
            .dynamicRange(
                Optional.of(
                    new VideoDynamicRange(
                        CompatibleBase.NONE,
                        Presence.ABSENT,
                        new DolbyVision.Present(
                            Optional.empty(),
                            Presence.PRESENT,
                            Optional.of(profile5RpuHeader()),
                            DolbyVisionRpuPlacement.AFTER_OWN_PICTURE))))
            .videoProperties(Optional.of(bt709Properties(VideoCodecProfile.HEVC_MAIN_10)))
            .build();

    assertThat(decide(containerFormat, profile5)).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  @Test
  @DisplayName("Should encode a Dolby Vision profile 9 stream when its RPUs start samples")
  void shouldEncodeADolbyVisionProfile9StreamWhenItsRpusStartSamples() {
    var profile9 = profile9Stream(DolbyVisionRpuPlacement.STARTS_SAMPLE);

    assertThat(decide("mov,mp4,m4a,3gp,3g2,mj2", profile9))
        .isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  @Test
  @DisplayName("Should copy a Dolby Vision profile 9 stream when every RPU follows its picture")
  void shouldCopyADolbyVisionProfile9StreamWhenEveryRpuFollowsItsPicture() {
    var profile9 = profile9Stream(DolbyVisionRpuPlacement.AFTER_OWN_PICTURE);

    assertThat(decide("mov,mp4,m4a,3gp,3g2,mj2", profile9)).isEqualTo(TranscodeMode.REMUX);
  }

  // A shifted file whose start was cut away has an RPU at the start of its first picture packet,
  // and the probe reports it as STARTS_SAMPLE like any other file whose RPUs start samples.
  // ADR 0013: an UNSPECIFIED placement rules out no copy, so only STARTS_SAMPLE refuses one.
  @ParameterizedTest(name = "{0}")
  @CsvSource({"UNSPECIFIED, REMUX", "AFTER_OWN_PICTURE, REMUX", "STARTS_SAMPLE, VIDEO_TRANSCODE"})
  @DisplayName("Should encode a Dolby Vision profile 8.2 stream only when its RPUs start samples")
  void shouldEncodeADolbyVisionProfile8Point2StreamOnlyWhenItsRpusStartSamples(
      DolbyVisionRpuPlacement placement, TranscodeMode expected) {
    var profile82 = profile82Stream(placement);

    assertThat(decide("mov,mp4,m4a,3gp,3g2,mj2", profile82)).isEqualTo(expected);
  }

  @Test
  @DisplayName(
      "Should encode a record-less Dolby Vision profile 5 stream when its RPUs start samples")
  void shouldEncodeARecordLessDolbyVisionProfile5StreamWhenItsRpusStartSamples() {
    var profile5 =
        hevcStream()
            .dynamicRange(
                Optional.of(
                    new VideoDynamicRange(
                        CompatibleBase.NONE,
                        Presence.ABSENT,
                        new DolbyVision.Present(
                            Optional.empty(),
                            Presence.PRESENT,
                            Optional.of(profile5RpuHeader()),
                            DolbyVisionRpuPlacement.STARTS_SAMPLE))))
            .videoProperties(Optional.of(bt709Properties(VideoCodecProfile.HEVC_MAIN_10)))
            .build();

    assertThat(decide("mov,mp4,m4a,3gp,3g2,mj2", profile5))
        .isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  @Test
  @DisplayName("Should encode a stream when probe version 5 could not name its codec profile")
  void shouldEncodeAStreamWhenProbeVersion5CouldNotNameItsCodecProfile() {
    var unnamed =
        h264Stream()
            .videoProperties(
                Optional.of(VideoStreamProperties.builder().firstFramesRead(true).build()))
            .build();

    assertThat(decide("mov,mp4,m4a,3gp,3g2,mj2", unnamed)).isEqualTo(TranscodeMode.VIDEO_TRANSCODE);
  }

  @Test
  @DisplayName("Should copy a stream when probe version 5 names its codec profile")
  void shouldCopyAStreamWhenProbeVersion5NamesItsCodecProfile() {
    var named =
        h264Stream()
            .videoProperties(Optional.of(bt709Properties(VideoCodecProfile.H264_HIGH)))
            .build();

    assertThat(decide("mov,mp4,m4a,3gp,3g2,mj2", named)).isEqualTo(TranscodeMode.REMUX);
  }

  @Test
  @DisplayName("Should copy a stream when probe version 4 reported no codec profile")
  void shouldCopyAStreamWhenProbeVersion4ReportedNoCodecProfile() {
    assertThat(decide("mov,mp4,m4a,3gp,3g2,mj2", h264Stream().build()))
        .isEqualTo(TranscodeMode.REMUX);
  }

  private TranscodeMode decide(String containerFormat, StreamInfo video) {
    var outcome =
        ProbeOutcome.Success.builder()
            .container(
                ProbeContainer.builder()
                    .format(Optional.of(containerFormat))
                    .duration(Optional.of(MEDIA_DURATION))
                    .bitrate(OptionalLong.of(5_000_000))
                    .build())
            .streams(List.of(video, ProbeFixture.audioStreamBuilder("aac").index(1).build()))
            .keyframes(ProbeFixture.keyframesEvery(Duration.ofSeconds(2), MEDIA_DURATION))
            .chosenVideoStream(
                Optional.of(
                    new ChosenVideoStream(
                        0,
                        VideoPlaybackFactsFixture.declaredRateRule(
                            VideoPlaybackFactsFixture.FILM_RATE))))
            .build();

    return service
        .decide(
            TranscodeDecisionRequest.builder()
                .source(outcome.mediaProbe())
                .clientOptions(
                    StreamingOptions.builder()
                        .supportedCodecs(List.of("h264", "hevc"))
                        .supportedAudioCodecs(StreamingOptions.DEFAULT_SUPPORTED_AUDIO_CODECS)
                        .maxAudioChannels(StreamingOptions.DEFAULT_MAX_AUDIO_CHANNELS)
                        .build())
                .encodableVideoCodecFamilies(Set.of("h264", "hevc", "av1"))
                .build())
        .orElseThrow()
        .transcodeMode();
  }

  // Dolby Vision profile 9: an H.264 base layer with an SDR base, whose record claims
  // compatibility identifier 2.
  private static StreamInfo profile9Stream(DolbyVisionRpuPlacement placement) {
    return h264Stream()
        .dynamicRange(Optional.of(sdrBaseDolbyVision(9, placement)))
        .videoProperties(Optional.of(bt709Properties(VideoCodecProfile.H264_HIGH)))
        .build();
  }

  // Dolby Vision profile 8.2: an HEVC base layer with an SDR base.
  private static StreamInfo profile82Stream(DolbyVisionRpuPlacement placement) {
    return hevcStream()
        .dynamicRange(Optional.of(sdrBaseDolbyVision(8, placement)))
        .videoProperties(Optional.of(bt709Properties(VideoCodecProfile.HEVC_MAIN_10)))
        .build();
  }

  // The RPU header fields that tell profile 5 in an HEVC stream without a configuration record.
  private static DolbyVisionRpuHeader profile5RpuHeader() {
    return DolbyVisionRpuHeader.builder()
        .vdrRpuProfile(0)
        .baseLayerFullRange(true)
        .disableResidual(true)
        .vdrBitDepth(12)
        .build();
  }

  // A Dolby Vision stream with an SDR base whose record claims compatibility identifier 2, and
  // whose samples the probe read.
  private static VideoDynamicRange sdrBaseDolbyVision(
      int profile, DolbyVisionRpuPlacement placement) {
    return new VideoDynamicRange(
        CompatibleBase.SDR,
        Presence.UNSPECIFIED,
        new DolbyVision.Present(
            Optional.of(
                DolbyVisionConfiguration.builder()
                    .profile(OptionalInt.of(profile))
                    .level(OptionalInt.of(5))
                    .baseLayerCompatibilityId(OptionalInt.of(2))
                    .baseLayer(true)
                    .rpu(true)
                    .build()),
            Presence.PRESENT,
            Optional.empty(),
            placement));
  }

  private static VideoStreamProperties bt709Properties(VideoCodecProfile profile) {
    var tier = Optional.of(VideoCodecTier.MAIN);
    if (profile == VideoCodecProfile.H264_HIGH) {
      tier = Optional.empty();
    }

    return VideoStreamProperties.builder()
        .codecProfile(Optional.of(profile))
        .codecLevel(OptionalInt.of(40))
        .codecTier(tier)
        .colorPrimaries(Optional.of(ColorPrimaries.BT709))
        .transferCharacteristics(Optional.of(TransferCharacteristics.BT709))
        .matrixCoefficients(Optional.of(MatrixCoefficients.BT709))
        .colorRange(Optional.of(ColorRange.LIMITED))
        .firstFramesRead(true)
        .build();
  }

  private static StreamInfo.StreamInfoBuilder h264Stream() {
    return StreamInfo.builder()
        .index(0)
        .codecType("video")
        .codec(Optional.of("h264"))
        .width(OptionalInt.of(1920))
        .height(OptionalInt.of(1080))
        .dynamicRange(Optional.of(VideoPlaybackFactsFixture.SDR))
        .bitDepth(OptionalInt.of(8))
        .chromaSubsampling(Optional.of(ChromaSubsampling.CHROMA_420))
        .codecString(Optional.of("avc1.640028"));
  }

  private static StreamInfo.StreamInfoBuilder hevcStream() {
    return StreamInfo.builder()
        .index(0)
        .codecType("video")
        .codec(Optional.of("hevc"))
        .width(OptionalInt.of(1920))
        .height(OptionalInt.of(1080))
        .bitDepth(OptionalInt.of(10))
        .chromaSubsampling(Optional.of(ChromaSubsampling.CHROMA_420))
        .codecString(Optional.of("hvc1.2.4.L120.90"));
  }
}
