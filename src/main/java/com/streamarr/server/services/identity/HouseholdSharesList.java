package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.pagination.ListOrder;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.services.pagination.PagedList;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The shares into one Household that the scope keeps, newest first. */
@Component
@RequiredArgsConstructor
public class HouseholdSharesList implements PagedList<ProfileHouseholdShare, HouseholdSharesScope> {

  private final ProfileHouseholdShareRepository shareRepository;
  private final Clock clock;

  @Override
  public ListOrder order(HouseholdSharesScope household) {
    return ShareListOrder.NEWEST_FIRST;
  }

  @Override
  public List<Row<ProfileHouseholdShare>> rowsBeyond(HouseholdSharesScope household, Seek seek) {
    return shareRepository.rowsIntoHousehold(household.filterAt(clock.instant()), seek);
  }

  /** Where the list places the share, or nothing when the scope does not keep it. */
  public Optional<Row<ProfileHouseholdShare>> rowOf(HouseholdSharesScope household, UUID shareId) {
    return shareRepository.findRowIntoHousehold(household.filterAt(clock.instant()), shareId);
  }
}
