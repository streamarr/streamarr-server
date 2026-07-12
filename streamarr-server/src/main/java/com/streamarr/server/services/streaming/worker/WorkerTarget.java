package com.streamarr.server.services.streaming.worker;

import java.util.UUID;

public record WorkerTarget(UUID workerId, UUID bootId) {

  public WorkerTarget {
    if (workerId == null || bootId == null) {
      throw new IllegalArgumentException("Worker and boot identities are required");
    }
  }
}
