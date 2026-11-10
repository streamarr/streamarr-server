package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.pagination.ListOrder;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.domain.pagination.SortValueKind;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.services.pagination.PagedList;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The shares into one Household in the scope's statuses, newest first. */
@Component
@RequiredArgsConstructor
public class HouseholdSharesList implements PagedList<ProfileHouseholdShare, ShareListScope> {

  private static final ListOrder NEWEST_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.DESC).build();

  private final ProfileHouseholdShareRepository shareRepository;
  private final Clock clock;

  @Override
  public ListOrder order(ShareListScope household) {
    return NEWEST_FIRST;
  }

  @Override
  public List<Row<ProfileHouseholdShare>> rowsBeyond(ShareListScope household, Seek seek) {
    return shareRepository.rowsIntoHousehold(
        household.id(), household.filterAt(clock.instant()), seek);
  }
}
