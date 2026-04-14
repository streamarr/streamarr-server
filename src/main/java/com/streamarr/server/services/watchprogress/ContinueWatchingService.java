package com.streamarr.server.services.watchprogress;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.streaming.ContinueWatchingRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContinueWatchingService {

  private final ContinueWatchingRepository continueWatchingRepository;
  private final MovieRepository movieRepository;
  private final EpisodeRepository episodeRepository;

  @Transactional(readOnly = true)
  public List<BaseCollectable<?>> getContinueWatching(int limit) {
    // TODO(#163): Replace with authenticated user ID from Spring Security
    var userId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    var collectableIds = continueWatchingRepository.findCollectableIds(userId, limit);

    if (collectableIds.isEmpty()) {
      return List.of();
    }

    Map<UUID, BaseCollectable<?>> byId = new HashMap<>();
    movieRepository.findAllById(collectableIds).forEach(movie -> byId.put(movie.getId(), movie));
    episodeRepository
        .findAllById(collectableIds)
        .forEach(episode -> byId.put(episode.getId(), episode));

    return collectableIds.stream().map(byId::get).filter(Objects::nonNull).toList();
  }
}
