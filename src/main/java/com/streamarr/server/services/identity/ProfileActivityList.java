package com.streamarr.server.services.identity;

import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.services.pagination.ListOrder;
import com.streamarr.server.services.pagination.PagedList;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValueKind;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** One Profile's viewing activity, most recently changed first. */
@Component
@RequiredArgsConstructor
public class ProfileActivityList implements PagedList<SessionProgress, UUID> {

  private static final ListOrder MOST_RECENT_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.DESC).build();

  private final SessionProgressRepository sessionProgressRepository;

  @Override
  public ListOrder order(UUID profileId) {
    return MOST_RECENT_FIRST;
  }

  @Override
  public List<Row<SessionProgress>> rowsBeyond(UUID profileId, Seek seek) {
    return sessionProgressRepository.rowsBeyond(profileId, seek);
  }
}
