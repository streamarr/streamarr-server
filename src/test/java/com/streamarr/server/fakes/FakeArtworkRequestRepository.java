package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.ArtworkState;
import com.streamarr.server.domain.media.ArtworkTarget;
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

  private final Map<ArtworkTarget, PendingArtworkRequest> requests = new HashMap<>();
  private final Map<ArtworkTarget, Instant> firstRequestedAt = new HashMap<>();
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

  /** Returns whether {@code generation} is the target's pending artwork request. */
  public synchronized boolean isPendingGeneration(ArtworkTarget target, long generation) {
    var pending = requests.get(target);
    return pending != null && pending.generation() == generation;
  }

  @Override
  public synchronized SavedArtworkRequest saveRequest(
      ArtworkSources artwork, ImageRefreshMode refreshMode, Instant requestedAt) {
    var sorted =
        artwork.toBuilder()
            .sources(artwork.sources().stream().sorted(SOURCE_ORDER).toList())
            .build();
    var pending = requests.get(artwork.target());
    if (pending != null && covers(pending, sorted, refreshMode)) {
      return new SavedArtworkRequest.Unchanged(pending.generation());
    }

    var mode = refreshMode;
    if (pending != null && pending.refreshMode().compareTo(refreshMode) > 0) {
      mode = pending.refreshMode();
    }

    var generation = ++lastGeneration;
    firstRequestedAt.putIfAbsent(artwork.target(), requestedAt);
    requests.put(
        artwork.target(),
        PendingArtworkRequest.builder()
            .artwork(sorted)
            .refreshMode(mode)
            .generation(generation)
            .build());
    return new SavedArtworkRequest.NewGeneration(generation);
  }

  @Override
  public synchronized Optional<PendingArtworkRequest> findRequest(ArtworkTarget target) {
    return Optional.ofNullable(requests.get(target));
  }

  @Override
  public synchronized Optional<Long> lockPendingGeneration(ArtworkTarget target) {
    return findRequest(target).map(PendingArtworkRequest::generation);
  }

  @Override
  public synchronized boolean tryCompleteGeneration(ArtworkTarget target, long generation) {
    var pending = requests.get(target);
    if (pending == null) {
      return true;
    }

    if (pending.generation() != generation) {
      return false;
    }

    remove(target);
    return true;
  }

  @Override
  public synchronized List<ArtworkState> findArtworkStates(Collection<ArtworkTarget> targets) {
    return targets.stream()
        .distinct()
        .map(
            target ->
                new ArtworkState(
                    target,
                    findRequest(target).map(PendingArtworkRequest::generation),
                    itemResults.findByItem(target.targetId(), target.targetType()).stream()
                        .filter(result -> result.step() == ItemStep.ARTWORK)
                        .toList()))
        .toList();
  }

  @Override
  public synchronized Map<ImageEntityType, PendingArtwork> findPendingArtworkByItemType() {
    var pending = new EnumMap<ImageEntityType, PendingArtwork>(ImageEntityType.class);
    requests.forEach(
        (target, request) ->
            pending.merge(
                target.targetType(),
                new PendingArtwork(pendingImagesOf(request), firstRequestedAt.get(target)),
                PendingArtwork::plus));
    return pending;
  }

  private long pendingImagesOf(PendingArtworkRequest request) {
    var target = request.artwork().target();
    var finished =
        itemResults.findByItem(target.targetId(), target.targetType()).stream()
            .filter(result -> result.step() == ItemStep.ARTWORK)
            .filter(result -> result.artworkGeneration() >= request.generation())
            .filter(ItemResult::isFinal)
            .count();
    return Math.max(0, request.artwork().requestedImageTypes().size() - finished);
  }

  /** Deletes the target's request, as deleting the target does. */
  public synchronized void deleteTarget(ArtworkTarget target) {
    remove(target);
  }

  private void remove(ArtworkTarget target) {
    requests.remove(target);
    firstRequestedAt.remove(target);
  }

  private static boolean covers(
      PendingArtworkRequest pending, ArtworkSources artwork, ImageRefreshMode refreshMode) {
    if (!pending.artwork().sources().equals(artwork.sources())) {
      return false;
    }

    return refreshMode.compareTo(pending.refreshMode()) <= 0;
  }
}
