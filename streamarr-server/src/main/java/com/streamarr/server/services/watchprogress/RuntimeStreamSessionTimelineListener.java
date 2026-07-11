package com.streamarr.server.services.watchprogress;

import com.streamarr.server.services.streaming.CommittedStreamSessionTimeline;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.services.watchprogress.events.StreamSessionTimelineCommittedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class RuntimeStreamSessionTimelineListener {

  private final RuntimeStreamSessionRegistry runtimeRegistry;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onTimelineCommitted(StreamSessionTimelineCommittedEvent event) {
    runtimeRegistry.mirrorCommittedTimeline(
        CommittedStreamSessionTimeline.builder()
            .streamSessionId(event.sessionId())
            .positionSeconds(event.positionSeconds())
            .state(event.state())
            .accessedAt(event.accessedAt())
            .build());
  }
}
