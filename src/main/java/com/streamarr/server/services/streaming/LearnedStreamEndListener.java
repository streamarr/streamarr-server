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
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.NonNull;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * Learns the end time of each stream whose container header states none from the first job attempt
 * that reads the stream's last sample, and stores it beside the probe facts (ADR 0037, early ends).
 */
public class LearnedStreamEndListener implements AutoCloseable {

  private static final String STORE_FAILURES_METRIC =
      "streamarr.streaming.learned_stream_end_store_failures";
  // A store is one short transaction, so an end already handed to the store is kept. A store that
  // still waits after this time waits for the media file's row or for a pooled connection, which
  // can take a minute. A shutdown does not wait for that.
  private static final Duration CLOSE_BOUND = Duration.ofSeconds(1);

  private final RuntimeStreamSessionRegistry runtimeRegistry;
  private final LearnedStreamEndRepository learnedStreamEnds;
  private final Counter storeFailures;
  // The listener runs inside the worker's completion report. A store can wait on the media file's
  // row lock, and waiting there would hold back every later report from that worker.
  private final ExecutorService learning = Executors.newVirtualThreadPerTaskExecutor();
  private volatile boolean storesCancelled;

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
  // Runs before the other listeners of the completion: short-copy recovery may end the stream
  // session, and the ends are read from it.
  @EventListener
  @Order(Ordered.HIGHEST_PRECEDENCE)
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
    if (unstated.isEmpty() || snapshot.isEmpty()) {
      return;
    }

    var mediaFileId = session.getMediaFileId();
    var source = session.getSourcePath();
    try {
      learning.execute(
          () ->
              storeIfUnchanged(
                  source, snapshot.get(), toLearned(mediaFileId, snapshot.get(), unstated)));
    } catch (RejectedExecutionException _) {
      // The server is shutting down; the next attempt that reads past the end learns it.
    }
  }

  private static List<LearnedStreamEnd> toLearned(
      UUID mediaFileId, SourceFileSnapshot snapshot, List<SourceStreamEnd> ends) {
    return ends.stream()
        .map(
            end ->
                LearnedStreamEnd.builder()
                    .mediaFileId(mediaFileId)
                    .snapshot(snapshot)
                    .streamIndex(end.streamIndex())
                    .endTime(end.endTime())
                    .build())
        .toList();
  }

  private void storeIfUnchanged(
      Path source, SourceFileSnapshot snapshot, List<LearnedStreamEnd> learned) {
    if (!currentSnapshot(source).equals(Optional.of(snapshot))) {
      return;
    }

    learned.stream().takeWhile(_ -> !storesCancelled).forEach(this::store);
  }

  // A store that close cancelled is no failure of the database: a later attempt that reads the
  // stream's last sample reports the end again.
  private void store(LearnedStreamEnd learned) {
    try {
      learnedStreamEnds.store(learned);
    } catch (DataAccessException | TransactionException _) {
      if (storesCancelled) {
        return;
      }

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

  /**
   * Waits one second for the ends already handed to the store, then cancels the stores that still
   * run. Later completions learn nothing.
   */
  @Override
  public void close() {
    learning.shutdown();
    try {
      if (!learning.awaitTermination(CLOSE_BOUND.toMillis(), TimeUnit.MILLISECONDS)) {
        cancelStores();
      }
    } catch (InterruptedException _) {
      cancelStores();
      Thread.currentThread().interrupt();
    }
  }

  private void cancelStores() {
    storesCancelled = true;
    learning.shutdownNow();
  }
}
