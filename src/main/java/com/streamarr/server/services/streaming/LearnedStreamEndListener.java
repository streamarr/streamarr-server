package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.media.LearnedStreamEnd;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.SourceStreamEnd;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.repositories.media.LearnedStreamEndRepository;
import com.streamarr.server.services.streaming.events.JobAttemptCompletedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * Learns the end time of each stream whose container header states none from the first job attempt
 * that reads the stream's last sample, and stores it beside the probe facts (ADR 0037, early ends).
 */
public class LearnedStreamEndListener {

  private static final String STORE_FAILURES_METRIC =
      "streamarr.streaming.learned_stream_end_store_failures";

  private final RuntimeStreamSessionRegistry runtimeRegistry;
  private final LearnedStreamEndRepository learnedStreamEnds;
  private final Counter storeFailures;

  public LearnedStreamEndListener(
      @NonNull RuntimeStreamSessionRegistry runtimeRegistry,
      @NonNull LearnedStreamEndRepository learnedStreamEnds,
      @NonNull MeterRegistry meterRegistry) {
    this.runtimeRegistry = runtimeRegistry;
    this.learnedStreamEnds = learnedStreamEnds;
    storeFailures =
        Counter.builder(STORE_FAILURES_METRIC)
            .description(
                "Stream end times that a completed job attempt reported but the database did not"
                    + " store")
            .register(meterRegistry);
  }

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
            store(
                LearnedStreamEnd.builder()
                    .mediaFileId(session.getMediaFileId())
                    .snapshot(snapshot.get())
                    .streamIndex(end.streamIndex())
                    .endTime(end.endTime())
                    .build()));
  }

  // This listener runs inside the worker's completion report, so a failed write that escaped it
  // would abort the report and close the worker's control stream.
  private void store(LearnedStreamEnd learned) {
    try {
      learnedStreamEnds.store(learned);
    } catch (DataAccessException | TransactionException _) {
      storeFailures.increment();
    }
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
