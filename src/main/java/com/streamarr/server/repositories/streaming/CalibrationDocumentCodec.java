package com.streamarr.server.repositories.streaming;

import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.headroom.CostConstant;
import com.streamarr.server.domain.streaming.headroom.DecodeBackend;
import com.streamarr.server.domain.streaming.headroom.EncodeConfiguration;
import com.streamarr.server.domain.streaming.headroom.EncoderAndPreset;
import com.streamarr.server.domain.streaming.headroom.HeadroomConstants;
import com.streamarr.server.domain.streaming.headroom.KeptCalibration;
import com.streamarr.server.domain.streaming.headroom.PixelRate;
import com.streamarr.server.domain.streaming.headroom.PredictionCheck;
import com.streamarr.server.domain.streaming.headroom.SourceDecoder;
import com.streamarr.server.domain.streaming.headroom.ToneMapping;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The JSON document that stores a kept calibration's constants, predictions and measured constants
 * in one column. The server writes and reads that state whole, never by part.
 */
@RequiredArgsConstructor
final class CalibrationDocumentCodec {

  @NonNull private final ObjectMapper mapper;

  String encode(@NonNull KeptCalibration calibration) {
    var constants = calibration.constants();
    var document =
        new Document(
            constants.cpuSecondsPerMegapixel().entrySet().stream()
                .map(entry -> new ConstantValue(constantJson(entry.getKey()), entry.getValue()))
                .toList(),
            constants.coresAlone().entrySet().stream()
                .map(entry -> new CoresAlone(constantJson(entry.getKey()), entry.getValue()))
                .toList(),
            calibration.measuredConstants().stream()
                .map(CalibrationDocumentCodec::constantJson)
                .toList(),
            calibration.latestPredictions().entrySet().stream()
                .map(
                    entry ->
                        new ConfigurationPredictions(
                            constantJson(entry.getKey().encoderAndPreset()),
                            constantJson(entry.getKey().sourceDecoder()),
                            entry.getKey().toneMapping().name(),
                            entry.getValue().stream().map(Check::of).toList()))
                .toList());
    return mapper.writeValueAsString(document);
  }

  /** A builder that carries the document's state; the caller sets the key and the kept time. */
  KeptCalibration.KeptCalibrationBuilder decode(@NonNull String json) {
    try {
      var document = mapper.readValue(json, Document.class);
      var cpuSecondsPerMegapixel = new HashMap<CostConstant, Double>();
      document
          .constants()
          .forEach(
              value ->
                  cpuSecondsPerMegapixel.put(
                      constant(value.constant()), value.cpuSecondsPerMegapixel()));
      var coresAlone = new HashMap<EncoderAndPreset, Double>();
      document
          .coresAlone()
          .forEach(
              value -> coresAlone.put(encoderAndPreset(value.encoderAndPreset()), value.cores()));
      return KeptCalibration.builder()
          .constants(
              HeadroomConstants.builder()
                  .cpuSecondsPerMegapixel(cpuSecondsPerMegapixel)
                  .coresAlone(coresAlone)
                  .build())
          .measuredConstants(
              document.measuredConstants().stream()
                  .map(CalibrationDocumentCodec::constant)
                  .collect(Collectors.toSet()))
          .latestPredictions(
              document.predictions().stream()
                  .collect(
                      Collectors.toMap(
                          predictions ->
                              new EncodeConfiguration(
                                  encoderAndPreset(predictions.encoderAndPreset()),
                                  sourceDecoder(predictions.sourceDecoder()),
                                  ToneMapping.valueOf(predictions.toneMapping())),
                          predictions ->
                              predictions.checks().stream()
                                  .map(Check::toPredictionCheck)
                                  .toList())));
    } catch (JacksonException | IllegalArgumentException e) {
      throw new InvalidCalibrationDocumentException("Unreadable calibration document", e);
    }
  }

