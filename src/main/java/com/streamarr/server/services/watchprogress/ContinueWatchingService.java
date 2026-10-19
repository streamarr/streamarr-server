package com.streamarr.server.services.watchprogress;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.streaming.ContinueWatchingRepository;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
  private final PaginationService paginationService;

  @Transactional(readOnly = true)
  public Map<UUID, PageItem<BaseCollectable<?>>> findMatchingItems(
      UUID profileId, Set<UUID> itemIds) {
    if (itemIds.isEmpty()) {
      return Map.of();
    }

    var orders = continueWatchingRepository.findMatchingItems(profileId, itemIds);
    var items = loadItems(List.copyOf(orders.keySet()));
    return items.entrySet().stream()
        .collect(
            Collectors.toMap(
                Map.Entry::getKey,
                entry -> new PageItem<>(entry.getValue(), orders.get(entry.getKey()))));
  }

  @Transactional(readOnly = true)
  public MediaPage<BaseCollectable<?>> getPage(UUID profileId, MediaPaginationOptions options) {
    return paginationService.buildMediaPage(
        loadPage(profileId, options),
        options,
        opposite -> loadPage(profileId, opposite).stream().map(PageItem::item).toList());
  }

  private List<PageItem<BaseCollectable<?>>> loadPage(
      UUID profileId, MediaPaginationOptions options) {
    var ordered = continueWatchingRepository.findItems(profileId, options);
    var byId = loadItems(ordered.stream().map(PageItem::item).toList());
    return ordered.stream()
        .filter(item -> byId.containsKey(item.item()))
        .map(item -> new PageItem<BaseCollectable<?>>(byId.get(item.item()), item.sortValue()))
        .toList();
  }

  private Map<UUID, BaseCollectable<?>> loadItems(List<UUID> itemIds) {
    if (itemIds.isEmpty()) {
      return Map.of();
    }

    Map<UUID, BaseCollectable<?>> byId = new HashMap<>();
    movieRepository.findAllById(itemIds).forEach(movie -> byId.put(movie.getId(), movie));
    episodeRepository.findAllById(itemIds).forEach(episode -> byId.put(episode.getId(), episode));
    return byId;
  }
}
