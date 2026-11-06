package com.streamarr.server.domain.streaming.headroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.EncoderPreset;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Headroom Constants Tests")
class HeadroomConstantsTest {

  // 25 megapixels per second of output and 50 of source keep each term a round number.
  private static final PixelRate OUTPUT = PixelRate.of(1000, 1000, 25);
  private static final PixelRate SOURCE = PixelRate.of(2000, 1000, 25);
  private static final EncoderAndPreset LIBX264_FASTER =
      new EncoderAndPreset(
          EncoderBackend.builder()
              .codecFamily("h264")
              .encoder("libx264")
              .kind(EncoderBackend.Kind.SOFTWARE)
              .build(),
          EncoderPreset.FASTER);
  private static final SourceDecoder SOFTWARE_HEVC =
      new SourceDecoder("hevc", DecodeBackend.SOFTWARE);
  private static final SourceDecoder HARDWARE_HEVC =
      new SourceDecoder("hevc", DecodeBackend.HARDWARE);

  private static final HeadroomConstants CONSTANTS =
      HeadroomConstants.builder()
          .cpuSecondsPerMegapixel(
              Map.of(
                  LIBX264_FASTER, 0.04,
                  SOFTWARE_HEVC, 0.01,
                  HARDWARE_HEVC, 0.001,
                  ToneMapping.PQ, 0.02))
          .coresAlone(Map.of(LIBX264_FASTER, 8.0))
          .build();

  private static JobAttemptLoad.JobAttemptLoadBuilder hevcSourceEncodedWithLibx264Faster() {
    return JobAttemptLoad.builder()
        .encoderAndPreset(LIBX264_FASTER)
        .outputPixelRate(OUTPUT)
        .sourceDecoder(SOFTWARE_HEVC)
        .sourcePixelRate(SOURCE)
        .toneMapping(ToneMapping.NONE);
  }

  @Test
  @DisplayName(
      "Should cost the encode term in output pixel rate plus the decode term in source pixel rate when the job attempt does not tone-map")
  void shouldCostTheEncodeTermInOutputPixelRatePlusTheDecodeTermInSourcePixelRateWhenTheJobAttemptDoesNotToneMap() {
    var cost = CONSTANTS.cpuSecondsPerMediaSecond(hevcSourceEncodedWithLibx264Faster().build());

    assertThat(cost).hasValueCloseTo(0.04 * 25 + 0.01 * 50, within(1e-12));
  }

  @Test
  @DisplayName(
      "Should add the tone-mapping term in source pixel rate when the job attempt tone-maps a PQ source")
  void shouldAddTheToneMappingTermInSourcePixelRateWhenTheJobAttemptToneMapsAPqSource() {
    var cost =
        CONSTANTS.cpuSecondsPerMediaSecond(
            hevcSourceEncodedWithLibx264Faster().toneMapping(ToneMapping.PQ).build());

    assertThat(cost).hasValueCloseTo(0.04 * 25 + 0.01 * 50 + 0.02 * 50, within(1e-12));
  }

  @Test
  @DisplayName(
      "Should charge the hardware decoder's constant when the job attempt decodes the source in hardware")
  void shouldChargeTheHardwareDecodersConstantWhenTheJobAttemptDecodesTheSourceInHardware() {
    var cost =
        CONSTANTS.cpuSecondsPerMediaSecond(
            hevcSourceEncodedWithLibx264Faster().sourceDecoder(HARDWARE_HEVC).build());

    assertThat(cost).hasValueCloseTo(0.04 * 25 + 0.001 * 50, within(1e-12));
  }

  @Test
  @DisplayName("Should have no cost when the tone-mapping constant of the job attempt is unknown")
  void shouldHaveNoCostWhenTheToneMappingConstantOfTheJobAttemptIsUnknown() {
    var cost =
        CONSTANTS.cpuSecondsPerMediaSecond(
            hevcSourceEncodedWithLibx264Faster().toneMapping(ToneMapping.HLG).build());

    assertThat(cost).isEmpty();
  }

  @Test
  @DisplayName("Should have no cost when the encoder-and-preset constant of the job attempt is unknown")
  void shouldHaveNoCostWhenTheEncoderAndPresetConstantOfTheJobAttemptIsUnknown() {
    var veryfast = new EncoderAndPreset(LIBX264_FASTER.backend(), EncoderPreset.VERYFAST);

    var cost =
        CONSTANTS.cpuSecondsPerMediaSecond(
            hevcSourceEncodedWithLibx264Faster().encoderAndPreset(veryfast).build());

    assertThat(cost).isEmpty();
  }
}
