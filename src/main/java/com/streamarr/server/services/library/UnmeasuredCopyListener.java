package com.streamarr.server.services.library;

import com.streamarr.server.repositories.media.BitrateScanPriorityRepository;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.events.library.UnmeasuredCopyStartedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
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

  private final BitrateScanPriorityRepository priorities;
  private final MeterRegistry meterRegistry;
  // The listener runs on the thread that starts the stream session. A raise can wait for the
  // scan's row lock, and waiting there would hold back the playback request.
  private final ExecutorService raises =
      SystemWork.newExecutorRunningAsSystemActor("bitrate-scan-raise-");
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
      count("failed");
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
  // whatever reason. A scan that cannot move keeps its place.
  private String raise(UUID mediaFileId) {
    try {
      if (priorities.tryRaisePriority(mediaFileId, BitrateScanPriority.COPIED_BY_STREAM_SESSION)) {
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

  /** Waits for every raise already started. A later unmeasured copy moves no scan. */
  @Override
  public void close() {
    raises.close();
  }
}
