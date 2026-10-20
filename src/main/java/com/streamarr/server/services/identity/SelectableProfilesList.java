package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.repositories.auth.ProfileRepository;
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

/** The Profiles available in one Household, by name without regard to case. */
@Component
@RequiredArgsConstructor
public class SelectableProfilesList implements PagedList<Profile, UUID> {

  private static final ListOrder BY_NAME =
      ListOrder.builder().valueKind(SortValueKind.TEXT).direction(SortDirection.ASC).build();

  private final ProfileRepository profileRepository;

  @Override
  public ListOrder order(UUID householdId) {
    return BY_NAME;
  }

  @Override
  public List<Row<Profile>> rowsBeyond(UUID householdId, Seek seek) {
    return profileRepository.rowsAvailableInHousehold(householdId, seek);
  }
}
