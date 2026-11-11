package com.streamarr.server.services.library;

import com.streamarr.server.repositories.media.BitrateScanPriorityRepository;
import com.streamarr.server.services.events.library.UnmeasuredCopyStartedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Moves a file's bitrate scan ahead of every other bitrate scan when a stream session copies the
 * file's video without a measurement, so the viewer's next stream session of the file gets the
 * measured cost (ADR 0054). The scan stays behind keyframe packet scans and still waits for the
 * keyframe packet scan capacity. Only the scan's priority changes, so no stream session waits for a
 * bitrate scan.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnmeasuredCopyListener {

  static final String MOVED_SCANS_METRIC = "streamarr.probes.bitrate_scans_moved_forward";

  private final BitrateScanPriorityRepository priorities;
  private final MeterRegistry meterRegistry;

  @EventListener
  public void onUnmeasuredCopyStarted(UnmeasuredCopyStartedEvent event) {
    meterRegistry
        .counter(MOVED_SCANS_METRIC, "outcome", moveForward(event.mediaFileId()))
        .increment();
  }

  // The stream session already runs. A scan that cannot move keeps its place, and the stream
  // session keeps running.
  private String moveForward(UUID mediaFileId) {
    try {
      if (priorities.tryRaisePriority(mediaFileId, BitrateScanPriority.COPIED_BY_STREAM_SESSION)) {
        return "moved";
      }

      return "unchanged";
    } catch (DataAccessException failure) {
      log.warn("Could not move the bitrate scan of media file {} forward", mediaFileId, failure);
      return "failed";
    }
  }
}
