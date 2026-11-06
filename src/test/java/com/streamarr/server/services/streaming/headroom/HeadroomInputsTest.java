package com.streamarr.server.services.streaming.headroom;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.headroom.CpuLimit;
import com.streamarr.server.domain.streaming.headroom.DecodeBackend;
import com.streamarr.server.domain.streaming.headroom.EncoderAndPreset;
import com.streamarr.server.domain.streaming.headroom.HeadroomConstants;
import com.streamarr.server.domain.streaming.headroom.JobAttemptLoad;
import com.streamarr.server.domain.streaming.headroom.PixelRate;
import com.streamarr.server.domain.streaming.headroom.PredictionCheck;
import com.streamarr.server.domain.streaming.headroom.SourceDecoder;
import com.streamarr.server.domain.streaming.headroom.ToneMapping;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Headroom Inputs Tests")
class HeadroomInputsTest {

  private static final JobAttemptLoad LOAD =
      JobAttemptLoad.builder()
          .encoderAndPreset(
              new EncoderAndPreset(
                  EncoderBackend.builder()
                      .codecFamily("h264")
                      .encoder("libx264")
                      .kind(EncoderBackend.Kind.SOFTWARE)
                      .build(),
                  EncoderPreset.FASTER))
          .outputPixelRate(PixelRate.of(1280, 720, 25))
          .sourceDecoder(new SourceDecoder("h264", DecodeBackend.SOFTWARE))
          .sourcePixelRate(PixelRate.of(1920, 1080, 25))
          .toneMapping(ToneMapping.NONE)
          .build();

  private static ProductionMeasurement.ProductionMeasurementBuilder threeSecondSegment() {
    return ProductionMeasurement.builder()
        .load(LOAD)
        .otherEncodes(List.of())
        .mediaDuration(Duration.ofSeconds(3))
        .productionTime(Duration.ofSeconds(1));
  }

  @ParameterizedTest(name = "{0} cores")
  @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
  @DisplayName("Should reject the CPU limit when its cores are not a positive finite number")
  void shouldRejectTheCpuLimitWhenItsCoresAreNotAPositiveFiniteNumber(double cores) {
    assertThatThrownBy(() -> new CpuLimit(cores)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "{0} cores")
  @ValueSource(doubles = {0, -1, Double.NaN})
  @DisplayName(
      "Should reject the constants when the cores that one job attempt uses alone are not a positive finite number")
  void shouldRejectTheConstantsWhenTheCoresThatOneJobAttemptUsesAloneAreNotAPositiveFiniteNumber(
      double cores) {
    var constants =
        HeadroomConstants.builder()
            .cpuSecondsPerMegapixel(Map.of())
            .coresAlone(Map.of(LOAD.encoderAndPreset(), cores));

    assertThatThrownBy(constants::build).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "{0} CPU seconds per megapixel")
  @ValueSource(doubles = {0, -0.01, Double.NaN})
  @DisplayName("Should reject the constants when a constant is not a positive finite number")
  void shouldRejectTheConstantsWhenAConstantIsNotAPositiveFiniteNumber(double constant) {
    var constants =
        HeadroomConstants.builder()
            .cpuSecondsPerMegapixel(Map.of(LOAD.sourceDecoder(), constant))
            .coresAlone(Map.of());

    assertThatThrownBy(constants::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject the measurement when its production time is zero")
  void shouldRejectTheMeasurementWhenItsProductionTimeIsZero() {
    var measurement = threeSecondSegment().productionTime(Duration.ZERO);

    assertThatThrownBy(measurement::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject the measurement when its media duration is zero")
  void shouldRejectTheMeasurementWhenItsMediaDurationIsZero() {
    var measurement = threeSecondSegment().mediaDuration(Duration.ZERO);

    assertThatThrownBy(measurement::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject the prediction check when its error is not a finite number")
  void shouldRejectThePredictionCheckWhenItsErrorIsNotAFiniteNumber() {
    var check =
        PredictionCheck.builder()
            .relativeError(Double.NaN)
            .besideOtherEncodes(false)
            .outputToSourceRatio(0.5);

    assertThatThrownBy(check::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject the prediction check when its ratio of output to source is zero")
  void shouldRejectThePredictionCheckWhenItsRatioOfOutputToSourceIsZero() {
    var check =
        PredictionCheck.builder()
            .relativeError(0.1)
            .besideOtherEncodes(true)
            .outputToSourceRatio(0);

    assertThatThrownBy(check::build).isInstanceOf(IllegalArgumentException.class);
  }
}
