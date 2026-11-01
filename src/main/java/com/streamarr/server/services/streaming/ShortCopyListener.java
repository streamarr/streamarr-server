package com.streamarr.server.services.streaming;

import com.streamarr.server.services.streaming.events.JobAttemptCompletedEvent;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;

/** Settles where a stream copy ran short when one of its job attempts completes (ADR 0052). */
@RequiredArgsConstructor
public class ShortCopyListener {

  @NonNull private final ProducerLifecycleService producerLifecycle;

  @EventListener
  public void onJobAttemptCompleted(@NonNull JobAttemptCompletedEvent event) {
    producerLifecycle.settleCompletedJobAttempt(
        event.streamSessionId(), event.variantLabel(), event.jobAttemptId());
  }
}
