package com.streamarr.server.fixtures;

import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.ColorPrimaries;
import com.streamarr.server.domain.streaming.ColorRange;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.FrameRateRule;
import com.streamarr.server.domain.streaming.FrameRateRuleInputs;
import com.streamarr.server.domain.streaming.MatrixCoefficients;
import com.streamarr.server.domain.streaming.OutputFrameRateBasis;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.TransferCharacteristics;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import java.util.Optional;
import java.util.OptionalInt;
import lombok.experimental.UtilityClass;

/**
 * Chosen video streams as a probe version 4 outcome describes them, and the properties that probe
 * version 5 adds.
 */
@UtilityClass
public class VideoPlaybackFactsFixture {

  public static final VideoDynamicRange SDR =
      new VideoDynamicRange(
          CompatibleBase.SDR, Presence.UNSPECIFIED, new DolbyVision.Unspecified());

  public static final VideoDynamicRange SDR_WITHOUT_DOLBY_VISION =
      new VideoDynamicRange(CompatibleBase.SDR, Presence.ABSENT, new DolbyVision.Absent());

  public static final Fraction FILM_RATE = new Fraction(24_000, 1_001);

  /**
   * 8-bit 4:2:0 SDR H.264 at High profile level 4.0 and 24000/1001 fps, after cover art at stream
   * 0.
   */
  public static VideoPlaybackFacts.VideoPlaybackFactsBuilder sdrH264FactsBuilder() {
    return VideoPlaybackFacts.builder()
        .streamIndex(1)
        .codecFamily(Optional.of(ProbedCodecFamily.H264))
        .dynamicRange(SDR)
        .bitDepth(OptionalInt.of(8))
        .chromaSubsampling(Optional.of(ChromaSubsampling.CHROMA_420))
        .codecString(Optional.of("avc1.640028"))
        .frameRateRule(declaredRateRule(FILM_RATE));
  }

  /**
   * What probe version 5 reports for an SDR H.264 High profile stream at level 4.0 whose first
   * frames it read.
   */
  public static VideoStreamProperties.VideoStreamPropertiesBuilder h264HighPropertiesBuilder() {
    return VideoStreamProperties.builder()
        .codecProfile(Optional.of(VideoCodecProfile.H264_HIGH))
        .codecLevel(OptionalInt.of(40))
        .colorPrimaries(Optional.of(ColorPrimaries.BT709))
        .transferCharacteristics(Optional.of(TransferCharacteristics.BT709))
        .matrixCoefficients(Optional.of(MatrixCoefficients.BT709))
        .colorRange(Optional.of(ColorRange.LIMITED))
        .firstFramesRead(true);
  }

  /** The rule's result when the two declared rates agree on a listed rate. */
  public static FrameRateRule declaredRateRule(Fraction rate) {
    return FrameRateRule.builder()
        .outputFrameRate(Optional.of(rate))
        .basis(OutputFrameRateBasis.DECLARED_RATES)
        .inputs(
            FrameRateRuleInputs.builder()
                .baseRate(Optional.of(rate))
                .averageRate(Optional.of(rate))
                .build())
        .build();
  }

  /**
   * Dolby Vision profile 8.2: an SDR compatible base whose record claims compatibility identifier
   * 2, the SDR base.
   */
  public static VideoDynamicRange dolbyVisionProfile82() {
    return new VideoDynamicRange(
        CompatibleBase.SDR,
        Presence.UNSPECIFIED,
        new DolbyVision.Present(
            Optional.of(
                DolbyVisionConfiguration.builder()
                    .profile(OptionalInt.of(8))
                    .level(OptionalInt.of(6))
                    .baseLayerCompatibilityId(OptionalInt.of(2))
                    .baseLayer(true)
                    .enhancementLayer(false)
                    .rpu(true)
                    .build()),
            Presence.UNSPECIFIED));
  }

  /** Dolby Vision profile 5: no compatible base, and compatibility identifier 0. */
  public static VideoDynamicRange dolbyVisionProfile5() {
    return new VideoDynamicRange(
        CompatibleBase.NONE,
        Presence.UNSPECIFIED,
        new DolbyVision.Present(
            Optional.of(
                DolbyVisionConfiguration.builder()
                    .profile(OptionalInt.of(5))
                    .level(OptionalInt.of(6))
                    .baseLayerCompatibilityId(OptionalInt.of(0))
                    .baseLayer(true)
                    .rpu(true)
                    .build()),
            Presence.UNSPECIFIED));
  }
}
