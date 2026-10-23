package com.streamarr.server.services.streaming;

import lombok.Builder;

/**
 * How many due probes the server may claim at once.
 *
 * @param backgroundProbes the probes of any priority, oldest first
 * @param playbackProbes the further probes that a playback request waits for
 */
@Builder
public record ProbeClaim(int backgroundProbes, int playbackProbes) {

  public int total() {
    return backgroundProbes + playbackProbes;
  }
}
