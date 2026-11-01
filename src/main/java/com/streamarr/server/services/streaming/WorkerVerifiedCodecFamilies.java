package com.streamarr.server.services.streaming;

import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/** The video codec families that one connected worker encodes with a worker-verified encoder. */
@Builder
public record WorkerVerifiedCodecFamilies(
    @NonNull UUID workerId, @NonNull Set<String> codecFamilies) {

  public WorkerVerifiedCodecFamilies {
    codecFamilies = Set.copyOf(codecFamilies);
  }
}
