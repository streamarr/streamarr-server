package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.pagination.ListOrder;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.domain.pagination.SortValueKind;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.pagination.PagedList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The Profiles one Account manages, in every Household, by name without regard to case. */
@Component
@RequiredArgsConstructor
public class ManagedProfilesList implements PagedList<Profile, UUID> {

  private static final ListOrder BY_NAME =
      ListOrder.builder().valueKind(SortValueKind.TEXT).direction(SortDirection.ASC).build();

  private final ProfileRepository profileRepository;

  @Override
  public ListOrder order(UUID accountId) {
    return BY_NAME;
  }

  @Override
  public List<Row<Profile>> rowsBeyond(UUID accountId, Seek seek) {
    return profileRepository.rowsManagedBy(accountId, seek);
  }
}
