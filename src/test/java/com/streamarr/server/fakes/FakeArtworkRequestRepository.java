package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.ArtworkItem;
import com.streamarr.server.domain.media.ArtworkState;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.services.ArtworkSources;
import com.streamarr.server.services.PendingArtworkRequest;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps artwork requests in memory, under the rules of {@code ArtworkRequestRepositoryContract}.
 */
public class FakeArtworkRequestRepository implements ArtworkRequestRepository {

  private static final Comparator<ImageSource> SOURCE_ORDER =
      Comparator.comparing(ImageSource::imageType).thenComparing(ImageSource::key);

  private final Map<ArtworkItem, PendingArtworkRequest> requests = new HashMap<>();
  private final FakeItemResultRepository itemResults;
  private long lastGeneration;

  public FakeArtworkRequestRepository(FakeItemResultRepository itemResults) {
    this.itemResults = itemResults;
  }

  @Override
  public synchronized SavedArtworkRequest saveRequest(
      ArtworkSources artwork, ImageRefreshMode refreshMode, Instant requestedAt) {
    var sorted =
        artwork.toBuilder()
            .sources(artwork.sources().stream().sorted(SOURCE_ORDER).toList())
            .build();
    var pending = requests.get(artwork.item());
    if (pending != null && covers(pending, sorted, refreshMode)) {
      return new SavedArtworkRequest.Unchanged(pending.generation());
    }

    var mode = refreshMode;
    if (pending != null && pending.refreshMode().compareTo(refreshMode) > 0) {
      mode = pending.refreshMode();
    }

    var generation = ++lastGeneration;
    requests.put(
        artwork.item(),
        PendingArtworkRequest.builder()
            .artwork(sorted)
            .refreshMode(mode)
            .generation(generation)
            .build());
    return new SavedArtworkRequest.NewGeneration(generation);
  }

  @Override
  public synchronized Optional<PendingArtworkRequest> findRequest(ArtworkItem item) {
    return Optional.ofNullable(requests.get(item));
  }

  @Override
  public synchronized Optional<Long> lockPendingGeneration(ArtworkItem item) {
    return findRequest(item).map(PendingArtworkRequest::generation);
  }

  @Override
  public synchronized boolean tryCompleteGeneration(ArtworkItem item, long generation) {
    var pending = requests.get(item);
    if (pending == null) {
      return true;
    }

    if (pending.generation() != generation) {
      return false;
    }

    requests.remove(item);
    return true;
  }

  @Override
  public synchronized List<ArtworkState> findArtworkStates(Collection<ArtworkItem> items) {
    return items.stream()
        .distinct()
        .map(
            item ->
                new ArtworkState(
                    item,
                    findRequest(item).map(PendingArtworkRequest::generation),
                    itemResults.findByItem(item.itemId(), item.itemType()).stream()
                        .filter(result -> result.step() == ItemStep.ARTWORK)
                        .toList()))
        .toList();
  }

  @Override
  public synchronized Map<ImageEntityType, Long> countPendingImagesByItemType() {
    var counts = new EnumMap<ImageEntityType, Long>(ImageEntityType.class);
    requests
        .values()
        .forEach(
            request ->
                counts.merge(
                    request.artwork().entityType(),
                    (long) request.artwork().requestedImageTypes().size(),
                    Long::sum));
    return counts;
  }

  /** Deletes the item's request, as deleting the item does. */
  public synchronized void deleteItem(ArtworkItem item) {
    requests.remove(item);
  }

  private static boolean covers(
      PendingArtworkRequest pending, ArtworkSources artwork, ImageRefreshMode refreshMode) {
    if (!pending.artwork().sources().equals(artwork.sources())) {
      return false;
    }

    return refreshMode.compareTo(pending.refreshMode()) <= 0;
  }
}
