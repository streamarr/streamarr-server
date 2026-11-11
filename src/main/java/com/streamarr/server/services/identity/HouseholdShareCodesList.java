package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ShareCode;
import com.streamarr.server.domain.pagination.ListOrder;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.domain.pagination.SortValueKind;
import com.streamarr.server.repositories.auth.ShareCodeRepository;
import com.streamarr.server.services.pagination.PagedList;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The live share codes of one Household, newest first. */
@Component
@RequiredArgsConstructor
public class HouseholdShareCodesList implements PagedList<ShareCode, UUID> {

  private static final ListOrder NEWEST_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.DESC).build();

  private final ShareCodeRepository shareCodeRepository;
  private final Clock clock;

  @Override
  public ListOrder order(UUID householdId) {
    return NEWEST_FIRST;
  }

  @Override
  public List<Row<ShareCode>> rowsBeyond(UUID householdId, Seek seek) {
    return shareCodeRepository.liveRowsInHousehold(householdId, clock.instant(), seek);
  }
}
