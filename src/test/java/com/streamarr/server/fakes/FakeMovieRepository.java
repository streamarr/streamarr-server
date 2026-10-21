package com.streamarr.server.fakes;

import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.SortValue;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FakeMovieRepository extends FakeJpaRepository<Movie> implements MovieRepository {

  @Override
  public List<Row<Movie>> rowsInLibrary(MediaFilter filter, Seek seek) {
    return InMemoryKeyset.rowsBeyond(
        listed(filter).map(movie -> new Row<>(movie, positionOf(movie, filter))).toList(), seek);
  }

  @Override
  public Optional<Row<Movie>> rowInLibrary(UUID itemId, MediaFilter filter) {
    return listed(filter)
        .filter(movie -> movie.getId().equals(itemId))
        .findFirst()
        .map(movie -> new Row<>(movie, positionOf(movie, filter)));
  }

  private Stream<Movie> listed(MediaFilter filter) {
    return filterByStartLetter(filterByLibrary(filter), filter);
  }

  private static Position positionOf(Movie movie, MediaFilter filter) {
    var value =
        switch (filter.getSortBy()) {
          case TITLE -> FakeFilterHelper.titleSortValue(movie.getTitleSort());
          case ADDED -> movie.getCreatedOn();
          case RELEASE_DATE -> movie.getReleaseDate();
          case RUNTIME -> movie.getRuntime();
          case LAST_WATCHED ->
              throw new UnsupportedOperationException("Last watched is tested against PostgreSQL");
        };
    return new Position(SortValue.of(value), movie.getId());
  }

  @Override
  public Set<UUID> findUnmatchedMovieIds(Collection<UUID> ids) {
    return database.values().stream()
        .filter(item -> ids.contains(item.getId()))
        .filter(item -> item.getExternalIds().isEmpty())
        .map(Movie::getId)
        .collect(Collectors.toSet());
  }

  @Override
  public Optional<Movie> findInLibraryByTmdbId(UUID libraryId, String tmdbId) {
    return database.values().stream()
        .filter(movie -> movie.getLibrary() != null && libraryId.equals(movie.getLibrary().getId()))
        .filter(
            movie ->
                movie.getExternalIds().stream()
                    .anyMatch(
                        id ->
                            id.getExternalSourceType() == ExternalSourceType.TMDB
                                && id.getExternalId().equals(tmdbId)))
        .findFirst();
  }

  private Stream<Movie> filterByLibrary(MediaFilter filter) {
    var libraryId = filter.getLibraryId();

    Stream<Movie> stream =
        libraryId == null
            ? database.values().stream()
            : database.values().stream()
                .filter(m -> m.getLibrary() != null && libraryId.equals(m.getLibrary().getId()));

    return applyFilters(stream, filter);
  }

  private Stream<Movie> filterByStartLetter(Stream<Movie> stream, MediaFilter filter) {
    var letter = filter.getStartLetter();
    if (letter == null || filter.getSortBy() == OrderMediaBy.TITLE) {
      return stream;
    }
    return stream.filter(m -> FakeFilterHelper.matchesLetterEquality(m.getTitleSort(), letter));
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

  @Override
  public List<Movie> findByLibrary_Id(UUID libraryId) {
    return database.values().stream()
        .filter(movie -> movie.getLibrary() != null && libraryId.equals(movie.getLibrary().getId()))
        .toList();
  }

  @Override
  public List<Movie> findWithExternalIdsByLibrary_Id(UUID libraryId) {
    return findByLibrary_Id(libraryId);
  }
}
