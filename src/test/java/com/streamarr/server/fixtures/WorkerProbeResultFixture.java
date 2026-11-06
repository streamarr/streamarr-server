package com.streamarr.server.fixtures;

import com.streamarr.transcode.v1.ChromaticityCoordinates;
import com.streamarr.transcode.v1.CompatibleBase;
import com.streamarr.transcode.v1.DolbyVisionEnhancementLayer;
import com.streamarr.transcode.v1.DolbyVisionPresent;
import com.streamarr.transcode.v1.Fraction;
import com.streamarr.transcode.v1.FrameRateRuleResult;
import com.streamarr.transcode.v1.MasteringDisplayColorVolume;
import com.streamarr.transcode.v1.OutputFrameRateBasis;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.v1.TransferCharacteristics;
import com.streamarr.transcode.v1.VideoDynamicRange;
import lombok.experimental.UtilityClass;

/** Worker probe results in the shape that probe version 4 reports. */
@UtilityClass
public class WorkerProbeResultFixture {

  /** One video stream at index 0, which the probe chose, at a declared 24000/1001 fps. */
  public static ProbeMediaInfo.Builder version4MediaBuilder() {
    return ProbeMediaInfo.newBuilder()
        .addStreams(ProbeStreamInfo.newBuilder().setIndex(0).setCodecType("video"))
        .setChosenVideoStreamIndex(0)
        .setFrameRateRule(declaredRateRuleBuilder());
  }

  public static FrameRateRuleResult.Builder declaredRateRuleBuilder() {
    var filmRate = Fraction.newBuilder().setNumerator(24_000).setDenominator(1_001);
    var rule =
        FrameRateRuleResult.newBuilder()
            .setOutputFrameRate(filmRate)
            .setBasis(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_DECLARED_RATES);
    rule.getInputsBuilder().setBaseRate(filmRate).setAverageRate(filmRate);
    return rule;
  }

  /** A mastering display whose four chromaticity coordinates and luminances are all 0. */
  public static MasteringDisplayColorVolume.Builder masteringDisplayBuilder() {
    var coordinates = ChromaticityCoordinates.getDefaultInstance();
    return MasteringDisplayColorVolume.newBuilder()
        .setRed(coordinates)
        .setGreen(coordinates)
        .setBlue(coordinates)
        .setWhitePoint(coordinates);
  }

  /**
   * Gives the video stream a PQ transfer and Dolby Vision on a PQ base, and returns its Dolby
   * Vision for the caller's RPU facts.
   */
  public static DolbyVisionPresent.Builder pqDolbyVisionOf(ProbeStreamInfo.Builder video) {
    return video
        .setTransferCharacteristics(TransferCharacteristics.TRANSFER_CHARACTERISTICS_SMPTE2084)
        .setDynamicRange(
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
                .setDolbyVisionPresent(DolbyVisionPresent.newBuilder()))
        .getDynamicRangeBuilder()
        .getDolbyVisionPresentBuilder();
  }

  /** The contract's enhancement layer of this name, such as FEL. */
  public static DolbyVisionEnhancementLayer reportedEnhancementLayer(String name) {
    return DolbyVisionEnhancementLayer.valueOf("DOLBY_VISION_ENHANCEMENT_LAYER_" + name);
  }
}
