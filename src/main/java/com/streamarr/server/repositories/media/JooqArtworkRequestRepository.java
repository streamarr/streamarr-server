package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.Sequences.ARTWORK_REQUEST_GENERATION;
import static com.streamarr.server.jooq.generated.tables.ArtworkRequest.ARTWORK_REQUEST;
import static com.streamarr.server.jooq.generated.tables.ItemResult.ITEM_RESULT;

import com.streamarr.server.domain.media.ArtworkItem;
import com.streamarr.server.domain.media.ArtworkState;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.jooq.generated.enums.ItemResultStep;
import com.streamarr.server.jooq.generated.tables.records.ArtworkRequestRecord;
import com.streamarr.server.repositories.PostgresTransactionLocks;
import com.streamarr.server.services.ArtworkSources;
import com.streamarr.server.services.PendingArtworkRequest;
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

  // Every attempt either writes or finds the row that a concurrent writer committed, so it loops
  // only while other transactions keep replacing or completing the same request.
  private static final int MAX_SAVE_ATTEMPTS = 8;

  private static final Comparator<ImageSource> SOURCE_ORDER =
      Comparator.comparing(ImageSource::imageType).thenComparing(ImageSource::key);

  private record StoredSources(List<ImageType> imageTypes, List<String> keys) {}

  // Writers of an item's request and the completion of its scheduled task take this lock before
  // the request row, so a completion that finds no request cannot miss a request being inserted.
  private static final int REQUEST_LOCK_NAMESPACE = 0x41525452;

  private final DSLContext dsl;
  private final PostgresTransactionLocks transactionLocks;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public SavedArtworkRequest saveRequest(
      ArtworkSources artwork, ImageRefreshMode refreshMode, Instant requestedAt) {
    var sources = storedSources(artwork);
    var unlocked = findRow(artwork.item());
    if (unlocked.isPresent() && coversRequest(unlocked.get(), sources, refreshMode)) {
      return new SavedArtworkRequest.Unchanged(unlocked.get().getGeneration());
    }

    lockRequest(artwork.item());
    for (var attempt = 0; attempt < MAX_SAVE_ATTEMPTS; attempt++) {
      var inserted = tryInsert(artwork, sources, refreshMode, requestedAt);
      if (inserted.isPresent()) {
        return new SavedArtworkRequest.NewGeneration(inserted.get());
      }

      var pending = findRow(artwork.item());
      if (pending.isEmpty()) {
        continue;
      }

      var row = pending.get();
      if (coversRequest(row, sources, refreshMode)) {
        return new SavedArtworkRequest.Unchanged(row.getGeneration());
      }

      var replaced =
          tryReplace(
              row,
              artwork,
              sources,
              strongerOf(toDomain(row.getImageRefreshMode()), refreshMode),
              requestedAt);
      if (replaced.isPresent()) {
        return new SavedArtworkRequest.NewGeneration(replaced.get());
      }
    }

    throw new IllegalStateException(
        "Artwork request for %s %s kept changing while it was saved"
            .formatted(artwork.entityType(), artwork.entityId()));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PendingArtworkRequest> findRequest(ArtworkItem item) {
    return findRow(item).map(JooqArtworkRequestRepository::toPendingRequest);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Long> lockPendingGeneration(ArtworkItem item) {
    lockRequest(item);
    return dsl.select(ARTWORK_REQUEST.GENERATION)
        .from(ARTWORK_REQUEST)
        .where(identifies(item))
        .forShare()
        .fetchOptional(ARTWORK_REQUEST.GENERATION);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean tryCompleteGeneration(ArtworkItem item, long generation) {
    lockRequest(item);
    var pending =
        dsl.select(ARTWORK_REQUEST.GENERATION)
            .from(ARTWORK_REQUEST)
            .where(identifies(item))
            .forUpdate()
            .fetchOptional(ARTWORK_REQUEST.GENERATION);
    if (pending.isEmpty()) {
      return true;
    }

    if (pending.get() != generation) {
      return false;
    }

    dsl.deleteFrom(ARTWORK_REQUEST).where(identifies(item)).execute();
    return true;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ArtworkState> findArtworkStates(Collection<ArtworkItem> items) {
    if (items.isEmpty()) {
      return List.of();
    }

    var itemIds = items.stream().map(ArtworkItem::itemId).distinct().toList();
    var generations = new HashMap<ArtworkItem, Long>();
    dsl.select(ARTWORK_REQUEST.ITEM_ID, ARTWORK_REQUEST.ITEM_TYPE, ARTWORK_REQUEST.GENERATION)
        .from(ARTWORK_REQUEST)
        .where(ARTWORK_REQUEST.ITEM_ID.in(itemIds))
        .forEach(
            row ->
                generations.put(
                    new ArtworkItem(row.value1(), toDomain(row.value2())), row.value3()));
    var results = new HashMap<ArtworkItem, List<ItemResult>>();
    dsl.selectFrom(ITEM_RESULT)
        .where(ITEM_RESULT.ITEM_ID.in(itemIds))
        .and(ITEM_RESULT.STEP.eq(ItemResultStep.ARTWORK))
        .fetch(JooqItemResultRepository::toDomain)
        .forEach(
            result ->
                results
                    .computeIfAbsent(
                        new ArtworkItem(result.itemId(), result.itemType()), _ -> new ArrayList<>())
                    .add(result));

    return items.stream()
        .distinct()
        .map(
            item ->
                new ArtworkState(
                    item,
                    Optional.ofNullable(generations.get(item)),
                    results.getOrDefault(item, List.of())))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Map<ImageEntityType, Long> countPendingImagesByItemType() {
    var counts = new EnumMap<ImageEntityType, Long>(ImageEntityType.class);
    dsl.select(ARTWORK_REQUEST.ITEM_TYPE, DSL.sum(ARTWORK_REQUEST.IMAGE_COUNT))
        .from(ARTWORK_REQUEST)
        .groupBy(ARTWORK_REQUEST.ITEM_TYPE)
        .forEach(row -> counts.put(toDomain(row.value1()), row.value2().longValue()));
    return counts;
  }

  private Optional<Long> tryInsert(
      ArtworkSources artwork,
      StoredSources sources,
      ImageRefreshMode refreshMode,
      Instant requestedAt) {
    return dsl.insertInto(ARTWORK_REQUEST)
        .set(ARTWORK_REQUEST.ITEM_ID, artwork.entityId())
        .set(ARTWORK_REQUEST.ITEM_TYPE, toJooq(artwork.entityType()))
        .set(ARTWORK_REQUEST.IMAGE_REFRESH_MODE, toJooq(refreshMode))
        .set(ARTWORK_REQUEST.SOURCE_IMAGE_TYPES, toJooq(sources.imageTypes()))
        .set(ARTWORK_REQUEST.SOURCE_KEYS, sources.keys().toArray(String[]::new))
        .set(ARTWORK_REQUEST.IMAGE_COUNT, artwork.requestedImageTypes().size())
        .set(ARTWORK_REQUEST.GENERATION, ARTWORK_REQUEST_GENERATION.nextval())
        .set(ARTWORK_REQUEST.REQUESTED_AT, requestedAt.atOffset(ZoneOffset.UTC))
        .onConflictDoNothing()
        .returning(ARTWORK_REQUEST.GENERATION)
        .fetchOptional(ARTWORK_REQUEST.GENERATION);
  }

  // The generation condition makes the replacement fail when a concurrent writer replaced or
  // completed the row after it was read; the caller then reads the row again.
  private Optional<Long> tryReplace(
      ArtworkRequestRecord pending,
      ArtworkSources artwork,
      StoredSources sources,
      ImageRefreshMode refreshMode,
      Instant requestedAt) {
    return dsl.update(ARTWORK_REQUEST)
        .set(ARTWORK_REQUEST.IMAGE_REFRESH_MODE, toJooq(refreshMode))
        .set(ARTWORK_REQUEST.SOURCE_IMAGE_TYPES, toJooq(sources.imageTypes()))
        .set(ARTWORK_REQUEST.SOURCE_KEYS, sources.keys().toArray(String[]::new))
        .set(ARTWORK_REQUEST.IMAGE_COUNT, artwork.requestedImageTypes().size())
        .set(ARTWORK_REQUEST.GENERATION, ARTWORK_REQUEST_GENERATION.nextval())
        .set(ARTWORK_REQUEST.REQUESTED_AT, requestedAt.atOffset(ZoneOffset.UTC))
        .where(identifies(artwork.item()))
        .and(ARTWORK_REQUEST.GENERATION.eq(pending.getGeneration()))
        .returning(ARTWORK_REQUEST.GENERATION)
        .fetchOptional(ARTWORK_REQUEST.GENERATION);
  }

  private void lockRequest(ArtworkItem item) {
    transactionLocks.lock(
        REQUEST_LOCK_NAMESPACE, Objects.hash(item.itemId(), item.itemType().name()));
  }

  private Optional<ArtworkRequestRecord> findRow(ArtworkItem item) {
    return dsl.selectFrom(ARTWORK_REQUEST).where(identifies(item)).fetchOptional();
  }

  private static Condition identifies(ArtworkItem item) {
    return ARTWORK_REQUEST
        .ITEM_ID
        .eq(item.itemId())
        .and(ARTWORK_REQUEST.ITEM_TYPE.eq(toJooq(item.itemType())));
  }

  // A pending request covers a new one with the same sources and an equal or weaker refresh mode.
  private static boolean coversRequest(
      ArtworkRequestRecord pending, StoredSources sources, ImageRefreshMode refreshMode) {
    if (!sourcesOf(pending).equals(sources)) {
      return false;
    }

    return refreshMode.compareTo(toDomain(pending.getImageRefreshMode())) <= 0;
  }

  private static ImageRefreshMode strongerOf(ImageRefreshMode pending, ImageRefreshMode requested) {
    if (requested.compareTo(pending) > 0) {
      return requested;
    }

    return pending;
  }

  private static StoredSources storedSources(ArtworkSources artwork) {
    var sorted = artwork.sources().stream().sorted(SOURCE_ORDER).toList();
    return new StoredSources(
        sorted.stream().map(ImageSource::imageType).toList(),
        sorted.stream().map(ImageSource::key).toList());
  }

  private static StoredSources sourcesOf(ArtworkRequestRecord row) {
    return new StoredSources(
        Arrays.stream(row.getSourceImageTypes())
            .map(imageType -> ImageType.valueOf(imageType.getLiteral()))
            .toList(),
        List.of(row.getSourceKeys()));
  }

  private static PendingArtworkRequest toPendingRequest(ArtworkRequestRecord row) {
    var stored = sourcesOf(row);
    var sources =
        IntStream.range(0, stored.imageTypes().size())
            .<ImageSource>mapToObj(
                index ->
                    new TmdbImageSource(stored.imageTypes().get(index), stored.keys().get(index)))
            .toList();
    return PendingArtworkRequest.builder()
        .artwork(
            ArtworkSources.builder()
                .entityId(row.getItemId())
                .entityType(toDomain(row.getItemType()))
                .sources(sources)
                .build())
        .refreshMode(toDomain(row.getImageRefreshMode()))
        .generation(row.getGeneration())
        .build();
  }

  // The generated enums share names with the domain enums they mirror.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.ImageEntityType toJooq(
      ImageEntityType itemType) {
    return com.streamarr.server.jooq.generated.enums.ImageEntityType.lookupLiteral(itemType.name());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.ImageType[] toJooq(
      List<ImageType> imageTypes) {
    return imageTypes.stream()
        .map(
            imageType ->
                com.streamarr.server.jooq.generated.enums.ImageType.lookupLiteral(imageType.name()))
        .toArray(com.streamarr.server.jooq.generated.enums.ImageType[]::new);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.ImageRefreshMode toJooq(
      ImageRefreshMode refreshMode) {
    return com.streamarr.server.jooq.generated.enums.ImageRefreshMode.lookupLiteral(
        refreshMode.name());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static ImageEntityType toDomain(
      com.streamarr.server.jooq.generated.enums.ImageEntityType itemType) {
    return ImageEntityType.valueOf(itemType.getLiteral());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static ImageRefreshMode toDomain(
      com.streamarr.server.jooq.generated.enums.ImageRefreshMode refreshMode) {
    return ImageRefreshMode.valueOf(refreshMode.getLiteral());
  }
}
