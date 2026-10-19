package com.streamarr.server.services.watchprogress;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.streaming.ContinueWatchingRepository;
import com.streamarr.server.services.pagination.MediaOrderKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
  public Map<UUID, ContinueWatchingPosition> getPositions(UUID profileId, Set<UUID> itemIds) {
    if (itemIds.isEmpty()) {
      return Map.of();
    }

    var orders = continueWatchingRepository.findMatchingPositions(profileId, itemIds);
    return itemIds.stream()
        .collect(
            Collectors.toMap(
                Function.identity(),
                itemId -> {
                  var order = orders.get(itemId);
                  return new ContinueWatchingPosition(
                      order != null, order == null ? null : MediaOrderKey.encode(order));
                }));
  }

  @Transactional(readOnly = true)
  public List<BaseCollectable<?>> getContinueWatching(UUID profileId, int limit) {
    var collectableIds = continueWatchingRepository.findCollectableIds(profileId, limit);

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
