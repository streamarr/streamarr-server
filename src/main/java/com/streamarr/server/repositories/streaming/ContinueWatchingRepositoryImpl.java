package com.streamarr.server.repositories.streaming;

import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;
import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static org.jooq.impl.DSL.noCondition;

import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.repositories.Keyset;
import com.streamarr.server.repositories.LastWatchedExpression;
import com.streamarr.server.repositories.WatchStateConditions;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Select;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ContinueWatchingRepositoryImpl implements ContinueWatchingRepository {
  private final DSLContext dsl;

  @Override
  public List<Row<UUID>> rowsBeyond(UUID profileId, Seek seek) {
    var matching = matchingItems(profileId, noCondition()).asTable("continue_watching");
    var keyset =
        Keyset.<OffsetDateTime>builder()
            .sortValue(matching.field("last_watched_at", OffsetDateTime.class))
            .itemId(matching.field(MEDIA_FILE.MEDIA_ID))
            .build();
    return keyset
        .rowsBeyond(dsl.select(keyset.sortValue(), keyset.itemId()).from(matching), seek)
        .fetch(keyset::rowOf);
  }

  @Override
  public Map<UUID, Instant> findMatchingItems(UUID profileId, Set<UUID> itemIds) {
    return matchingItems(profileId, MEDIA_FILE.MEDIA_ID.in(itemIds))
        .fetchMap(Record2::value1, item -> item.value2().toInstant());
  }

  private Select<Record2<UUID, OffsetDateTime>> matchingItems(UUID profileId, Condition scope) {
    var itemId = MEDIA_FILE.MEDIA_ID;
    var lastWatchedAt =
        LastWatchedExpression.lastWatchedAt(profileId, CollectableScope.MOVIE_OR_EPISODE, itemId);
    return dsl.select(itemId, lastWatchedAt.as("last_watched_at"))
        .from(SESSION_PROGRESS)
        .innerJoin(MEDIA_FILE)
        .on(SESSION_PROGRESS.MEDIA_FILE_ID.eq(MEDIA_FILE.ID))
        .where(
            SESSION_PROGRESS
                .PROFILE_ID
                .eq(profileId)
                .and(SESSION_PROGRESS.POSITION_SECONDS.greaterThan(0))
                .and(WatchStateConditions.hasResumePosition(profileId, itemId))
                .and(scope))
        .groupBy(itemId);
  }
}
