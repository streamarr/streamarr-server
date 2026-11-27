package com.streamarr.server.services.streaming;

import com.streamarr.server.services.streaming.events.MediaSegmentStoredEvent;
import jakarta.annotation.PreDestroy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Follows the media segments that each HLS stream session stores, from the session's start until
 * the server removes them, and counts those that it never answered a viewer's request with.
 *
 * <p>The streaming service reaches a session's stored segments through this component. It opens a
 * segment for a viewer's request here, which marks the segment as served, and it deletes a
 * session's segments here, which counts the unserved ones after the deletion and stops following
 * the session. Nothing is kept for a stream session that is not followed.
 */
@Component
@RequiredArgsConstructor
public class UnservedSegments {

  @NonNull private final SegmentStore segmentStore;
  @NonNull private final UnservedSegmentMetrics metrics;

  private final ConcurrentHashMap<UUID, FollowedSegments> followedBySession =
      new ConcurrentHashMap<>();
  private volatile boolean serverShuttingDown;

  /** Call before the stream session's first job attempt starts, so before it stores a segment. */
  void streamSessionStarts(UUID sessionId) {
    followedBySession.put(sessionId, new FollowedSegments());
  }

  @EventListener
  public void onMediaSegmentStored(@NonNull MediaSegmentStoredEvent event) {
    var followed = followedBySession.get(event.streamSessionId());
    if (followed != null) {
      followed.stored(event.storedSegmentName());
    }
  }

  /**
   * Opens a segment to answer a viewer's request with it, and marks the segment as served. Empty
   * when the segment store holds no such segment. Also empty, with the opened segment closed, when
   * the stream session's segments were deleted and counted between the open and the mark: the
   * answer would serve a segment that is already counted as unserved. A read that answers no viewer
   * goes to the segment store instead.
   */
  Optional<StoredSegment> openForViewer(UUID sessionId, String storedSegmentName) {
    // Read before the open, so that the mark fails once these segments are deleted and counted.
    // A lookup after the open could not tell that deletion from a stream session never followed.
    var followed = followedBySession.get(sessionId);
    var opened = segmentStore.openSegment(sessionId, storedSegmentName);
    if (followed == null || opened.isEmpty() || followed.tryMarkServed(storedSegmentName)) {
      return opened;
    }

    opened.ifPresent(StoredSegment::close);
    return Optional.empty();
  }

  /**
   * Call before the server's shutdown ends the stream sessions. From then on, a stream session that
   * ends is counted as removed by the shutdown.
   */
  void serverShutdownBegins() {
    serverShuttingDown = true;
  }

  /**
   * Stops following a stream session whose start was refused before any job attempt ran. Such a
   * session stored nothing, so nothing is deleted or counted.
   */
  void streamSessionRefused(UUID sessionId) {
    followedBySession.remove(sessionId);
  }

  void deleteSegmentsOfEndedSession(UUID sessionId) {
    deleteThenCount(sessionId, this::countAfterSessionEnded);
  }

  /** For a stream session that a disk-pressure eviction ended. */
  void deleteSegmentsOfEvictedSession(UUID sessionId) {
    deleteThenCount(sessionId, metrics::countAfterDiskPressureEviction);
  }

  void deleteSegmentsOfSessionWhoseStartFailed(UUID sessionId) {
    deleteThenCount(sessionId, metrics::countAfterSessionStartFailed);
  }

  // Counted after the deletion, from which on the segment store opens nothing of the session. A
  // failed deletion still ends the session, so its unserved segments still count.
  private void deleteThenCount(UUID sessionId, IntConsumer countUnserved) {
    try {
      segmentStore.deleteSession(sessionId);
    } finally {
      countUnserved.accept(stopFollowing(sessionId));
    }
  }

  private void countAfterSessionEnded(int unserved) {
    if (serverShuttingDown) {
      metrics.countAtServerShutdown(unserved);
      return;
    }

    metrics.countAfterSessionEnded(unserved);
  }

  /**
   * Counts the unserved segments of every stream session that is still followed when the server
   * stops: the segment store deletes those sessions' segments as it shuts down. The shutdown ends
   * the other stream sessions before, and each of those is counted as it ends.
   */
  @PreDestroy
  public void serverShutsDown() {
    List.copyOf(followedBySession.keySet())
        .forEach(sessionId -> metrics.countAtServerShutdown(stopFollowing(sessionId)));
  }

  // The number of the stream session's unserved segments, or zero when it is not followed.
  private int stopFollowing(UUID sessionId) {
    var followed = followedBySession.remove(sessionId);
    if (followed == null) {
      return 0;
    }

    return followed.removeAll();
  }

  /**
   * One stream session's segments, by the name under which the segment store keeps each, and
   * whether a viewer's request was answered with each. Only a media segment is announced as stored,
   * so only a media segment can be unserved. Every method runs under the instance's monitor, so a
   * mark and the removal never interleave.
   */
  private static final class FollowedSegments {

    private final Map<String, Boolean> servedByStoredName = new HashMap<>();
    private boolean removed;

    // A served segment stays served: a job attempt can store a segment again under its name, and a
    // request can be answered with a segment before the segment is announced as stored.
    synchronized void stored(String storedSegmentName) {
      servedByStoredName.putIfAbsent(storedSegmentName, false);
    }

    /** Marks the segment as served unless the segments were already removed and counted. */
    synchronized boolean tryMarkServed(String storedSegmentName) {
      if (removed) {
        return false;
      }

      servedByStoredName.put(storedSegmentName, true);
      return true;
    }

    /** Ends every later mark and answers how many stored segments were never served. */
    synchronized int removeAll() {
      removed = true;
      return Collections.frequency(servedByStoredName.values(), false);
    }
  }
}
