package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.HouseholdProfilesFilter;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.pagination.ListOrder;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.domain.pagination.SortValueKind;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.pagination.PagedList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The Profiles of one Household that one reader's Household settings list, by name. */
@Component
@RequiredArgsConstructor
public class HouseholdProfilesList implements PagedList<Profile, HouseholdProfilesFilter> {

  private static final ListOrder BY_NAME =
      ListOrder.builder().valueKind(SortValueKind.TEXT).direction(SortDirection.ASC).build();

  private final ProfileRepository profileRepository;

  @Override
  public ListOrder order(HouseholdProfilesFilter filter) {
    return BY_NAME;
  }

  @Override
  public List<Row<Profile>> rowsBeyond(HouseholdProfilesFilter filter, Seek seek) {
    return profileRepository.rowsInHousehold(filter, seek);
  }
}
