package com.streamarr.server.fixtures;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.probe.ProbeAttemptEnds;
import com.streamarr.server.services.streaming.PlaybackProbeService;
import java.time.Clock;

public final class PlaybackProbeServiceFixture {

  private PlaybackProbeServiceFixture() {}

  /**
   * A playback probe service that reads the given outcomes. No probe worker is connected, so a file
   * without a stored outcome is refused as not ready at once instead of waiting for its probe.
   */
  public static PlaybackProbeService.PlaybackProbeServiceBuilder playbackProbeServiceBuilder(
      MediaFileContainerInfoRepository outcomes) {
    return PlaybackProbeService.builder()
        .reader(new PersistedProbeReader(outcomes))
        .outcomes(outcomes)
        .eventPublisher(new CapturingEventPublisher())
        .probeWorkers(_ -> false)
        .attemptEnds(new ProbeAttemptEnds())
        .clock(Clock.systemUTC())
        .properties(StreamingProperties.builder().build());
  }
}
