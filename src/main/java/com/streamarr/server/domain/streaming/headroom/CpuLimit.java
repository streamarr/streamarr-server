package com.streamarr.server.domain.streaming.headroom;

/**
 * The share of the host's CPU that a worker and its FFmpeg processes may use, in cores (ADR 0044).
 * A container's CPU quota sets it, and a worker without a quota has the whole host's CPU.
 */
public record CpuLimit(double cores) {

  public CpuLimit {
    if (!Double.isFinite(cores) || cores <= 0) {
      throw new IllegalArgumentException("A CPU limit needs a positive number of cores: " + cores);
    }
  }
}
