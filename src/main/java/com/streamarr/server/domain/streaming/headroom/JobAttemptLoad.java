package com.streamarr.server.domain.streaming.headroom;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;

/**
 * What a job attempt that encodes video asks of a worker: the encode term prices the output pixel
 * rate, and the decode and tone-mapping terms price the source pixel rate (ADR 0044).
 */
@Builder(toBuilder = true)
public record JobAttemptLoad(
    @NonNull EncoderAndPreset encoderAndPreset,
    @NonNull PixelRate outputPixelRate,
    @NonNull SourceDecoder sourceDecoder,
    @NonNull PixelRate sourcePixelRate,
    @NonNull ToneMapping toneMapping) {

  public EncodeConfiguration configuration() {
    return new EncodeConfiguration(encoderAndPreset, sourceDecoder, toneMapping);
  }

  public List<CostTerm> costTerms() {
    var terms = new ArrayList<CostTerm>(3);
    terms.add(new CostTerm(encoderAndPreset, outputPixelRate.megapixelsPerSecond()));
    terms.add(new CostTerm(sourceDecoder, sourcePixelRate.megapixelsPerSecond()));
    if (toneMapping != ToneMapping.NONE) {
      terms.add(new CostTerm(toneMapping, sourcePixelRate.megapixelsPerSecond()));
    }

    return List.copyOf(terms);
  }
}
