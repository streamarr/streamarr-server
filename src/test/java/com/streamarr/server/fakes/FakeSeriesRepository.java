package com.streamarr.server.fakes;

import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.PaginationDirection;
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

  @Override
  public List<Series> findByLibrary_Id(UUID libraryId) {
    return database.values().stream()
        .filter(
            series -> series.getLibrary() != null && libraryId.equals(series.getLibrary().getId()))
        .toList();
  }

  @Override
  public List<Series> findByLibraryIdWithExternalIds(UUID libraryId) {
    return findByLibrary_Id(libraryId);
  }

  private Stream<Series> filterByLibrary(MediaFilter filter) {
    var libraryId = filter.getLibraryId();

    Stream<Series> stream =
        libraryId == null
            ? database.values().stream()
            : database.values().stream()
                .filter(s -> s.getLibrary() != null && libraryId.equals(s.getLibrary().getId()));

    return applyFilters(filterByStartLetter(stream, filter), filter);
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

  private Stream<Series> filterByStartLetter(Stream<Series> stream, MediaFilter filter) {
    var letter = filter.getStartLetter();
    if (letter == null) {
      return stream;
    }

    if (filter.getSortBy() != OrderMediaBy.TITLE) {
      return stream.filter(s -> FakeFilterHelper.matchesLetterEquality(s.getTitle(), letter));
    }

    if (filter.getSortDirection() == SortOrder.DESC) {
      return stream.filter(s -> FakeFilterHelper.matchesLetterDescRange(s.getTitle(), letter));
    }

    return stream.filter(s -> FakeFilterHelper.matchesLetterAscRange(s.getTitle(), letter));
  }

  private Comparator<Series> comparatorFor(MediaFilter filter, SortOrder idSortOrder) {
    var isDesc = filter.getSortDirection() == SortOrder.DESC;

    Comparator<Series> primary =
        switch (filter.getSortBy()) {
          case ADDED -> Comparator.comparing(Series::getCreatedOn, nullsLastDirectional(isDesc));
          case RELEASE_DATE ->
              Comparator.comparing(Series::getFirstAirDate, nullsLastDirectional(isDesc));
          case RUNTIME -> Comparator.comparing(Series::getRuntime, nullsLastDirectional(isDesc));
          case TITLE ->
              isDesc
                  ? Comparator.comparing(Series::getTitle, Comparator.reverseOrder())
                  : Comparator.comparing(Series::getTitle);
          case LAST_WATCHED ->
              throw new UnsupportedOperationException("LAST_WATCHED not yet implemented in fake");
        };

    Comparator<Series> idComparator = Comparator.comparing(Series::getId);
    if (idSortOrder == SortOrder.DESC) {
      idComparator = idComparator.reversed();
    }

    return primary.thenComparing(idComparator);
  }

  private <T extends Comparable<T>> Comparator<T> nullsLastDirectional(boolean desc) {
    Comparator<T> inner = desc ? Comparator.reverseOrder() : Comparator.naturalOrder();
    return Comparator.nullsLast(inner);
  }
}
