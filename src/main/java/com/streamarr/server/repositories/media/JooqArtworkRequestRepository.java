package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.Sequences.ARTWORK_REQUEST_GENERATION;
import static com.streamarr.server.jooq.generated.tables.ArtworkRequest.ARTWORK_REQUEST;
import static com.streamarr.server.jooq.generated.tables.ItemResult.ITEM_RESULT;
import static com.streamarr.server.repositories.media.MediaEnumMapping.toDomain;
import static com.streamarr.server.repositories.media.MediaEnumMapping.toJooq;

import com.streamarr.server.config.ArtworkProperties;
import com.streamarr.server.domain.media.ArtworkCompletionEvidence;
import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.PendingArtwork;
import com.streamarr.server.jooq.generated.enums.ItemResultFailureReason;
import com.streamarr.server.jooq.generated.enums.ItemResultOutcome;
import com.streamarr.server.jooq.generated.enums.ItemResultStep;
import com.streamarr.server.jooq.generated.tables.records.ArtworkRequestRecord;
import com.streamarr.server.repositories.PostgresTransactionLocks;
import com.streamarr.server.services.ArtworkSources;
import com.streamarr.server.services.StoredArtworkRequest;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class JooqArtworkRequestRepository implements ArtworkRequestRepository {

  // Writers of a target's request and the completion of its scheduled task take this lock before
  // the request row, so a completion that finds no request cannot miss a request being inserted.
  private static final int REQUEST_LOCK_NAMESPACE = 0x41525452;

  private static final Comparator<ImageSource> SOURCE_ORDER =
      Comparator.comparing(ImageSource::imageType).thenComparing(ImageSource::key);

  private record StoredSources(List<ImageType> imageTypes, List<String> keys) {}

  private final DSLContext dsl;
  private final PostgresTransactionLocks transactionLocks;
  private final ArtworkProperties properties;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public SavedArtworkRequest saveRequest(
      ArtworkSources artwork, ImageRefreshMode refreshMode, Instant requestedAt) {
    return transactionLocks.withLockWaitLimit(
        properties.lockTimeout(), () -> saveRequestUnderLock(artwork, refreshMode, requestedAt));
  }

  private SavedArtworkRequest saveRequestUnderLock(
      ArtworkSources artwork, ImageRefreshMode refreshMode, Instant requestedAt) {
    var sources = storedSources(artwork);
    var unlocked = findRow(artwork.target());
    if (unlocked.isPresent() && coversRequest(unlocked.get(), sources, refreshMode)) {
      return new SavedArtworkRequest.Unchanged(unlocked.get().getGeneration());
    }

    lockRequest(artwork.target());
    var newGeneration =
        dsl.insertInto(ARTWORK_REQUEST)
            .set(ARTWORK_REQUEST.TARGET_ID, artwork.targetId())
            .set(ARTWORK_REQUEST.TARGET_TYPE, toJooq(artwork.targetType()))
            .set(ARTWORK_REQUEST.IMAGE_REFRESH_MODE, toJooq(refreshMode))
            .set(ARTWORK_REQUEST.SOURCE_IMAGE_TYPES, toJooq(sources.imageTypes()))
            .set(ARTWORK_REQUEST.SOURCE_KEYS, sources.keys().toArray(String[]::new))
            .set(ARTWORK_REQUEST.IMAGE_COUNT, artwork.requestedImageTypes().size())
            .set(ARTWORK_REQUEST.GENERATION, ARTWORK_REQUEST_GENERATION.nextval())
            .set(ARTWORK_REQUEST.REQUESTED_AT, requestedAt.atOffset(ZoneOffset.UTC))
            .onConflict(ARTWORK_REQUEST.TARGET_ID, ARTWORK_REQUEST.TARGET_TYPE)
            .doUpdate()
            .set(
                ARTWORK_REQUEST.IMAGE_REFRESH_MODE,
                DSL.greatest(
                    ARTWORK_REQUEST.IMAGE_REFRESH_MODE,
                    DSL.excluded(ARTWORK_REQUEST.IMAGE_REFRESH_MODE)))
            .set(
                ARTWORK_REQUEST.SOURCE_IMAGE_TYPES,
                DSL.excluded(ARTWORK_REQUEST.SOURCE_IMAGE_TYPES))
            .set(ARTWORK_REQUEST.SOURCE_KEYS, DSL.excluded(ARTWORK_REQUEST.SOURCE_KEYS))
            .set(ARTWORK_REQUEST.GENERATION, ARTWORK_REQUEST_GENERATION.nextval())
            .set(ARTWORK_REQUEST.IMAGE_COUNT, DSL.excluded(ARTWORK_REQUEST.IMAGE_COUNT))
            .where(changesPendingRequest())
            .returning(ARTWORK_REQUEST.GENERATION)
            .fetchOptional(ARTWORK_REQUEST.GENERATION);
    if (newGeneration.isPresent()) {
      return new SavedArtworkRequest.NewGeneration(newGeneration.get());
    }

    // The conflicting row covers the request; the upsert locked it, so it is still there.
    return new SavedArtworkRequest.Unchanged(
        findRow(artwork.target()).orElseThrow().getGeneration());
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<StoredArtworkRequest> findRequest(ArtworkTarget target) {
    return findRow(target).map(JooqArtworkRequestRepository::toStoredRequest);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Long> lockPendingGeneration(ArtworkTarget target) {
    return transactionLocks.withLockWaitLimit(
        properties.lockTimeout(),
        () -> {
          lockRequest(target);
          return dsl.select(ARTWORK_REQUEST.GENERATION)
              .from(ARTWORK_REQUEST)
              .where(identifies(target))
              .forShare()
              .fetchOptional(ARTWORK_REQUEST.GENERATION);
        });
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean tryCompleteGeneration(ArtworkTarget target, long generation) {
    return transactionLocks.withLockWaitLimit(
        properties.lockTimeout(), () -> tryCompleteGenerationUnderLock(target, generation));
  }

  private boolean tryCompleteGenerationUnderLock(ArtworkTarget target, long generation) {
    lockRequest(target);
    var pending =
        dsl.select(ARTWORK_REQUEST.GENERATION)
            .from(ARTWORK_REQUEST)
            .where(identifies(target))
            .forUpdate()
            .fetchOptional(ARTWORK_REQUEST.GENERATION);
    if (pending.isEmpty()) {
      return true;
    }

    if (pending.get() != generation) {
      return false;
    }

    dsl.deleteFrom(ARTWORK_REQUEST).where(identifies(target)).execute();
    return true;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ArtworkCompletionEvidence> findCompletionEvidence(Collection<ArtworkTarget> targets) {
    if (targets.isEmpty()) {
      return List.of();
    }

    // Generations are read before results: a step stores its results before its completion
    // deletes the request, so a target read without a request has every result it will get.
    var targetIds = targets.stream().map(ArtworkTarget::targetId).distinct().toList();
    var generations = new HashMap<ArtworkTarget, Long>();
    dsl.select(ARTWORK_REQUEST.TARGET_ID, ARTWORK_REQUEST.TARGET_TYPE, ARTWORK_REQUEST.GENERATION)
        .from(ARTWORK_REQUEST)
        .where(ARTWORK_REQUEST.TARGET_ID.in(targetIds))
        .forEach(
            row ->
                generations.put(
                    new ArtworkTarget(row.value1(), toDomain(row.value2())), row.value3()));
    var results = new HashMap<ArtworkTarget, List<ItemResult>>();
    dsl.selectFrom(ITEM_RESULT)
        .where(ITEM_RESULT.ITEM_ID.in(targetIds))
        .and(ITEM_RESULT.STEP.eq(ItemResultStep.ARTWORK))
        .fetch(ItemResultRecords::toDomain)
        .forEach(
            result ->
                results
                    .computeIfAbsent(
                        new ArtworkTarget(result.itemId(), result.itemType()),
                        _ -> new ArrayList<>())
                    .add(result));

    return targets.stream()
        .distinct()
        .map(
            target ->
                new ArtworkCompletionEvidence(
                    target,
                    Optional.ofNullable(generations.get(target)),
                    results.getOrDefault(target, List.of())))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Map<ImageEntityType, PendingArtwork> findPendingArtworkByItemType() {
    var finishedImages =
        DSL.selectCount()
            .from(ITEM_RESULT)
            .where(ITEM_RESULT.ITEM_ID.eq(ARTWORK_REQUEST.TARGET_ID))
            .and(ITEM_RESULT.ITEM_TYPE.eq(ARTWORK_REQUEST.TARGET_TYPE))
            .and(ITEM_RESULT.STEP.eq(ItemResultStep.ARTWORK))
            .and(ITEM_RESULT.ARTWORK_GENERATION.ge(ARTWORK_REQUEST.GENERATION))
            .and(isFinal())
            .<Integer>asField();
    var pendingImages =
        DSL.greatest(ARTWORK_REQUEST.IMAGE_COUNT.minus(finishedImages), DSL.inline(0));
    var pending = new EnumMap<ImageEntityType, PendingArtwork>(ImageEntityType.class);
    dsl.select(
            ARTWORK_REQUEST.TARGET_TYPE,
            DSL.sum(pendingImages),
            DSL.min(ARTWORK_REQUEST.REQUESTED_AT))
        .from(ARTWORK_REQUEST)
        .groupBy(ARTWORK_REQUEST.TARGET_TYPE)
        .forEach(
            row ->
                pending.put(
                    toDomain(row.value1()),
                    new PendingArtwork(row.value2().longValue(), row.value3().toInstant())));
    return pending;
  }

  // A pending request covers a new one with the same sources and an equal or weaker refresh mode.
  private static Condition changesPendingRequest() {
    return ARTWORK_REQUEST
        .SOURCE_IMAGE_TYPES
        .isDistinctFrom(DSL.excluded(ARTWORK_REQUEST.SOURCE_IMAGE_TYPES))
        .or(ARTWORK_REQUEST.SOURCE_KEYS.isDistinctFrom(DSL.excluded(ARTWORK_REQUEST.SOURCE_KEYS)))
        .or(
            DSL.excluded(ARTWORK_REQUEST.IMAGE_REFRESH_MODE)
                .gt(ARTWORK_REQUEST.IMAGE_REFRESH_MODE));
  }

  // A result that no retry of its generation would change.
  private static Condition isFinal() {
    return ITEM_RESULT
        .OUTCOME
        .ne(ItemResultOutcome.FAILED)
        .or(ITEM_RESULT.FAILURE_REASON.ne(ItemResultFailureReason.TEMPORARY));
  }

  private void lockRequest(ArtworkTarget target) {
    transactionLocks.lock(
        REQUEST_LOCK_NAMESPACE, Objects.hash(target.targetId(), target.targetType().name()));
  }

  private Optional<ArtworkRequestRecord> findRow(ArtworkTarget target) {
    return dsl.selectFrom(ARTWORK_REQUEST).where(identifies(target)).fetchOptional();
  }

  private static Condition identifies(ArtworkTarget target) {
    return ARTWORK_REQUEST
        .TARGET_ID
        .eq(target.targetId())
        .and(ARTWORK_REQUEST.TARGET_TYPE.eq(toJooq(target.targetType())));
  }

  // A pending request covers a new one with the same sources and an equal or weaker refresh mode.
  private static boolean coversRequest(
      ArtworkRequestRecord pending, StoredSources sources, ImageRefreshMode refreshMode) {
    if (!sourcesOf(pending).equals(sources)) {
      return false;
    }

    return refreshMode.compareTo(toDomain(pending.getImageRefreshMode())) <= 0;
  }

  private static StoredSources storedSources(ArtworkSources artwork) {
    var sorted = artwork.sources().stream().sorted(SOURCE_ORDER).toList();
    return new StoredSources(
        sorted.stream().map(ImageSource::imageType).toList(),
        sorted.stream().map(ImageSource::key).toList());
  }

  private static StoredSources sourcesOf(ArtworkRequestRecord row) {
    return new StoredSources(
        Arrays.stream(row.getSourceImageTypes()).map(MediaEnumMapping::toDomain).toList(),
        List.of(row.getSourceKeys()));
  }

  private static StoredArtworkRequest toStoredRequest(ArtworkRequestRecord row) {
    var stored = sourcesOf(row);
    var sources =
        IntStream.range(0, stored.imageTypes().size())
            .<ImageSource>mapToObj(
                index ->
                    new TmdbImageSource(stored.imageTypes().get(index), stored.keys().get(index)))
            .toList();
    return StoredArtworkRequest.builder()
        .artwork(
            ArtworkSources.builder()
                .targetId(row.getTargetId())
                .targetType(toDomain(row.getTargetType()))
                .sources(sources)
                .build())
        .refreshMode(toDomain(row.getImageRefreshMode()))
        .generation(row.getGeneration())
        .build();
  }
}
