package com.streamarr.server.services.streaming;

import com.streamarr.server.services.streaming.events.MediaSegmentPublishedEvent;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;

/**
 * Credits each stored media segment to the job attempt that uploaded it, so that seek priority
 * counts only a job attempt's own output toward its lead (ADR 0044).
 */
@RequiredArgsConstructor
public class MediaSegmentPublicationListener {

  @NonNull private final ProducerLifecycleService producerLifecycle;

  @EventListener
  public void onMediaSegmentPublished(@NonNull MediaSegmentPublishedEvent event) {
    producerLifecycle.mediaSegmentPublished(event);
  }
}
