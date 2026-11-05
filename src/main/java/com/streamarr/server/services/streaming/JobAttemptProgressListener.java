package com.streamarr.server.services.streaming;

import com.streamarr.server.services.streaming.events.JobAttemptProgressedEvent;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;

/**
 * Restarts a job attempt's stall clock each time its worker reports that the output advanced, so a
 * producer of a long merged segment is not taken for stalled while it works (ADR 0037).
 */
@RequiredArgsConstructor
public class JobAttemptProgressListener {

  @NonNull private final ProducerLifecycleService producerLifecycle;

  @EventListener
  public void onJobAttemptProgressed(@NonNull JobAttemptProgressedEvent event) {
    producerLifecycle.restartStallClock(
        event.streamSessionId(), event.variantLabel(), event.jobAttemptId());
  }
}
