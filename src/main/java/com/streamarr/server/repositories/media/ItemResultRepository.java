package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.ArtworkResultCounts;
import com.streamarr.server.domain.media.ArtworkStepsAttempt;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ItemResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stores the latest result of each step for each item. An artwork result takes the library
 * operation id that the target's artwork request carries as the result is saved; any other result
 * keeps the library operation id it names.
 */
public interface ItemResultRepository {

  /**
   * Stores the result as the latest one for its item, step, and image type. Returns {@code false}
   * without writing when a result from a later attempt is already stored or the item no longer
   * exists.
   */
  boolean trySave(ItemResult result);

  /**
   * Stores the result whichever attempt it came from. Call it only in the transaction that writes
   * the state the result describes, such as saved artwork, so the stored result matches that state.
   */
  void overwrite(ItemResult result);

  /**
   * Stores the artwork results of one attempt together, so they commit all or none. A result never
   * replaces one that a later attempt of the same generation stored, and a failure never replaces a
   * saved image's result of the same generation. Returns {@code false} without writing when the
   * attempt's generation is no longer the target's pending artwork request or the target no longer
   * exists.
   */
  boolean trySaveArtworkResults(ArtworkStepsAttempt attempt, List<ItemResult> results);

  List<ItemResult> findByItem(UUID itemId, ImageEntityType itemType);

  /** Counts the artwork results recorded since {@code since} by item type and outcome. */
  Map<ImageEntityType, ArtworkResultCounts> countArtworkOutcomesRecordedSince(Instant since);
}
