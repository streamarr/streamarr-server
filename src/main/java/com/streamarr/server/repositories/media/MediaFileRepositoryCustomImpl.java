package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.BaseCollectable.BASE_COLLECTABLE;
import static com.streamarr.server.jooq.generated.tables.Episode.EPISODE;
import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;
import static com.streamarr.server.jooq.generated.tables.Season.SEASON;

import com.streamarr.server.domain.media.MatchingFailure;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaFileTitle;
import com.streamarr.server.jooq.generated.enums.ItemResultFailureReason;
import com.streamarr.server.repositories.JooqQueryHelper;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class MediaFileRepositoryCustomImpl implements MediaFileRepositoryCustom {

  private final DSLContext dsl;
  private final EntityManager entityManager;

  @Override
  public List<UUID> findMediaFileIdsByMediaIds(Collection<UUID> mediaIds) {
    return dsl.select(MEDIA_FILE.ID)
        .from(MEDIA_FILE)
        .where(MEDIA_FILE.MEDIA_ID.in(mediaIds))
        .fetch(MEDIA_FILE.ID);
  }

  @Override
  public Optional<UUID> findMediaIdByMediaFileId(UUID mediaFileId) {
    return dsl.select(MEDIA_FILE.MEDIA_ID)
        .from(MEDIA_FILE)
        .where(MEDIA_FILE.ID.eq(mediaFileId))
        .fetchOptional(MEDIA_FILE.MEDIA_ID);
  }

  @Override
  public Map<UUID, MediaFileTitle> findTitlesByMediaFileIds(Collection<UUID> mediaFileIds) {
    if (mediaFileIds.isEmpty()) {
      return Map.of();
    }

    var item = BASE_COLLECTABLE.as("item");
    var series = BASE_COLLECTABLE.as("series");
    return dsl.select(
            MEDIA_FILE.ID, item.TITLE, series.TITLE, SEASON.SEASON_NUMBER, EPISODE.EPISODE_NUMBER)
        .from(MEDIA_FILE)
        .join(item)
        .on(item.ID.eq(MEDIA_FILE.MEDIA_ID))
        .leftJoin(EPISODE)
        .on(EPISODE.ID.eq(MEDIA_FILE.MEDIA_ID))
        .leftJoin(SEASON)
        .on(SEASON.ID.eq(EPISODE.SEASON_ID))
        .leftJoin(series)
        .on(series.ID.eq(SEASON.SERIES_ID))
        .where(MEDIA_FILE.ID.in(mediaFileIds))
        .fetchMap(
            MEDIA_FILE.ID,
            row ->
                MediaFileTitle.builder()
                    .title(Optional.ofNullable(row.value2()))
                    .seriesTitle(Optional.ofNullable(row.value3()))
                    .seasonNumber(optionalInt(row.value4()))
                    .episodeNumber(optionalInt(row.value5()))
                    .build());
  }

  private static OptionalInt optionalInt(Integer value) {
    if (value == null) {
      return OptionalInt.empty();
    }

    return OptionalInt.of(value);
  }

  @Override
  public Set<UUID> findDistinctMediaIdsByMediaIdIn(Collection<UUID> mediaIds) {
    var query =
        dsl.selectDistinct(MEDIA_FILE.MEDIA_ID)
            .from(MEDIA_FILE)
            .where(MEDIA_FILE.MEDIA_ID.in(mediaIds));

    return new HashSet<>(JooqQueryHelper.nativeQuery(entityManager, query, UUID.class));
  }

  @Override
  @Transactional
  public boolean tryMarkMatchingFailed(UUID mediaFileId, MatchingFailure failure) {
    ItemResultFailureReason reason = null;
    if (failure.reason() != null) {
      reason = ItemResultFailureReason.lookupLiteral(failure.reason().name());
    }

    return dsl.update(MEDIA_FILE)
            .set(MEDIA_FILE.STATUS, toJooq(failure.status()))
            .set(MEDIA_FILE.FAILURE_REASON, reason)
            .where(MEDIA_FILE.ID.eq(mediaFileId))
            .and(MEDIA_FILE.STATUS.ne(toJooq(MediaFileStatus.MATCHED)))
            .execute()
        > 0;
  }

  @Override
  public Map<MediaFileStatus, Long> countStatuses(Collection<UUID> mediaFileIds) {
    var counts = new EnumMap<MediaFileStatus, Long>(MediaFileStatus.class);
    dsl.select(MEDIA_FILE.STATUS, DSL.count())
        .from(MEDIA_FILE)
        .where(MEDIA_FILE.ID.eq(DSL.any(mediaFileIds.toArray(UUID[]::new))))
        .groupBy(MEDIA_FILE.STATUS)
        .forEach(
            row ->
                counts.put(
                    MediaFileStatus.valueOf(row.value1().getLiteral()), (long) row.value2()));
    return counts;
  }

  // The generated enum shares its name with the domain enum it mirrors.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.MediaFileStatus toJooq(
      MediaFileStatus status) {
    return com.streamarr.server.jooq.generated.enums.MediaFileStatus.lookupLiteral(status.name());
  }
}
