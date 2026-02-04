package com.streamarr.server.fakes;

import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.MediaPaginationOptions;
import com.streamarr.server.graphql.cursor.OrderMoviesBy;
import com.streamarr.server.graphql.cursor.PaginationDirection;
import com.streamarr.server.repositories.media.MovieRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.jooq.SortOrder;

public class FakeMovieRepository extends FakeJpaRepository<Movie> implements MovieRepository {

  @Override
  public Optional<Movie> findByTmdbId(String tmdbId) {
    return database.values().stream()
        .filter(
            movie ->
                movie.getExternalIds().stream()
                    .anyMatch(
                        id ->
                            id.getExternalSourceType() == ExternalSourceType.TMDB
                                && id.getExternalId().equals(tmdbId)))
        .findFirst();
  }

  @Override
  public List<Movie> findFirstWithFilter(MediaPaginationOptions options) {
    var filter = options.getMediaFilter();
    var limit = options.getPaginationOptions().getLimit();

    return filterByLibrary(filter).sorted(comparatorFor(filter)).limit(limit + 1L).toList();
  }

  @Override
  public List<Movie> seekWithFilter(MediaPaginationOptions options) {
    var filter = options.getMediaFilter();
    var shouldReverse =
        options.getPaginationOptions().getPaginationDirection().equals(PaginationDirection.REVERSE);
    var limit = options.getPaginationOptions().getLimit();

    var effectiveFilter = shouldReverse ? reverseFilter(filter) : filter;

    var sorted = filterByLibrary(effectiveFilter).sorted(comparatorFor(effectiveFilter)).toList();

    var cursorId = options.getCursorId().orElse(null);
    int startIndex = 0;
    if (cursorId != null) {
      for (int i = 0; i < sorted.size(); i++) {
        if (sorted.get(i).getId().equals(cursorId)) {
          startIndex = i;
          break;
        }
      }
    }

    var endIndex = Math.min(sorted.size(), startIndex + limit + 2);
    var result = new ArrayList<>(sorted.subList(startIndex, endIndex));

    if (shouldReverse) {
      Collections.reverse(result);
    }

    return result;
  }

  private Stream<Movie> filterByLibrary(MediaFilter filter) {
    var libraryId = filter.getLibraryId();
    var stream = database.values().stream();

    if (libraryId != null) {
      stream =
          stream.filter(m -> m.getLibrary() != null && libraryId.equals(m.getLibrary().getId()));
    }

    return stream;
  }

  private Comparator<Movie> comparatorFor(MediaFilter filter) {
    Comparator<Movie> primary =
        filter.getSortBy() == OrderMoviesBy.ADDED
            ? Comparator.comparing(
                Movie::getCreatedOn, Comparator.nullsLast(Comparator.naturalOrder()))
            : Comparator.comparing(Movie::getTitle);

    if (filter.getSortDirection() == SortOrder.DESC) {
      primary = primary.reversed();
    }

    return primary.thenComparing(Movie::getId);
  }

  private MediaFilter reverseFilter(MediaFilter filter) {
    var reversed = filter.getSortDirection() == SortOrder.DESC ? SortOrder.ASC : SortOrder.DESC;
    return filter.toBuilder().sortDirection(reversed).build();
  }
}
