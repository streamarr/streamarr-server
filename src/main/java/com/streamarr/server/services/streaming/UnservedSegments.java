package com.streamarr.server.services.streaming;

import com.streamarr.server.services.streaming.events.MediaSegmentStoredEvent;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Counts the media segments that the server stored for an HLS stream session and removed without
 * answering a viewer's request with them.
 *
 * <p>A stream session is followed from {@link #streamSessionStarts} until one of the counts, which
 * each caller makes after it deleted the session's stored segments. Nothing is kept for a stream
 * session that is not followed.
 *
 * <p>The count is approximate. Each of these races can put it off by one segment: a request that is
 * answered while its session's segments are counted, a stored segment that is announced after that
 * count, and a request that is refused for its range after the segment was opened for it.
 */
@Component
@RequiredArgsConstructor
public class UnservedSegments {

  @NonNull private final UnservedSegmentMetrics metrics;

  private final ConcurrentHashMap<UUID, SessionSegments> followedBySession =
      new ConcurrentHashMap<>();
  private volatile boolean serverShuttingDown;

  /** Call before the stream session's first job attempt starts, so before it stores a segment. */
  void streamSessionStarts(UUID sessionId) {
    followedBySession.put(sessionId, SessionSegments.none());
  }

  @EventListener
  public void onMediaSegmentStored(@NonNull MediaSegmentStoredEvent event) {
    var segments = followedBySession.get(event.streamSessionId());
    if (segments != null) {
      segments.stored().add(event.storedSegmentName());
    }
  }

  void served(UUID sessionId, String storedSegmentName) {
    var segments = followedBySession.get(sessionId);
    if (segments != null) {
      segments.served().add(storedSegmentName);
    }
  }

  /**
   * Call before the server's shutdown ends the stream sessions. From then on, a stream session that
   * ends is counted as removed by the shutdown.
   */
  void serverShutdownBegins() {
    serverShuttingDown = true;
  }

  void countAfterSessionEnded(UUID sessionId) {
    var unserved = stopFollowing(sessionId);
    if (serverShuttingDown) {
      metrics.countAtServerShutdown(unserved);
      return;
    }

    metrics.countAfterSessionEnded(unserved);
  }

  void countAfterDiskPressureEviction(UUID sessionId) {
    metrics.countAfterDiskPressureEviction(stopFollowing(sessionId));
  }

  void countAfterSessionStartFailed(UUID sessionId) {
    metrics.countAfterSessionStartFailed(stopFollowing(sessionId));
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

  // The map hands a session's segments to one caller, so a session is counted once.
  private int stopFollowing(UUID sessionId) {
    var segments = followedBySession.remove(sessionId);
    if (segments == null) {
      return 0;
    }

    return segments.unserved();
  }

  /**
   * One stream session's segments, by the name under which the segment store keeps each. A served
   * segment stays served when a job attempt stores it again, and a request can be answered with a
   * segment before the segment is announced as stored.
   */
  private record SessionSegments(Set<String> stored, Set<String> served) {

    static SessionSegments none() {
      return new SessionSegments(ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet());
    }

    int unserved() {
      return (int) stored.stream().filter(name -> !served.contains(name)).count();
    }
  }
}
