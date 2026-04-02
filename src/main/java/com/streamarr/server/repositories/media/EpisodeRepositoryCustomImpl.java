package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.Episode.EPISODE;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;

@RequiredArgsConstructor
public class EpisodeRepositoryCustomImpl implements EpisodeRepositoryCustom {

  private final DSLContext dsl;

  @Override
  public Map<UUID, List<UUID>> findEpisodeIdsBySeasonIds(Collection<UUID> seasonIds) {
    return dsl.select(EPISODE.ID, EPISODE.SEASON_ID)
        .from(EPISODE)
        .where(EPISODE.SEASON_ID.in(seasonIds))
        .fetchGroups(EPISODE.SEASON_ID, EPISODE.ID);
  }
}
