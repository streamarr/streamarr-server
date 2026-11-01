package com.streamarr.server.services.streaming;

import com.streamarr.server.services.streaming.events.CopiedVariantRanShortEvent;
import com.streamarr.server.services.streaming.events.JobAttemptCompletedEvent;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

/** Settles where a stream copy ran short when one of its job attempts completes (ADR 0052). */
@RequiredArgsConstructor
public class ShortCopyListener {

  @NonNull private final ProducerLifecycleService producerLifecycle;
  @NonNull private final ApplicationEventPublisher eventPublisher;

  /**
   * A copy that ran short while a source stream still has media after its last segment fails as a
   * copy does, so the playback session falls back to an encode. A copy that ran short after every
   * source stream ended ends playback normally instead.
   */
  @EventListener
  public void onJobAttemptCompleted(@NonNull JobAttemptCompletedEvent event) {
    producerLifecycle
        .settleCompletedJobAttempt(
            event.streamSessionId(), event.variantLabel(), event.jobAttemptId())
        .filter(shortCopy -> !shortCopy.everySourceStreamEnded())
        .ifPresent(
            _ ->
                eventPublisher.publishEvent(
                    CopiedVariantRanShortEvent.builder()
                        .streamSessionId(event.streamSessionId())
                        .jobAttemptId(event.jobAttemptId())
                        .variantLabel(event.variantLabel())
                        .build()));
  }
}
