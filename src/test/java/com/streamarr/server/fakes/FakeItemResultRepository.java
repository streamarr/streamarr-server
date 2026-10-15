package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.ArtworkResultCounts;
import com.streamarr.server.domain.media.ArtworkStepsAttempt;
import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.repositories.media.ItemResultRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Function;

public class FakeItemResultRepository implements ItemResultRepository {

  private record Key(UUID itemId, ImageEntityType itemType, ItemStep step, ImageType imageType) {}

  private final Map<Key, ItemResult> results = new ConcurrentHashMap<>();
  private final Map<Key, Instant> recordedAt = new ConcurrentHashMap<>();
  private volatile Clock storageClock = Clock.systemUTC();
  private final AtomicReference<RuntimeException> writeFailure = new AtomicReference<>();
  private final AtomicReference<RuntimeException> artworkResultsFailure = new AtomicReference<>();
  private final AtomicInteger remainingArtworkResultsFailures = new AtomicInteger();
  private volatile BiPredicate<ArtworkTarget, Long> currentArtworkGeneration = (_, _) -> true;
  private volatile Function<ArtworkTarget, Optional<UUID>> requestLibraryOperationId =
      _ -> Optional.empty();

  public void failWritesWith(RuntimeException failure) {
    writeFailure.set(failure);
  }

  /**
   * Fails the next {@code times} grouped artwork result writes with {@code failure}, while a saved
   * image's result, which {@link #overwrite} stores, still commits.
   */
  public void failArtworkResultWritesWith(RuntimeException failure, int times) {
    artworkResultsFailure.set(failure);
    remainingArtworkResultsFailures.set(times);
  }

  /** Decides whether an attempt's generation is still the target's pending artwork request. */
  public void checkArtworkGenerationsWith(
      BiPredicate<ArtworkTarget, Long> currentArtworkGeneration) {
    this.currentArtworkGeneration = currentArtworkGeneration;
  }

  /**
   * Reads the library operation id that the target's artwork request carries, which an artwork
   * result takes as it is saved.
   */
  public void stampArtworkResultsWith(
      Function<ArtworkTarget, Optional<UUID>> requestLibraryOperationId) {
    this.requestLibraryOperationId = requestLibraryOperationId;
  }

  /**
   * Gives the operation's id to the target's results of {@code generation} or a newer one that no
   * retry will replace, as a library operation that takes over the target's request does.
   */
  public void takeOverFinalArtworkResults(
      ArtworkTarget target, long generation, UUID libraryOperationId) {
    results.replaceAll(
        (key, result) -> {
          if (!isFinalArtworkResultOf(target, generation, result)) {
            return result;
          }

          return result.toBuilder().libraryOperationId(Optional.of(libraryOperationId)).build();
        });
  }

  private static boolean isFinalArtworkResultOf(
      ArtworkTarget target, long generation, ItemResult result) {
    return result.step() == ItemStep.ARTWORK
        && result.itemId().equals(target.targetId())
        && result.itemType() == target.targetType()
        && result.artworkGeneration() >= generation
        && result.isFinal();
  }

  private ItemResult stamped(ItemResult result) {
    if (result.step() != ItemStep.ARTWORK) {
      return result;
    }

    return result.toBuilder()
        .libraryOperationId(
            requestLibraryOperationId.apply(new ArtworkTarget(result.itemId(), result.itemType())))
        .build();
  }

  @Override
  public boolean trySave(ItemResult result) {
    var failure = writeFailure.get();
    if (failure != null) {
      throw failure;
    }

    var recorded = new AtomicBoolean();
    results.compute(
        new Key(result.itemId(), result.itemType(), result.step(), result.imageType()),
        (_, existing) -> {
          if (existing != null && existing.attemptedAt().isAfter(result.attemptedAt())) {
            return existing;
          }

          recorded.set(true);
          return stamped(result);
        });
    if (recorded.get()) {
      recordedAt.put(keyOf(result), storageClock.instant());
    }

    return recorded.get();
  }

  @Override
  public void overwrite(ItemResult result) {
    var failure = writeFailure.get();
    if (failure != null) {
      throw failure;
    }

    results.put(keyOf(result), stamped(result));
    recordedAt.put(keyOf(result), storageClock.instant());
  }

  @Override
  public boolean trySaveArtworkResults(ArtworkStepsAttempt attempt, List<ItemResult> results) {
    if (remainingArtworkResultsFailures.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
      throw artworkResultsFailure.get();
    }

    var failure = writeFailure.get();
    if (failure != null) {
      throw failure;
    }

    if (!currentArtworkGeneration.test(attempt.target(), attempt.generation())) {
      return false;
    }

    results.stream().filter(this::isNotSupersededByALaterAttempt).forEach(this::overwrite);
    return true;
  }

  private boolean isNotSupersededByALaterAttempt(ItemResult result) {
    var stored = results.get(keyOf(result));
    if (stored == null || stored.artworkGeneration() < result.artworkGeneration()) {
      return true;
    }

    if (stored.attemptedAt().isAfter(result.attemptedAt())) {
      return false;
    }

    return !isSavedImageReplacedByFailure(stored, result);
  }

  private static boolean isSavedImageReplacedByFailure(ItemResult stored, ItemResult result) {
    if (!(stored.outcome() instanceof ItemOutcome.Succeeded)) {
      return false;
    }

    return !(result.outcome() instanceof ItemOutcome.Succeeded);
  }

  @Override
  public Map<ImageEntityType, ArtworkResultCounts> countArtworkOutcomesRecordedSince(
      Instant since) {
    var resultCounts = new EnumMap<ImageEntityType, ArtworkResultCounts>(ImageEntityType.class);
    results.values().stream()
        .filter(result -> result.step() == ItemStep.ARTWORK)
        .filter(result -> !recordedAt.getOrDefault(keyOf(result), Instant.MIN).isBefore(since))
        .forEach(
            result ->
                resultCounts.merge(
                    result.itemType(),
                    resultCountsOf(result.outcome()),
                    FakeItemResultRepository::add));
    return resultCounts;
  }

  /**
   * Stamps each stored result with the clock's time, as the database's {@code recorded_at} does.
   */
  public void stampStoredResultsWith(Clock clock) {
    this.storageClock = clock;
  }

  private static ArtworkResultCounts resultCountsOf(ItemOutcome outcome) {
    return switch (outcome) {
      case ItemOutcome.Succeeded _ -> new ArtworkResultCounts(1, 0, 0);
      case ItemOutcome.Unavailable _ -> new ArtworkResultCounts(0, 1, 0);
      case ItemOutcome.Failed _ -> new ArtworkResultCounts(0, 0, 1);
    };
  }

  private static ArtworkResultCounts add(ArtworkResultCounts left, ArtworkResultCounts right) {
    return new ArtworkResultCounts(
        left.saved() + right.saved(),
        left.unavailable() + right.unavailable(),
        left.failed() + right.failed());
  }

  private static Key keyOf(ItemResult result) {
    return new Key(result.itemId(), result.itemType(), result.step(), result.imageType());
  }

  public List<ItemResult> findAll() {
    return List.copyOf(results.values());
  }

  @Override
  public List<ItemResult> findByItem(UUID itemId, ImageEntityType itemType) {
    return results.values().stream()
        .filter(result -> result.itemId().equals(itemId) && result.itemType() == itemType)
        .toList();
  }

  public Optional<ItemResult> find(UUID itemId, ItemStep step, ImageType imageType) {
    return results.values().stream()
        .filter(result -> result.itemId().equals(itemId))
        .filter(result -> result.step() == step && result.imageType() == imageType)
        .findFirst();
  }
}
