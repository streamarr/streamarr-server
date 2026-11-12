package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.NonNull;

/** The video codec families that one connected worker encodes with a worker-verified encoder. */
@Builder
public record WorkerVerifiedCodecFamilies(
    @NonNull UUID workerId, @NonNull Set<String> codecFamilies) {

  public WorkerVerifiedCodecFamilies {
    codecFamilies = Set.copyOf(codecFamilies);
  }

  /** The codec families that at least one of these workers encodes with a verified encoder. */
  public static Set<String> codecFamiliesOf(List<WorkerVerifiedCodecFamilies> workers) {
    return workers.stream()
        .flatMap(worker -> worker.codecFamilies().stream())
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * The codec families that the server encodes and this worker has no encoder for, in name order.
   */
  public List<String> missingCodecFamilies() {
    return EncodeCodecProfile.codecFamilyNames().stream()
        .filter(family -> !codecFamilies.contains(family))
        .toList();
  }
}
