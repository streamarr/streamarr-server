package com.streamarr.server.repositories.streaming;

import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;
import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.row;

import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.repositories.LastWatchedExpression;
import com.streamarr.server.repositories.WatchStateConditions;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationDirection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.Select;
import org.jooq.SortOrder;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ContinueWatchingRepositoryImpl implements ContinueWatchingRepository {
  private final DSLContext dsl;

  @Override
  public List<PageItem<UUID>> findItems(UUID profileId, MediaPaginationOptions options) {
    var matching = matchingItems(profileId, noCondition()).asTable("continue_watching");
    var itemId = matching.field(MEDIA_FILE.MEDIA_ID);
    var lastWatchedAt = matching.field("last_watched_at", OffsetDateTime.class);
    var reverse =
        options.getPaginationOptions().getPaginationDirection() == PaginationDirection.REVERSE;
    var direction = reverse ? SortOrder.ASC : SortOrder.DESC;
    var items =
        dsl.select(itemId, lastWatchedAt)
            .from(matching)
            .where(seek(options, itemId, lastWatchedAt))
            .orderBy(lastWatchedAt.sort(direction), itemId.sort(direction))
            .limit(options.getPaginationOptions().getLimit() + 2)
            .fetch(item -> new PageItem<>(item.value1(), item.value2().toInstant()));
    return reverse ? items.reversed() : items;
  }

  private Condition seek(
      MediaPaginationOptions options, Field<UUID> itemId, Field<OffsetDateTime> lastWatchedAt) {
    if (options.getCursorId().isEmpty()) {
      return noCondition();
    }

    var at =
        Instant.parse(options.getMediaFilter().getPreviousSortFieldValue().toString())
            .atOffset(ZoneOffset.UTC);
    var anchor = row(at, options.getCursorId().orElseThrow());
    var current = row(lastWatchedAt, itemId);
    return options.getPaginationOptions().getPaginationDirection() == PaginationDirection.REVERSE
        ? current.ge(anchor)
        : current.le(anchor);
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