  private static ConstantJson constantJson(CostConstant constant) {
    return switch (constant) {
      case EncoderAndPreset(var backend, var preset) ->
          new ConstantJson(
              ConstantJson.ENCODER_AND_PRESET,
              backend.codecFamily(),
              backend.encoder(),
              backend.kind().name(),
              preset.name(),
              null,
              null);
      case SourceDecoder(var codecFamily, var backend) ->
          new ConstantJson(
              ConstantJson.SOURCE_DECODER, codecFamily, null, null, null, backend.name(), null);
      case ToneMapping toneMapping ->
          new ConstantJson(
              ConstantJson.TONE_MAPPING, null, null, null, null, null, toneMapping.name());
    };
  }

  private static CostConstant constant(ConstantJson json) {
    return switch (json.kind()) {
      case ConstantJson.ENCODER_AND_PRESET -> encoderAndPreset(json);
      case ConstantJson.SOURCE_DECODER -> sourceDecoder(json);
      case ConstantJson.TONE_MAPPING -> ToneMapping.valueOf(json.toneMapping());
      case null, default ->
          throw new InvalidCalibrationDocumentException("Unknown constant kind: " + json.kind());
    };
  }

  private static EncoderAndPreset encoderAndPreset(ConstantJson json) {
    if (!ConstantJson.ENCODER_AND_PRESET.equals(json.kind())) {
      throw new InvalidCalibrationDocumentException("Not an encoder and preset: " + json.kind());
    }

    return new EncoderAndPreset(
        EncoderBackend.builder()
            .codecFamily(json.codecFamily())
            .encoder(json.encoder())
            .kind(EncoderBackend.Kind.valueOf(json.encoderKind()))
            .build(),
        EncoderPreset.valueOf(json.preset()));
  }

  private static SourceDecoder sourceDecoder(ConstantJson json) {
    if (!ConstantJson.SOURCE_DECODER.equals(json.kind())) {
      throw new InvalidCalibrationDocumentException("Not a source decoder: " + json.kind());
    }

    return new SourceDecoder(json.codecFamily(), DecodeBackend.valueOf(json.decodeBackend()));
  }

  private record Document(
      List<ConstantValue> constants,
      List<CoresAlone> coresAlone,
      List<ConstantJson> measuredConstants,
      List<ConfigurationPredictions> predictions) {}

  private record ConstantJson(
      String kind,
      String codecFamily,
      String encoder,
      String encoderKind,
      String preset,
      String decodeBackend,
      String toneMapping) {

    private static final String ENCODER_AND_PRESET = "ENCODER_AND_PRESET";
    private static final String SOURCE_DECODER = "SOURCE_DECODER";
    private static final String TONE_MAPPING = "TONE_MAPPING";
  }

  private record ConstantValue(ConstantJson constant, double cpuSecondsPerMegapixel) {}

  private record CoresAlone(ConstantJson encoderAndPreset, double cores) {}

  private record ConfigurationPredictions(
      ConstantJson encoderAndPreset,
      ConstantJson sourceDecoder,
      String toneMapping,
      List<Check> checks) {}

  private record Check(
      double relativeError,
      boolean besideOtherEncodes,
      double outputPixelsPerSecond,
      double sourcePixelsPerSecond,
      double measuredCpuSecondsPerMediaSecond,
      String measuredAt) {

    private static Check of(PredictionCheck check) {
      return new Check(
          check.relativeError(),
          check.besideOtherEncodes(),
          check.outputPixelRate().pixelsPerSecond(),
          check.sourcePixelRate().pixelsPerSecond(),
          check.measuredCpuSecondsPerMediaSecond(),
          check.measuredAt().toString());
    }

    private PredictionCheck toPredictionCheck() {
      return PredictionCheck.builder()
          .relativeError(relativeError)
          .besideOtherEncodes(besideOtherEncodes)
          .outputPixelRate(new PixelRate(outputPixelsPerSecond))
          .sourcePixelRate(new PixelRate(sourcePixelsPerSecond))
          .measuredCpuSecondsPerMediaSecond(measuredCpuSecondsPerMediaSecond)
          .measuredAt(Instant.parse(measuredAt))
          .build();
    }
  }
}
