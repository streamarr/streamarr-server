package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.pagination.ListOrder;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.services.pagination.PagedList;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The shares of one Profile in the scope's statuses, newest first. */
@Component
@RequiredArgsConstructor
public class ProfileSharesList implements PagedList<ProfileHouseholdShare, ShareListScope> {

  private final ProfileHouseholdShareRepository shareRepository;
  private final Clock clock;

  @Override
  public ListOrder order(ShareListScope profile) {
    return ShareListOrder.NEWEST_FIRST;
  }

  @Override
  public List<Row<ProfileHouseholdShare>> rowsBeyond(ShareListScope profile, Seek seek) {
    return shareRepository.rowsOfProfile(profile.id(), profile.filterAt(clock.instant()), seek);
  }
}
