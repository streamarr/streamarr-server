package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.ArtworkBacklog;
import com.streamarr.server.domain.media.ArtworkCompletionEvidence;
import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.services.NewArtworkRequest;
import com.streamarr.server.services.PendingArtworkRequest;
import com.streamarr.server.services.StoredArtworkRequest;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stores at most one artwork request per target. A request with changed sources or a stronger
 * refresh mode starts a new generation; a request that changes neither keeps the pending
 * generation. A library operation's request takes over the pending request: the request then
 * carries the operation's id, and a request from outside an operation keeps the id that the pending
 * request carries.
 */
public interface ArtworkRequestRepository {

  /** How {@link #saveRequest} left the target's request. */
  sealed interface SavedArtworkRequest {

    long generation();

    /** The pending request already covered the new one; nothing was written. */
    record Unchanged(long generation) implements SavedArtworkRequest {}

    /**
     * A library operation took over the pending request, which covered the new one: the request and
     * each result of its generation that no retry will replace now carry the operation's id. The
     * caller's transaction holds the request's row lock until it ends.
     */
    record TakenOver(long generation) implements SavedArtworkRequest {}

    /**
     * The request was inserted, or replaced a pending one, under a new generation. The caller's
     * transaction holds the request's row lock until it ends.
     */
    record NewGeneration(long generation) implements SavedArtworkRequest {}
  }

  /**
   * Saves the target's artwork request in the caller's transaction. A changed request keeps the
   * stronger of the pending and the requested refresh mode. A request that changes nothing takes no
   * row lock.
   */
  SavedArtworkRequest saveRequest(NewArtworkRequest request);

  Optional<StoredArtworkRequest> findRequest(ArtworkTarget target);

  /**
   * Locks the target's request until the caller's transaction ends and returns its generation and
   * library operation id, or nothing when no request is pending.
   */
  Optional<PendingArtworkRequest> lockPendingRequest(ArtworkTarget target);

  /**
   * Deletes the target's request in the caller's transaction when {@code generation} is still the
   * pending one. Returns {@code false} without deleting when a newer generation replaced it, and
   * {@code true} when no request remains.
   */
  boolean tryCompleteGeneration(ArtworkTarget target, long generation);

  /**
   * Returns the completion evidence of each target: its pending generation and its artwork results.
   * A deleted target has neither.
   */
  List<ArtworkCompletionEvidence> findCompletionEvidence(Collection<ArtworkTarget> targets);

  /**
   * Returns the artwork backlog of each target type: the images that pending requests still need,
   * and when the oldest of those requests was first made. A request that a changed request replaced
   * keeps its first request time.
   */
  Map<ImageEntityType, ArtworkBacklog> findArtworkBacklogByTargetType();
}
