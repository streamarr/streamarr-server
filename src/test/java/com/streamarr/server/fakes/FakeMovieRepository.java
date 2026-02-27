package com.streamarr.server.fakes;

import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.MediaPaginationOptions;
import com.streamarr.server.graphql.cursor.OrderMediaBy;
import com.streamarr.server.graphql.cursor.PaginationDirection;
import com.streamarr.server.repositories.media.MovieRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    return filterByLibrary(filter)
        .sorted(comparatorFor(filter, filter.getSortDirection()))
        .limit(limit + 1L)
        .toList();
  }

  @Override
  public List<Movie> seekWithFilter(MediaPaginationOptions options) {
    var filter = options.getMediaFilter();
    var shouldReverse =
        options.getPaginationOptions().getPaginationDirection().equals(PaginationDirection.REVERSE);
    var limit = options.getPaginationOptions().getLimit();

    var effectiveFilter = shouldReverse ? FakeFilterHelper.reverseFilter(filter) : filter;

    var sorted =
        filterByLibrary(effectiveFilter)
            .sorted(comparatorFor(effectiveFilter, effectiveFilter.getSortDirection()))
            .toList();

    var startIndex = FakeFilterHelper.findCursorIndex(sorted, options.getCursorId());
    var endIndex = Math.min(sorted.size(), startIndex + limit + 2);
    var result = new ArrayList<>(sorted.subList(startIndex, endIndex));

    if (shouldReverse) {
      Collections.reverse(result);
    }

    return result;
  }

  private Stream<Movie> filterByLibrary(MediaFilter filter) {
    var libraryId = filter.getLibraryId();

    Stream<Movie> stream =
        libraryId == null
            ? database.values().stream()
            : database.values().stream()
                .filter(m -> m.getLibrary() != null && libraryId.equals(m.getLibrary().getId()));

    return applyFilters(filterByStartLetter(stream, filter), filter);
  }

  private Stream<Movie> applyFilters(Stream<Movie> stream, MediaFilter filter) {
    var genreIds = filter.getGenreIds();
    if (genreIds != null && !genreIds.isEmpty()) {
      stream =
          stream.filter(m -> m.getGenres().stream().anyMatch(g -> genreIds.contains(g.getId())));
    }

    var years = filter.getYears();
    if (years != null && !years.isEmpty()) {
      stream =
          stream.filter(
              m -> m.getReleaseDate() != null && years.contains(m.getReleaseDate().getYear()));
    }

    var contentRatings = filter.getContentRatings();
    if (contentRatings != null && !contentRatings.isEmpty()) {
      stream =
          stream.filter(
              m ->
                  m.getContentRating() != null
                      && contentRatings.contains(m.getContentRating().value()));
    }

    var studioIds = filter.getStudioIds();
    if (studioIds != null && !studioIds.isEmpty()) {
      stream =
          stream.filter(m -> m.getStudios().stream().anyMatch(s -> studioIds.contains(s.getId())));
    }

    var directorIds = filter.getDirectorIds();
    if (directorIds != null && !directorIds.isEmpty()) {
      stream =
          stream.filter(
              m -> m.getDirectors().stream().anyMatch(d -> directorIds.contains(d.getId())));
    }

    var castMemberIds = filter.getCastMemberIds();
    if (castMemberIds != null && !castMemberIds.isEmpty()) {
      stream =
          stream.filter(m -> m.getCast().stream().anyMatch(p -> castMemberIds.contains(p.getId())));
    }

    if (Boolean.TRUE.equals(filter.getUnmatched())) {
      stream = stream.filter(m -> m.getExternalIds().isEmpty());
    }

    return stream;
  }

  private Stream<Movie> filterByStartLetter(Stream<Movie> stream, MediaFilter filter) {
    var letter = filter.getStartLetter();
    if (letter == null) {
      return stream;
    }

    if (filter.getSortBy() != OrderMediaBy.TITLE) {
      return stream.filter(m -> FakeFilterHelper.matchesLetterEquality(m.getTitle(), letter));
    }

    if (filter.getSortDirection() == SortOrder.DESC) {
      return stream.filter(m -> FakeFilterHelper.matchesLetterDescRange(m.getTitle(), letter));
    }

    return stream.filter(m -> FakeFilterHelper.matchesLetterAscRange(m.getTitle(), letter));
  }

  private Comparator<Movie> comparatorFor(MediaFilter filter, SortOrder idSortOrder) {
    Comparator<Movie> primary =
        switch (filter.getSortBy()) {
          case ADDED ->
              Comparator.comparing(
                  Movie::getCreatedOn, Comparator.nullsLast(Comparator.naturalOrder()));
          case RELEASE_DATE ->
              Comparator.comparing(
                  Movie::getReleaseDate, Comparator.nullsLast(Comparator.naturalOrder()));
          case RUNTIME ->
              Comparator.comparing(
                  Movie::getRuntime, Comparator.nullsLast(Comparator.naturalOrder()));
          case TITLE -> Comparator.comparing(Movie::getTitle);
        };

    if (filter.getSortDirection() == SortOrder.DESC) {
      primary = primary.reversed();
    }

    Comparator<Movie> idComparator = Comparator.comparing(Movie::getId);
    if (idSortOrder == SortOrder.DESC) {
      idComparator = idComparator.reversed();
    }

    return primary.thenComparing(idComparator);
  }

  @Override
  public List<Movie> findByLibrary_Id(UUID libraryId) {
    return database.values().stream()
        .filter(movie -> movie.getLibrary() != null && libraryId.equals(movie.getLibrary().getId()))
        .toList();
  }

  @Override
  public List<Movie> findByLibraryIdWithExternalIds(UUID libraryId) {
    return findByLibrary_Id(libraryId);
  }
}
