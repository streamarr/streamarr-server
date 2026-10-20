package com.streamarr.server.services;

import com.streamarr.server.domain.media.Series;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.pagination.ListOrder;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.PagedList;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** A Library's series that a filter admits, in the filter's sort order. */
@Component
@RequiredArgsConstructor
public class LibrarySeriesList implements PagedList<Series, MediaFilter> {

  private final SeriesRepository seriesRepository;

  @Override
  public ListOrder order(MediaFilter filter) {
    return filter.listOrder();
  }

  @Override
  public List<Row<Series>> rowsBeyond(MediaFilter filter, Seek seek) {
    return seriesRepository.rowsInLibrary(filter, seek);
  }
}
