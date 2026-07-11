package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.Season.SEASON;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;

@RequiredArgsConstructor
public class SeasonRepositoryCustomImpl implements SeasonRepositoryCustom {

  private final DSLContext dsl;

  @Override
  public Map<UUID, List<UUID>> findSeasonIdsBySeriesIds(Collection<UUID> seriesIds) {
    return dsl.select(SEASON.ID, SEASON.SERIES_ID)
        .from(SEASON)
        .where(SEASON.SERIES_ID.in(seriesIds))
        .fetchGroups(SEASON.SERIES_ID, SEASON.ID);
  }
}
