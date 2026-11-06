package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.media.LearnedStreamEnd;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.SourceStreamEnd;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.repositories.media.LearnedStreamEndRepository;
import com.streamarr.server.services.streaming.events.JobAttemptCompletedEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;
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
   * The end holds for the source file that the stream session's probe outcome describes, and only
   * while the file at the session's source path still holds that outcome's snapshot. A session that
   * has ended, or whose probe names no source file, learns nothing.
   */
  @EventListener
  public void onJobAttemptCompleted(@NonNull JobAttemptCompletedEvent event) {
    runtimeRegistry
        .findById(event.streamSessionId())
        .ifPresent(session -> learnFrom(session, event.streamEnds()));
  }

  private void learnFrom(StreamSession session, List<SourceStreamEnd> streamEnds) {
    var probe = session.getMediaProbe();
    var unstated =
        streamEnds.stream().filter(end -> probe.statesNoEndTimeFor(end.streamIndex())).toList();
    var snapshot = probe.sourceSnapshot();
    if (unstated.isEmpty()
        || snapshot.isEmpty()
        || !currentSnapshot(session.getSourcePath()).equals(snapshot)) {
      return;
    }

    unstated.forEach(
        end ->
            learnedStreamEnds.store(
                LearnedStreamEnd.builder()
                    .mediaFileId(session.getMediaFileId())
                    .snapshot(snapshot.get())
                    .streamIndex(end.streamIndex())
                    .endTime(end.endTime())
                    .build()));
  }

  // The worker reports no snapshot of the file it read, so the file must still hold the probed
  // snapshot when the attempt completes: an end read from a changed file never counts for the
  // earlier version.
  private static Optional<SourceFileSnapshot> currentSnapshot(Path source) {
    try {
      return Optional.of(
          SourceFileSnapshot.of(Files.readAttributes(source, BasicFileAttributes.class)));
    } catch (IOException _) {
      return Optional.empty();
    }
  }
}
