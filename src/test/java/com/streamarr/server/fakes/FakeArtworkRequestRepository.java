package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.ArtworkItem;
import com.streamarr.server.domain.media.ArtworkState;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.domain.media.PendingArtwork;
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
  private final Map<ArtworkItem, Instant> firstRequestedAt = new HashMap<>();
  private final FakeItemResultRepository itemResults;
  private long lastGeneration;

  /** Holds the fake item results to this repository's generations, as the database does. */
  public FakeArtworkRequestRepository(FakeItemResultRepository itemResults) {
    this.itemResults = itemResults;
    itemResults.checkArtworkGenerationsWith(this::isPendingGeneration);
  }

  /** Returns every stored artwork request. */
  public synchronized List<PendingArtworkRequest> requests() {
    return List.copyOf(requests.values());
  }

  /** Returns whether {@code generation} is the item's pending artwork request. */
  public synchronized boolean isPendingGeneration(ArtworkItem item, long generation) {
    var pending = requests.get(item);
    return pending != null && pending.generation() == generation;
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
    firstRequestedAt.putIfAbsent(artwork.item(), requestedAt);
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

    remove(item);
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
  public synchronized Map<ImageEntityType, PendingArtwork> findPendingArtworkByItemType() {
    var pending = new EnumMap<ImageEntityType, PendingArtwork>(ImageEntityType.class);
    requests.forEach(
        (item, request) ->
            pending.merge(
                item.itemType(),
                new PendingArtwork(pendingImagesOf(request), firstRequestedAt.get(item)),
                PendingArtwork::plus));
    return pending;
  }

  private long pendingImagesOf(PendingArtworkRequest request) {
    var item = request.artwork().item();
    var finished =
        itemResults.findByItem(item.itemId(), item.itemType()).stream()
            .filter(result -> result.step() == ItemStep.ARTWORK)
            .filter(result -> result.artworkGeneration() >= request.generation())
            .filter(ItemResult::isFinal)
            .count();
    return Math.max(0, request.artwork().requestedImageTypes().size() - finished);
  }

  /** Deletes the item's request, as deleting the item does. */
  public synchronized void deleteItem(ArtworkItem item) {
    remove(item);
  }

  private void remove(ArtworkItem item) {
    requests.remove(item);
    firstRequestedAt.remove(item);
  }

  private static boolean covers(
      PendingArtworkRequest pending, ArtworkSources artwork, ImageRefreshMode refreshMode) {
    if (!pending.artwork().sources().equals(artwork.sources())) {
      return false;
    }

    return refreshMode.compareTo(pending.refreshMode()) <= 0;
  }
}
