package com.streamarr.server.services.library;

import com.streamarr.server.domain.task.BitrateScanPriority;
import com.streamarr.server.repositories.CancellableDatabaseWork;
import com.streamarr.server.repositories.media.BitrateScanPriorityRepository;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.events.library.UnmeasuredCopyStartedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Moves a file's bitrate scan ahead of every other bitrate scan when a stream session copies the
 * file's video without a measurement, so the viewer's next stream session of the file gets the
 * measured cost (ADR 0054). The scan stays behind keyframe packet scans and still waits for the
 * keyframe packet scan capacity. Only the scan's priority changes, and the change runs on its own
 * thread, so no stream session waits for a bitrate scan.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnmeasuredCopyListener implements AutoCloseable {

  static final String MOVED_SCANS_METRIC = "streamarr.probes.bitrate_scans_moved_forward";
  // A cancelled raise ends within milliseconds and then counts its outcome. Without this wait the
  // meter registry, which closes after the listener, could close before the count.
  private static final Duration CLOSE_BOUND = Duration.ofSeconds(1);

  private final BitrateScanPriorityRepository priorities;
  private final MeterRegistry meterRegistry;
  // The listener runs on the thread that starts the stream session. A raise can wait for the
  // scan's row lock, and waiting there would hold back the playback request.
  private final ExecutorService raises =
      SystemWork.newExecutorRunningAsSystemActor("bitrate-scan-raise-");
  private final CancellableDatabaseWork raiseStatements = new CancellableDatabaseWork();
  // One raise per file runs at a time: a second one would wait for the same row to write the same
  // priority.
  private final Set<UUID> filesWithARunningRaise = ConcurrentHashMap.newKeySet();

  @EventListener
  public void onUnmeasuredCopyStarted(UnmeasuredCopyStartedEvent event) {
    var mediaFileId = event.mediaFileId();
    if (!filesWithARunningRaise.add(mediaFileId)) {
      count("already_moving");
      return;
    }

    try {
      raises.execute(() -> moveForward(mediaFileId));
    } catch (RejectedExecutionException _) {
      // The server is shutting down. The scan keeps its place until the next unmeasured copy.
      filesWithARunningRaise.remove(mediaFileId);
      count("cancelled");
    }
  }

  // The file leaves the set before its outcome counts, so a stream session that starts once the
  // outcome is visible raises the scan again.
  private void moveForward(UUID mediaFileId) {
    String outcome;
    try {
      outcome = raise(mediaFileId);
    } finally {
      filesWithARunningRaise.remove(mediaFileId);
    }

    count(outcome);
  }

  // Nothing waits for this thread, so the count is the only outcome of a raise that fails, for
  // whatever reason. A scan that cannot move keeps its place. A raise that close cancelled is not
  // failed: the server shut down before the raise got its row or its connection.
  private String raise(UUID mediaFileId) {
    try {
      var raised =
          raiseStatements.call(
              () ->
                  priorities.tryRaisePriority(
                      mediaFileId, BitrateScanPriority.COPIED_BY_STREAM_SESSION));
      if (raised.isEmpty()) {
        return "cancelled";
      }

      if (raised.get()) {
        return "moved";
      }

      return "unchanged";
    } catch (RuntimeException failure) {
      log.warn("Could not move the bitrate scan of media file {} forward", mediaFileId, failure);
      return "failed";
    }
  }

  private void count(String outcome) {
    meterRegistry.counter(MOVED_SCANS_METRIC, "outcome", outcome).increment();
  }

  /**
   * Cancels every raise already started, so a shutdown never waits for a scan's row or for a pooled
   * connection, and returns once they have ended or after one second. A later unmeasured copy moves
   * no scan.
   */
  @Override
  public void close() {
    raises.shutdown();
    var cancelled = raiseStatements.cancel();
    if (cancelled > 0) {
      log.info("Raises of bitrate scans cancelled at shutdown: {}", cancelled);
    }

    try {
      if (!raises.awaitTermination(CLOSE_BOUND.toMillis(), TimeUnit.MILLISECONDS)) {
        log.warn("A raise of a bitrate scan still ran {} after its cancel", CLOSE_BOUND);
      }
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }
}
