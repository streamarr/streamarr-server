package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.QualityVariant;
import java.util.List;
import lombok.NonNull;

/**
 * The variants of an encode and the audio step that every variant's cost includes (ADR 0050). The
 * stream session delivers that audio in place of its audio decision.
 */
public record QualityLadder(@NonNull List<QualityVariant> variants, @NonNull AudioDecision audio) {

  public QualityLadder {
    variants = List.copyOf(variants);
  }
}
