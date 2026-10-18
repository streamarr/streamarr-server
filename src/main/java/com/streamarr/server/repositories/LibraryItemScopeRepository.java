package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.BaseCollectable.BASE_COLLECTABLE;
import static com.streamarr.server.jooq.generated.tables.Episode.EPISODE;
import static com.streamarr.server.jooq.generated.tables.Movie.MOVIE;
import static com.streamarr.server.jooq.generated.tables.Season.SEASON;
import static com.streamarr.server.jooq.generated.tables.Series.SERIES;
import static org.jooq.impl.DSL.coalesce;

import com.streamarr.server.domain.media.MediaType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class LibraryItemScopeRepository {
  private final DSLContext context;

  public record Item(UUID id, UUID libraryId, MediaType kind) {}

  public List<Item> findItems(Collection<UUID> collectableIds) {
    if (collectableIds.isEmpty()) {
      return List.of();
    }

    var episodeSeason = SEASON.as("episode_season");
    var root = BASE_COLLECTABLE.as("library_item");
    return context
        .selectDistinct(root.ID, root.LIBRARY_ID, MOVIE.ID)
        .from(BASE_COLLECTABLE)
        .leftJoin(EPISODE)
        .on(EPISODE.ID.eq(BASE_COLLECTABLE.ID))
        .leftJoin(episodeSeason)
        .on(episodeSeason.ID.eq(EPISODE.SEASON_ID))
        .leftJoin(SEASON)
        .on(SEASON.ID.eq(BASE_COLLECTABLE.ID))
        .join(root)
        .on(root.ID.eq(coalesce(episodeSeason.SERIES_ID, SEASON.SERIES_ID, BASE_COLLECTABLE.ID)))
        .leftJoin(MOVIE)
        .on(MOVIE.ID.eq(root.ID))
        .leftJoin(SERIES)
        .on(SERIES.ID.eq(root.ID))
        .where(BASE_COLLECTABLE.ID.in(collectableIds))
        .and(MOVIE.ID.isNotNull().or(SERIES.ID.isNotNull()))
        .fetch(
            row ->
                new Item(
                    row.value1(),
                    row.value2(),
                    row.value3() == null ? MediaType.SERIES : MediaType.MOVIE));
  }
}
