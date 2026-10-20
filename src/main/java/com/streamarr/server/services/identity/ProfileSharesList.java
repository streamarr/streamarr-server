package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
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

/** Every share of one Profile in any status, newest first. */
@Component
@RequiredArgsConstructor
public class ProfileSharesList implements PagedList<ProfileHouseholdShare, UUID> {

  private static final ListOrder NEWEST_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.DESC).build();

  private final ProfileHouseholdShareRepository shareRepository;

  @Override
  public ListOrder order(UUID profileId) {
    return NEWEST_FIRST;
  }

  @Override
  public List<Row<ProfileHouseholdShare>> rowsBeyond(UUID profileId, Seek seek) {
    return shareRepository.rowsOfProfile(profileId, seek);
  }
}
