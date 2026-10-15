package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.ArtworkBacklog;
import com.streamarr.server.domain.media.ArtworkCompletionEvidence;
import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.services.ArtworkSources;
import com.streamarr.server.services.NewArtworkRequest;
import com.streamarr.server.services.StoredArtworkRequest;
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

  private final Map<ArtworkTarget, StoredArtworkRequest> requests = new HashMap<>();
  private final Map<ArtworkTarget, Instant> firstRequestedAt = new HashMap<>();
  private final FakeItemResultRepository itemResults;
  private long lastGeneration;
  private int completionEvidenceReads;
  private RuntimeException nextCompletionEvidenceReadFailure;

  /** Holds the fake item results to this repository's generations, as the database does. */
  public FakeArtworkRequestRepository(FakeItemResultRepository itemResults) {
    this.itemResults = itemResults;
    itemResults.checkArtworkGenerationsWith(this::isPendingGeneration);
  }

  /** Fails the next read of the artwork completion evidence with {@code failure}. */
  public synchronized void failNextCompletionEvidenceReadWith(RuntimeException failure) {
    nextCompletionEvidenceReadFailure = failure;
  }

  /** Returns how many times the artwork completion evidence was read. */
  public synchronized int completionEvidenceReads() {
    return completionEvidenceReads;
  }

  /** Returns every stored artwork request. */
  public synchronized List<StoredArtworkRequest> requests() {
    return List.copyOf(requests.values());
  }

  /** Returns whether {@code generation} is the target's pending artwork request. */
  public synchronized boolean isPendingGeneration(ArtworkTarget target, long generation) {
    var pending = requests.get(target);
    return pending != null && pending.generation() == generation;
  }

  @Override
  public synchronized SavedArtworkRequest saveRequest(NewArtworkRequest request) {
    var artwork = request.artwork();
    var refreshMode = request.refreshMode();
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
    firstRequestedAt.putIfAbsent(artwork.target(), request.requestedAt());
    requests.put(
        artwork.target(),
        StoredArtworkRequest.builder()
            .artwork(sorted)
            .refreshMode(mode)
            .generation(generation)
            .build());
    return new SavedArtworkRequest.NewGeneration(generation);
  }

  @Override
  public synchronized Optional<StoredArtworkRequest> findRequest(ArtworkTarget target) {
    return Optional.ofNullable(requests.get(target));
  }

  @Override
  public synchronized Optional<Long> lockPendingGeneration(ArtworkTarget target) {
    return findRequest(target).map(StoredArtworkRequest::generation);
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
  public synchronized List<ArtworkCompletionEvidence> findCompletionEvidence(
      Collection<ArtworkTarget> targets) {
    completionEvidenceReads++;
    var failure = nextCompletionEvidenceReadFailure;
    if (failure != null) {
      nextCompletionEvidenceReadFailure = null;
      throw failure;
    }

    return targets.stream()
        .distinct()
        .map(
            target ->
                new ArtworkCompletionEvidence(
                    target,
                    findRequest(target).map(StoredArtworkRequest::generation),
                    itemResults.findByItem(target.targetId(), target.targetType()).stream()
                        .filter(result -> result.step() == ItemStep.ARTWORK)
                        .toList()))
        .toList();
  }

  @Override
  public synchronized Map<ImageEntityType, ArtworkBacklog> findArtworkBacklogByTargetType() {
    var backlog = new EnumMap<ImageEntityType, ArtworkBacklog>(ImageEntityType.class);
    requests.forEach(
        (target, request) ->
            backlog.merge(
                target.targetType(),
                new ArtworkBacklog(pendingImagesOf(request), firstRequestedAt.get(target)),
                ArtworkBacklog::plus));
    return backlog;
  }

  private long pendingImagesOf(StoredArtworkRequest request) {
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
      StoredArtworkRequest pending, ArtworkSources artwork, ImageRefreshMode refreshMode) {
    if (!pending.artwork().sources().equals(artwork.sources())) {
      return false;
    }

    return refreshMode.compareTo(pending.refreshMode()) <= 0;
  }
}
