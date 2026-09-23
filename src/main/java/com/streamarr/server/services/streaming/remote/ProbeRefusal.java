package com.streamarr.server.services.streaming.remote;

/**
 * Why no worker accepted a probe attempt. When workers refuse for different reasons, dispatch
 * reports the one declared last. A worker that failed to receive the probe therefore outranks busy
 * capacity, so the attempt keeps the failure backoff instead of waiting as busy.
 */
public enum ProbeRefusal {
  INVALID_REQUEST("The probe request is incomplete"),
  NO_CONNECTED_WORKER("No worker is connected to probe the media source"),
  NO_COMPATIBLE_WORKER(
      "No connected worker can read the source namespace at the requested probe version"),
  WORKERS_BUSY("All compatible workers are busy"),
  WORKER_UNREACHABLE("A compatible worker could not receive the probe request"),
  ATTEMPT_IN_PROGRESS("The probe attempt is already running on a worker");

  private final String description;

  ProbeRefusal(String description) {
    this.description = description;
  }

  public String description() {
    return description;
  }

  ProbeRefusal mostSpecific(ProbeRefusal other) {
    if (compareTo(other) >= 0) {
      return this;
    }

    return other;
  }
}
