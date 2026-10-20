package com.streamarr.server.services;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.pagination.ListOrder;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.PagedList;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** A Library's movies that a filter admits, in the filter's sort order. */
@Component
@RequiredArgsConstructor
public class LibraryMoviesList implements PagedList<Movie, MediaFilter> {

  private final MovieRepository movieRepository;

  @Override
  public ListOrder order(MediaFilter filter) {
    return filter.listOrder();
  }

  @Override
  public List<Row<Movie>> rowsBeyond(MediaFilter filter, Seek seek) {
    return movieRepository.rowsInLibrary(filter, seek);
  }
}
