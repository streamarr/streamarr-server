package com.streamarr.server.config.health;

import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome.Deleted;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome.NoOwnedFolder;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome.Refused;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * Reports whether leftovers of earlier server processes remain in a segment folder that only this
 * server uses. The answer is the one of the server's start: nothing deletes leftovers later.
 */
@Component
@RequiredArgsConstructor
public class SegmentFolderHealthIndicator implements HealthIndicator {

  static final Status DEGRADED =
      new Status(
          "DEGRADED",
          "Leftovers of earlier server processes remain in the server's segment folder");

  private final SegmentFolderLeftoversOutcome leftovers;

  @Override
  public Health health() {
    return switch (leftovers) {
      case Refused(var refusal) ->
          Health.status(DEGRADED).withDetail("refusal", refusal.metricTag()).build();
      case Deleted(_, var remaining) when remaining > 0 ->
          Health.status(DEGRADED).withDetail("remaining", remaining).build();
      case Deleted _, NoOwnedFolder _ -> Health.up().build();
    };
  }
}
