package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ItemResult;
import java.util.List;
import java.util.UUID;

public interface ItemResultRepository {

  /**
   * Stores the result as the latest one for its item, step, and image type. Returns {@code false}
   * without writing when a result from a later attempt is already stored.
   */
  boolean trySave(ItemResult result);

  /**
   * Stores the result whichever attempt it came from. Call it only in the transaction that writes
   * the state the result describes, such as saved artwork, so the stored result matches that state.
   */
  void overwrite(ItemResult result);

  List<ItemResult> findByItem(UUID itemId, ImageEntityType itemType);
}
