package com.streamarr.server.repositories.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.headroom.CalibrationKey;
import com.streamarr.server.domain.streaming.headroom.CpuLimit;
import com.streamarr.server.domain.streaming.headroom.DecodeBackend;
import com.streamarr.server.domain.streaming.headroom.EncodeConfiguration;
import com.streamarr.server.domain.streaming.headroom.EncoderAndPreset;
import com.streamarr.server.domain.streaming.headroom.HeadroomConstants;
import com.streamarr.server.domain.streaming.headroom.KeptCalibration;
import com.streamarr.server.domain.streaming.headroom.PixelRate;
import com.streamarr.server.domain.streaming.headroom.PredictionCheck;
import com.streamarr.server.domain.streaming.headroom.SourceDecoder;
import com.streamarr.server.domain.streaming.headroom.ToneMapping;
import com.streamarr.server.domain.streaming.headroom.WorkerHardware;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("UnitTest")
@DisplayName("Calibration Document Codec Tests")
class CalibrationDocumentCodecTest {

  private static final EncoderAndPreset LIBX264_FASTER =
      new EncoderAndPreset(
          EncoderBackend.builder()
              .codecFamily("h264")
              .encoder("libx264")
              .kind(EncoderBackend.Kind.SOFTWARE)
              .build(),
          EncoderPreset.FASTER);
  private static final EncoderAndPreset NVENC_FASTER =
      new EncoderAndPreset(
          EncoderBackend.builder()
              .codecFamily("hevc")
              .encoder("hevc_nvenc")
              .kind(EncoderBackend.Kind.HARDWARE)
              .build(),
          EncoderPreset.FASTER);
  private static final SourceDecoder SOFTWARE_H264 =
      new SourceDecoder("h264", DecodeBackend.SOFTWARE);
  private static final SourceDecoder HARDWARE_HEVC =
      new SourceDecoder("hevc", DecodeBackend.HARDWARE);

  private final CalibrationDocumentCodec codec =
      new CalibrationDocumentCodec(JsonMapper.builder().build());

  private static KeptCalibration calibration() {
    var check =
        PredictionCheck.builder()
            .relativeError(-0.0312345678901234)
            .besideOtherEncodes(true)
            .outputPixelRate(PixelRate.of(1280, 720, 24_000.0 / 1_001))
            .sourcePixelRate(PixelRate.of(3840, 2160, 24_000.0 / 1_001))
            .measuredCpuSecondsPerMediaSecond(1.7300000000000002)
            .measuredAt(Instant.parse("2026-11-06T01:02:03.456789Z"))
            .build();
    return KeptCalibration.builder()
        .key(
            CalibrationKey.builder()
                .hardware(
                    WorkerHardware.builder()
                        .cpuModel("Synthetic CPU 9000")
                        .logicalCpus(16)
                        .gpuModels(List.of("Synthetic GPU"))
                        .build())
                .cpuLimit(new CpuLimit(6))
                .ffmpegBuild("8.1.2-synthetic")
                .build())
        .constants(
            HeadroomConstants.builder()
                .cpuSecondsPerMegapixel(
                    Map.of(
                        LIBX264_FASTER,
                        0.0401,
                        NVENC_FASTER,
                        0.0003,
                        SOFTWARE_H264,
                        0.0102,
                        HARDWARE_HEVC,
                        0.0011,
                        ToneMapping.PQ,
                        0.0197))
                .coresAlone(Map.of(LIBX264_FASTER, 6.0, NVENC_FASTER, 1.25))
                .build())
        .latestPredictions(
            Map.of(
                new EncodeConfiguration(LIBX264_FASTER, SOFTWARE_H264, ToneMapping.NONE),
                List.of(check, check.toBuilder().besideOtherEncodes(false).build()),
                new EncodeConfiguration(NVENC_FASTER, HARDWARE_HEVC, ToneMapping.PQ),
                List.of(check)))
        .measuredConstants(Set.of(LIBX264_FASTER, SOFTWARE_H264, ToneMapping.PQ))
        .keptAt(Instant.parse("2026-11-06T02:00:00Z"))
        .build();
  }

  @Test
  @DisplayName("Should read back the same calibration state when a kept calibration is encoded")
  void shouldReadBackTheSameCalibrationStateWhenAKeptCalibrationIsEncoded() {
    var original = calibration();

    var decoded =
        codec.decode(codec.encode(original)).key(original.key()).keptAt(original.keptAt()).build();

    assertThat(decoded).isEqualTo(original);
  }

  @Test
  @DisplayName("Should reject the document when a constant names an unknown kind")
  void shouldRejectTheDocumentWhenAConstantNamesAnUnknownKind() {
    var document =
        """
        {"constants": [{"constant": {"kind": "RESAMPLING"}, "cpuSecondsPerMegapixel": 0.01}],
         "coresAlone": [], "measuredConstants": [], "predictions": []}
        """;

    assertThatThrownBy(() -> codec.decode(document))
        .isInstanceOf(InvalidCalibrationDocumentException.class);
  }
}
