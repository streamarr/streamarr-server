package com.streamarr.server.services;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.support.PagedListContract;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Library movie paging")
class LibraryMoviesPagedListIT extends PagedListContract<Movie, Library> {

  @Autowired private MovieService movieService;
  @Autowired private MovieRepository movies;
  @Autowired private LibraryRepository libraries;

  @Override
  protected Library createScope() {
    return libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
  }

  @Override
  protected void deleteScope(Library library) {
    movies.deleteAll(movies.findByLibrary_Id(library.getId()));
    libraries.deleteById(library.getId());
  }

  @Override
  protected UUID add(Library library, int rank) {
    return movies
        .saveAndFlush(
            Movie.builder()
                .title("Paged movie " + rank)
                .titleSort("Paged movie " + rank)
                .library(library)
                .build())
        .getId();
  }

  // Removing a movie takes a Library scan; deleting the row still proves a page continues.
  @Override
  protected void remove(Library library, UUID item) {
    movies.deleteById(item);
    movies.flush();
  }

  @Override
  protected ListPage<Movie> page(Library library, PageRequest request) {
    return movieService.getMoviesWithFilter(
        MediaFilter.builder()
            .libraryId(library.getId())
            .sortBy(OrderMediaBy.TITLE)
            .sortDirection(SortDirection.ASC)
            .build(),
        request);
  }

  @Override
  protected UUID idOf(Movie movie) {
    return movie.getId();
  }

  @Override
  protected SortDirection tieDirection() {
    return SortDirection.ASC;
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
