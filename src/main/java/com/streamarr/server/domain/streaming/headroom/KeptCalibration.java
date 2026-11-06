package com.streamarr.server.domain.streaming.headroom;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.NonNull;

/**
 * The last calibration that the server keeps for one calibration key: the fitted constants, each
 * encode configuration's live predictions, the constants that a measurement has priced, and when
 * the server kept it.
 */
@Builder(toBuilder = true)
public record KeptCalibration(
    @NonNull CalibrationKey key,
    @NonNull HeadroomConstants constants,
    @NonNull Map<EncodeConfiguration, List<PredictionCheck>> latestPredictions,
    @NonNull Set<CostConstant> measuredConstants,
    @NonNull Instant keptAt) {

  public KeptCalibration {
    latestPredictions =
        latestPredictions.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    measuredConstants = Set.copyOf(measuredConstants);
  }
}
