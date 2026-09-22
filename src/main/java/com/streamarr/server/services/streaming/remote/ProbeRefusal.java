package com.streamarr.server.services.streaming.remote;

/**
 * Why no worker accepted a probe attempt. When workers refuse for different reasons, dispatch
 * reports the one declared last: it says the most about when a retry can succeed.
 */
public enum ProbeRefusal {
  INVALID_REQUEST("The probe request is incomplete"),
  NO_CONNECTED_WORKER("No worker is connected to probe the media source"),
  NO_COMPATIBLE_WORKER(
      "No connected worker can read the source namespace at the requested probe version"),
  WORKER_UNREACHABLE("Compatible workers could not receive the probe request"),
  WORKERS_BUSY("All compatible workers are busy"),
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
