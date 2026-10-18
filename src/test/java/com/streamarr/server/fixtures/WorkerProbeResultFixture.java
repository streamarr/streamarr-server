package com.streamarr.server.fixtures;

import com.streamarr.transcode.v1.Fraction;
import com.streamarr.transcode.v1.FrameRateRuleResult;
import com.streamarr.transcode.v1.OutputFrameRateBasis;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
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
}
