package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.pagination.ListOrder;
import com.streamarr.server.services.pagination.PagedList;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValueKind;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The Accounts of one Household, by display name without regard to case. */
@Component
@RequiredArgsConstructor
public class HouseholdAccountsList implements PagedList<UserAccount, UUID> {

  private static final ListOrder BY_DISPLAY_NAME =
      ListOrder.builder().valueKind(SortValueKind.TEXT).direction(SortDirection.ASC).build();

  private final UserAccountRepository userAccountRepository;

  @Override
  public ListOrder order(UUID householdId) {
    return BY_DISPLAY_NAME;
  }

  @Override
  public List<Row<UserAccount>> rowsBeyond(UUID householdId, Seek seek) {
    return rowsBeyond(Map.of(householdId, seek)).get(householdId);
  }

  @Override
  public Map<UUID, List<Row<UserAccount>>> rowsBeyond(Map<UUID, Seek> seeksByHousehold) {
    return userAccountRepository.rowsBeyond(seeksByHousehold);
  }
}
