package com.streamarr.server.services.watchprogress;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.streaming.ContinueWatchingRepository;
import com.streamarr.server.services.pagination.ListOrder;
import com.streamarr.server.services.pagination.PagedList;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
import com.streamarr.server.services.pagination.SortValueKind;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** A Profile's in-progress movies and episodes, most recently watched first. */
@Component
@RequiredArgsConstructor
public class ContinueWatchingList implements PagedList<BaseCollectable<?>, UUID> {

  public static final ListOrder LAST_WATCHED_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.DESC).build();

  private final ContinueWatchingRepository continueWatchingRepository;
  private final MovieRepository movieRepository;
  private final EpisodeRepository episodeRepository;

  @Override
  public ListOrder order(UUID profileId) {
    return LAST_WATCHED_FIRST;
  }

  @Override
  public List<Row<BaseCollectable<?>>> rowsBeyond(UUID profileId, Seek seek) {
    var rows = continueWatchingRepository.rowsBeyond(profileId, seek);
    var byId = loadItems(rows.stream().map(Row::item).toList());
    return rows.stream()
        .filter(row -> byId.containsKey(row.item()))
        .map(row -> new Row<BaseCollectable<?>>(byId.get(row.item()), row.position()))
        .toList();
  }

  /** The rows of the given items that are in the list, keyed by item ID. */
  public Map<UUID, Row<BaseCollectable<?>>> rowsOf(UUID profileId, Set<UUID> itemIds) {
    if (itemIds.isEmpty()) {
      return Map.of();
    }

    var lastWatched = continueWatchingRepository.findMatchingItems(profileId, itemIds);
    return loadItems(List.copyOf(lastWatched.keySet())).entrySet().stream()
        .collect(
            Collectors.toMap(
                Map.Entry::getKey,
                entry ->
                    new Row<>(
                        entry.getValue(),
                        new Position(
                            new SortValue.OfInstant(lastWatched.get(entry.getKey())),
                            entry.getKey()))));
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
