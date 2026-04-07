package com.streamarr.server.services.watchprogress;

import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.not;
import static org.jooq.impl.DSL.select;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.jooq.generated.Tables;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.SortOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContinueWatchingService {

  private final DSLContext context;
  private final MovieRepository movieRepository;
  private final EpisodeRepository episodeRepository;

  @Transactional(readOnly = true)
  public List<BaseCollectable<?>> getContinueWatching(int limit) {
    // TODO(#163): Replace with authenticated user ID from Spring Security
    var userId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    var isWatched =
        exists(
            select(Tables.WATCH_HISTORY.ID)
                .from(Tables.WATCH_HISTORY)
                .where(
                    Tables.WATCH_HISTORY
                        .COLLECTABLE_ID
                        .eq(Tables.MEDIA_FILE.MEDIA_ID)
                        .and(Tables.WATCH_HISTORY.USER_ID.eq(userId))
                        .and(Tables.WATCH_HISTORY.DISMISSED_AT.isNull())));

    var collectableIds =
        context
            .select(
                Tables.MEDIA_FILE.MEDIA_ID,
                max(Tables.SESSION_PROGRESS.LAST_MODIFIED_ON).as("last_activity"))
            .from(Tables.SESSION_PROGRESS)
            .innerJoin(Tables.MEDIA_FILE)
            .on(Tables.SESSION_PROGRESS.MEDIA_FILE_ID.eq(Tables.MEDIA_FILE.ID))
            .where(
                Tables.SESSION_PROGRESS
                    .USER_ID
                    .eq(userId)
                    .and(Tables.SESSION_PROGRESS.POSITION_SECONDS.greaterThan(0))
                    .and(not(isWatched)))
            .groupBy(Tables.MEDIA_FILE.MEDIA_ID)
            .orderBy(max(Tables.SESSION_PROGRESS.LAST_MODIFIED_ON).sort(SortOrder.DESC))
            .limit(limit)
            .fetch(Tables.MEDIA_FILE.MEDIA_ID);

    if (collectableIds.isEmpty()) {
      return List.of();
    }

    var movies = movieRepository.findAllById(collectableIds);
    var episodes = episodeRepository.findAllById(collectableIds);

    var movieMap = movies.stream().collect(java.util.stream.Collectors.toMap(Movie::getId, m -> m));
    var episodeMap =
        episodes.stream().collect(java.util.stream.Collectors.toMap(Episode::getId, e -> e));

    var result = new ArrayList<BaseCollectable<?>>();
    for (var id : collectableIds) {
      var movie = movieMap.get(id);
      if (movie != null) {
        result.add(movie);
        continue;
      }
      var episode = episodeMap.get(id);
      if (episode != null) {
        result.add(episode);
      }
    }

    return result;
  }
}
