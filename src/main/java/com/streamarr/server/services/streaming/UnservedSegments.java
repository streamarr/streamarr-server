package com.streamarr.server.services.streaming;

import com.streamarr.server.services.streaming.events.MediaSegmentStoredEvent;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Follows the media segments that each HLS stream session stores, from the session's start until
 * the server removes them, and counts those that it never answered a viewer's request with.
 *
 * <p>A stream session is followed from {@link #streamSessionStarts} until the call that reports the
 * removal of its segments. Each of those calls counts the session's unserved segments once and
 * stops following the session. Nothing is kept for a stream session that is not followed.
 */
@Component
@RequiredArgsConstructor
public class UnservedSegments {

  @NonNull private final UnservedSegmentMetrics metrics;

  private final ConcurrentHashMap<UUID, FollowedSegments> followedBySession =
      new ConcurrentHashMap<>();

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
   * when {@code open} finds no segment. Also empty, with the opened segment closed, when the stream
   * session's segments were removed and counted between the open and the mark: the answer would
   * serve a segment that is already counted as unserved. A read that answers no viewer opens the
   * segment in the segment store instead.
   */
  Optional<StoredSegment> openForViewer(
      UUID sessionId, String storedSegmentName, Supplier<Optional<StoredSegment>> open) {
    // Read before the open, so that the mark fails once these segments are removed and counted.
    // A lookup after the open could not tell that removal from a stream session never followed.
    var followed = followedBySession.get(sessionId);
    var opened = open.get();
    if (followed == null || opened.isEmpty() || followed.tryMarkServed(storedSegmentName)) {
      return opened;
    }

    opened.ifPresent(StoredSegment::close);
    return Optional.empty();
  }

  /** Call after the ended stream session's stored segments are deleted. */
  void streamSessionEnded(UUID sessionId) {
    metrics.countAfterSessionEnded(stopFollowing(sessionId));
  }

  /** Call after the evicted stream session's stored segments are deleted. */
  void streamSessionEvictedUnderDiskPressure(UUID sessionId) {
    metrics.countAfterDiskPressureEviction(stopFollowing(sessionId));
  }

  /** Call after the stored segments of the stream session whose start failed are deleted. */
  void streamSessionStartFailed(UUID sessionId) {
    metrics.countAfterSessionStartFailed(stopFollowing(sessionId));
  }

  /**
   * Counts the unserved segments of every stream session that is still followed when the server
   * stops: the segment store deletes those sessions' segments as it shuts down. A stream session
   * that the shutdown ended before is counted as ended.
   */
  @PreDestroy
  public void serverShutsDown() {
    List.copyOf(followedBySession.keySet())
        .forEach(sessionId -> metrics.countAtServerShutdown(stopFollowing(sessionId)));
  }

  // The number of the stream session's unserved segments, or zero when it is not followed.
  private long stopFollowing(UUID sessionId) {
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
    synchronized long removeAll() {
      removed = true;
      return servedByStoredName.values().stream().filter(served -> !served).count();
    }
  }
}
