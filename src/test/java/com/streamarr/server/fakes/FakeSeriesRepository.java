package com.streamarr.server.fakes;

import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.MediaPaginationOptions;
import com.streamarr.server.graphql.cursor.OrderMediaBy;
import com.streamarr.server.graphql.cursor.PaginationDirection;
import com.streamarr.server.repositories.media.SeriesRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.jooq.SortOrder;

public class FakeSeriesRepository extends FakeJpaRepository<Series> implements SeriesRepository {

  @Override
  public Optional<Series> findByTmdbId(String tmdbId) {
    return database.values().stream()
        .filter(
            series ->
                series.getExternalIds().stream()
                    .anyMatch(
                        id ->
                            id.getExternalSourceType() == ExternalSourceType.TMDB
                                && id.getExternalId().equals(tmdbId)))
        .findFirst();
  }

  @Override
  public List<Series> findFirstWithFilter(MediaPaginationOptions options) {
    var filter = options.getMediaFilter();
    var limit = options.getPaginationOptions().getLimit();

    return filterByLibrary(filter)
        .sorted(comparatorFor(filter, filter.getSortDirection()))
        .limit(limit + 1L)
        .toList();
  }

  @Override
  public List<Series> seekWithFilter(MediaPaginationOptions options) {
    var filter = options.getMediaFilter();
    var shouldReverse =
        options.getPaginationOptions().getPaginationDirection().equals(PaginationDirection.REVERSE);
    var limit = options.getPaginationOptions().getLimit();

    var effectiveFilter = shouldReverse ? reverseFilter(filter) : filter;

    var sorted =
        filterByLibrary(effectiveFilter)
            .sorted(comparatorFor(effectiveFilter, effectiveFilter.getSortDirection()))
            .toList();

    var startIndex = findCursorIndex(sorted, options.getCursorId());
    var endIndex = Math.min(sorted.size(), startIndex + limit + 2);
    var result = new ArrayList<>(sorted.subList(startIndex, endIndex));

    if (shouldReverse) {
      Collections.reverse(result);
    }

    return result;
  }

  @Override
  public List<Series> findByLibrary_Id(UUID libraryId) {
    return database.values().stream()
        .filter(
            series -> series.getLibrary() != null && libraryId.equals(series.getLibrary().getId()))
        .toList();
  }

  private int findCursorIndex(List<Series> sorted, Optional<UUID> cursorId) {
    if (cursorId.isEmpty()) {
      return 0;
    }

    var id = cursorId.get();
    for (int i = 0; i < sorted.size(); i++) {
      if (sorted.get(i).getId().equals(id)) {
        return i;
      }
    }

    return 0;
  }

  private Stream<Series> filterByLibrary(MediaFilter filter) {
    var libraryId = filter.getLibraryId();

    if (libraryId == null) {
      return database.values().stream();
    }

    return database.values().stream()
        .filter(s -> s.getLibrary() != null && libraryId.equals(s.getLibrary().getId()));
  }

  private Comparator<Series> comparatorFor(MediaFilter filter, SortOrder idSortOrder) {
    Comparator<Series> primary =
        filter.getSortBy() == OrderMediaBy.ADDED
            ? Comparator.comparing(
                Series::getCreatedOn, Comparator.nullsLast(Comparator.naturalOrder()))
            : Comparator.comparing(Series::getTitle);

    if (filter.getSortDirection() == SortOrder.DESC) {
      primary = primary.reversed();
    }

    Comparator<Series> idComparator = Comparator.comparing(Series::getId);
    if (idSortOrder == SortOrder.DESC) {
      idComparator = idComparator.reversed();
    }

    return primary.thenComparing(idComparator);
  }

  private MediaFilter reverseFilter(MediaFilter filter) {
    var reversed = filter.getSortDirection() == SortOrder.DESC ? SortOrder.ASC : SortOrder.DESC;
    return filter.toBuilder().sortDirection(reversed).build();
  }
}
