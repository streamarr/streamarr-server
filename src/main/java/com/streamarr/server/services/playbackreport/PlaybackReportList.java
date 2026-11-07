package com.streamarr.server.services.playbackreport;

import com.streamarr.server.repositories.playbackreport.PlaybackReportRepository;
import com.streamarr.server.services.pagination.ListOrder;
import com.streamarr.server.services.pagination.PagedList;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.ServerScope;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValueKind;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The server's shared playback reports, newest first. */
@Component
@RequiredArgsConstructor
public class PlaybackReportList implements PagedList<PlaybackReport, ServerScope> {

  private static final ListOrder NEWEST_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.DESC).build();

  private final PlaybackReportRepository playbackReportRepository;

  @Override
  public ListOrder order(ServerScope scope) {
    return NEWEST_FIRST;
  }

  @Override
  public List<Row<PlaybackReport>> rowsBeyond(ServerScope scope, Seek seek) {
    return playbackReportRepository.rowsBeyond(seek);
  }
}
