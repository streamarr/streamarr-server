package com.streamarr.server.repositories.streaming;

import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;
import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static org.jooq.impl.DSL.noCondition;

import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.repositories.LastWatchedExpression;
import com.streamarr.server.repositories.WatchStateConditions;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaOrder;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.SortDirection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.SelectLimitStep;
import org.jooq.SortOrder;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ContinueWatchingRepositoryImpl implements ContinueWatchingRepository {
  private static final MediaFilter ORDER =
      MediaFilter.builder()
          .sortBy(OrderMediaBy.LAST_WATCHED)
          .sortDirection(SortDirection.DESC)
          .build();

  private final DSLContext dsl;

  @Override
  public List<UUID> findCollectableIds(UUID profileId, int limit) {
    return matchingItems(profileId, noCondition()).limit(limit).fetch(MEDIA_FILE.MEDIA_ID);
  }

  @Override
  public Map<UUID, MediaOrder> findMatchingPositions(UUID profileId, Set<UUID> itemIds) {
    return matchingItems(profileId, MEDIA_FILE.MEDIA_ID.in(itemIds))
        .fetchMap(
            Record2::value1,
            item -> MediaOrder.from(ORDER, item.value1(), item.value2().toInstant()));
  }

  private SelectLimitStep<Record2<UUID, OffsetDateTime>> matchingItems(
      UUID profileId, Condition scope) {
    var itemId = MEDIA_FILE.MEDIA_ID;
    var lastWatchedAt =
        LastWatchedExpression.lastWatchedAt(profileId, CollectableScope.MOVIE_OR_EPISODE, itemId);
    var direction = ORDER.getSortDirection() == SortDirection.DESC ? SortOrder.DESC : SortOrder.ASC;
    return dsl.select(itemId, lastWatchedAt)
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
        .groupBy(itemId)
        .orderBy(lastWatchedAt.sort(direction), itemId.sort(direction));
  }
}
