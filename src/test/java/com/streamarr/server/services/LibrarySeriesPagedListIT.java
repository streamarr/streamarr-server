package com.streamarr.server.services;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.support.PagedListContract;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Library series paging")
class LibrarySeriesPagedListIT extends PagedListContract<Series, Library> {

  private static final LocalDate LATEST_FIRST_AIR_DATE = LocalDate.of(2026, 10, 20);

  @Autowired private SeriesService seriesService;
  @Autowired private SeriesRepository series;
  @Autowired private LibraryRepository libraries;

  @Override
  protected Library createScope() {
    return libraries.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
  }

  @Override
  protected void deleteScope(Library library) {
    series.deleteAll(series.findByLibrary_Id(library.getId()));
    libraries.deleteById(library.getId());
  }

  @Override
  protected UUID add(Library library, int rank) {
    return series
        .saveAndFlush(
            Series.builder()
                .title("Paged series " + rank)
                .titleSort("Paged series " + rank)
                .firstAirDate(LATEST_FIRST_AIR_DATE.minusDays(rank))
                .library(library)
                .build())
        .getId();
  }

  // Removing a series takes a Library scan; deleting the row still proves a page continues.
  @Override
  protected void remove(Library library, UUID item) {
    series.deleteById(item);
    series.flush();
  }

  @Override
  protected ListPage<Series> page(Library library, PageRequest request) {
    return seriesService.getSeriesWithFilter(
        MediaFilter.builder()
            .libraryId(library.getId())
            .sortBy(OrderMediaBy.RELEASE_DATE)
            .sortDirection(SortDirection.DESC)
            .build(),
        request);
  }

  @Override
  protected UUID idOf(Series item) {
    return item.getId();
  }

  @Override
  protected SortDirection tieDirection() {
    return SortDirection.DESC;
  }

  @Override
  protected boolean sortValuesCanTie() {
    return true;
  }

  @Override
  protected Optional<Position> positionBeforeArrangedItems(Library library) {
    return Optional.empty();
  }

  @Override
  protected Optional<Position> positionAfterArrangedItems(Library library) {
    return Optional.empty();
  }
}
