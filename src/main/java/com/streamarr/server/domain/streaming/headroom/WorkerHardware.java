package com.streamarr.server.domain.streaming.headroom;

import java.util.List;
import lombok.Builder;
import lombok.NonNull;

/**
 * The hardware that a worker reports when it registers, part of a kept calibration's key (ADR
 * 0044). The driver version is not part of it. The order of the GPU models does not matter.
 */
@Builder
public record WorkerHardware(
    @NonNull String cpuModel, int logicalCpus, @NonNull List<String> gpuModels) {

  public WorkerHardware {
    if (logicalCpus <= 0) {
      throw new IllegalArgumentException("A worker needs logical CPUs: " + logicalCpus);
    }

    gpuModels = gpuModels.stream().sorted().toList();
  }
}
