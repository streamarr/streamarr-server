package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.ArtworkResultCounts;
import com.streamarr.server.domain.media.ArtworkStepsAttempt;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.repositories.media.ItemResultRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Stores results through another repository, but fails each single-result write that the predicate
 * selects, as a lost connection would. Artwork result batches and reads always reach the other
 * repository.
 */
@RequiredArgsConstructor
public final class WriteFailingItemResultRepository implements ItemResultRepository {

  @NonNull private final ItemResultRepository delegate;
  @NonNull private final Predicate<ItemResult> failsWrite;

  @Override
  public boolean trySave(ItemResult result) {
    failIfSelected(result);
    return delegate.trySave(result);
  }

  @Override
  public void overwrite(ItemResult result) {
    failIfSelected(result);
    delegate.overwrite(result);
  }

  @Override
  public boolean trySaveArtworkResults(ArtworkStepsAttempt attempt, List<ItemResult> results) {
    return delegate.trySaveArtworkResults(attempt, results);
  }

  @Override
  public List<ItemResult> findByItem(UUID itemId, ImageEntityType itemType) {
    return delegate.findByItem(itemId, itemType);
  }

  @Override
  public Set<UUID> findItemIdsWithMetadataResultForLibraryOperation(UUID libraryOperationId) {
    return delegate.findItemIdsWithMetadataResultForLibraryOperation(libraryOperationId);
  }

  @Override
  public ArtworkResultCounts countArtworkOutcomesForLibraryOperation(UUID libraryOperationId) {
    return delegate.countArtworkOutcomesForLibraryOperation(libraryOperationId);
  }

  @Override
  public Map<ImageEntityType, ArtworkResultCounts> countArtworkOutcomesRecordedSince(
      Instant since) {
    return delegate.countArtworkOutcomesRecordedSince(since);
  }

  private void failIfSelected(ItemResult result) {
    if (failsWrite.test(result)) {
      throw new DataAccessResourceFailureException("simulated connection loss");
    }
  }
}
