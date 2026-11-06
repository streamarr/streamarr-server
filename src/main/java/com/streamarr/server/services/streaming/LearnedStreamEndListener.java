package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.media.LearnedStreamEnd;
import com.streamarr.server.domain.streaming.SourceStreamEnd;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.repositories.media.LearnedStreamEndRepository;
import com.streamarr.server.services.streaming.events.JobAttemptCompletedEvent;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;

/**
 * Learns the end time of each stream whose container header states none from the first job attempt
 * that reads the stream's last sample, and stores it beside the probe facts (ADR 0037, early ends).
 */
@RequiredArgsConstructor
public class LearnedStreamEndListener {

  @NonNull private final RuntimeStreamSessionRegistry runtimeRegistry;
  @NonNull private final LearnedStreamEndRepository learnedStreamEnds;

  /**
   * The end holds for the source file that the stream session's probe outcome describes. A session
   * that has ended, or whose probe names no source file, learns nothing.
   */
  @EventListener
  public void onJobAttemptCompleted(@NonNull JobAttemptCompletedEvent event) {
    runtimeRegistry
        .findById(event.streamSessionId())
        .ifPresent(
            session ->
                event.streamEnds().stream()
                    .filter(end -> session.getMediaProbe().statesNoEndTimeFor(end.streamIndex()))
                    .forEach(end -> learn(session, end)));
  }

  private void learn(StreamSession session, SourceStreamEnd end) {
    session
        .getMediaProbe()
        .sourceSnapshot()
        .map(
            snapshot ->
                LearnedStreamEnd.builder()
                    .mediaFileId(session.getMediaFileId())
                    .snapshot(snapshot)
                    .streamIndex(end.streamIndex())
                    .endTime(end.endTime())
                    .build())
        .ifPresent(learnedStreamEnds::store);
  }
}
