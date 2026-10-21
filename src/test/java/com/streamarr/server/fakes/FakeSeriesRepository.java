package com.streamarr.server.fakes;

import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.repositories.media.SeriesRepository;
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

public class FakeSeriesRepository extends FakeJpaRepository<Series> implements SeriesRepository {

  @Override
  public List<Row<Series>> rowsInLibrary(MediaFilter filter, Seek seek) {
    return InMemoryKeyset.rowsBeyond(
        listed(filter).map(item -> new Row<>(item, positionOf(item, filter))).toList(), seek);
  }

  @Override
  public Optional<Row<Series>> rowInLibrary(UUID itemId, MediaFilter filter) {
    return listed(filter)
        .filter(item -> item.getId().equals(itemId))
        .findFirst()
        .map(item -> new Row<>(item, positionOf(item, filter)));
  }

  private Stream<Series> listed(MediaFilter filter) {
    return filterByStartLetter(filterByLibrary(filter), filter);
  }

  private static Position positionOf(Series item, MediaFilter filter) {
    var value =
        switch (filter.getSortBy()) {
          case TITLE -> FakeFilterHelper.titleSortValue(item.getTitleSort());
          case ADDED -> item.getCreatedOn();
          case RELEASE_DATE -> item.getFirstAirDate();
          case RUNTIME -> item.getRuntime();
          case LAST_WATCHED ->
              throw new UnsupportedOperationException("Last watched is tested against PostgreSQL");
        };
    return new Position(SortValue.of(value), item.getId());
  }

  @Override
  public Set<UUID> findUnmatchedSeriesIds(Collection<UUID> ids) {
    return database.values().stream()
        .filter(item -> ids.contains(item.getId()))
        .filter(item -> item.getExternalIds().isEmpty())
        .map(Series::getId)
        .collect(Collectors.toSet());
  }

  @Override
  public Optional<Series> findInLibraryByTmdbId(UUID libraryId, String tmdbId) {
    return database.values().stream()
        .filter(
            series -> series.getLibrary() != null && libraryId.equals(series.getLibrary().getId()))
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
  public List<Series> findByLibrary_Id(UUID libraryId) {
    return database.values().stream()
        .filter(
            series -> series.getLibrary() != null && libraryId.equals(series.getLibrary().getId()))
        .toList();
  }

  @Override
  public List<Series> findWithExternalIdsByLibrary_Id(UUID libraryId) {
    return findByLibrary_Id(libraryId);
  }

  private Stream<Series> filterByLibrary(MediaFilter filter) {
    var libraryId = filter.getLibraryId();

    Stream<Series> stream =
        libraryId == null
            ? database.values().stream()
            : database.values().stream()
                .filter(s -> s.getLibrary() != null && libraryId.equals(s.getLibrary().getId()));

    return applyFilters(stream, filter);
  }

  private Stream<Series> filterByStartLetter(Stream<Series> stream, MediaFilter filter) {
    var letter = filter.getStartLetter();
    if (letter == null || filter.getSortBy() == OrderMediaBy.TITLE) {
      return stream;
    }
    return stream.filter(s -> FakeFilterHelper.matchesLetterEquality(s.getTitleSort(), letter));
  }

  private Stream<Series> applyFilters(Stream<Series> stream, MediaFilter filter) {
    var genreIds = filter.getGenreIds();
    if (genreIds != null && !genreIds.isEmpty()) {
      stream =
          stream.filter(s -> s.getGenres().stream().anyMatch(g -> genreIds.contains(g.getId())));
    }

    var years = filter.getYears();
    if (years != null && !years.isEmpty()) {
      stream =
          stream.filter(
              s -> s.getFirstAirDate() != null && years.contains(s.getFirstAirDate().getYear()));
    }

    var contentRatings = filter.getContentRatings();
    if (contentRatings != null && !contentRatings.isEmpty()) {
      stream =
          stream.filter(
              s ->
                  s.getContentRating() != null
                      && contentRatings.contains(s.getContentRating().value()));
    }

    var studioIds = filter.getStudioIds();
    if (studioIds != null && !studioIds.isEmpty()) {
      stream =
          stream.filter(s -> s.getStudios().stream().anyMatch(c -> studioIds.contains(c.getId())));
    }

    var directorIds = filter.getDirectorIds();
    if (directorIds != null && !directorIds.isEmpty()) {
      stream =
          stream.filter(
              s -> s.getDirectors().stream().anyMatch(d -> directorIds.contains(d.getId())));
    }

    var castMemberIds = filter.getCastMemberIds();
    if (castMemberIds != null && !castMemberIds.isEmpty()) {
      stream =
          stream.filter(s -> s.getCast().stream().anyMatch(p -> castMemberIds.contains(p.getId())));
    }

    if (Boolean.TRUE.equals(filter.getUnmatched())) {
      stream = stream.filter(s -> s.getExternalIds().isEmpty());
    }

    return stream;
  }
}
