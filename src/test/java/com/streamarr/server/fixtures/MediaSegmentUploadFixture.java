package com.streamarr.server.fixtures;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.events.MediaSegmentPublishedEvent;
import java.util.Optional;

/** What the server publishes when a worker's upload stores a media segment. */
public final class MediaSegmentUploadFixture {

  private MediaSegmentUploadFixture() {}

  /**
   * The event of an upload by the job attempt that runs the segment's variant, from the worker that
   * runs it. The stored segment name carries the variant's label unless the variant is the default
   * one. Empty when the name is not a media segment's or no job attempt of the variant runs.
   */
  public static Optional<MediaSegmentPublishedEvent> publishedByRunningJobAttempt(
      StreamSession session, String storedSegmentName, TranscodeExecutor transcodeExecutor) {
    var variantLabel = variantOf(storedSegmentName);
    var index = SegmentNames.indexOf(storedSegmentName);
    var attempt = session.getVariantHandle(variantLabel);
    var worker = transcodeExecutor.runningExecutionTarget(session.getSessionId(), variantLabel);
    if (index.isEmpty() || attempt.isEmpty() || worker.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(
        MediaSegmentPublishedEvent.builder()
            .streamSessionId(session.getSessionId())
            .jobAttemptId(attempt.get().attemptId())
            .variantLabel(variantLabel)
            .segmentIndex(index.getAsInt())
            .executionTarget(worker.get())
            .build());
  }

  private static String variantOf(String storedSegmentName) {
    var slash = storedSegmentName.indexOf('/');
    if (slash < 0) {
      return StreamSession.defaultVariant();
    }

    return storedSegmentName.substring(0, slash);
  }
}
