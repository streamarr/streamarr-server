package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.repositories.auth.HouseholdRepository;
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

/** The Households one Account may use: its own first, then those it visits, by name. */
@Component
@RequiredArgsConstructor
public class UsableHouseholdsList implements PagedList<Household, UUID> {

  private static final ListOrder OWN_FIRST_THEN_BY_NAME =
      ListOrder.builder().valueKind(SortValueKind.TEXT).direction(SortDirection.ASC).build();

  private final HouseholdRepository householdRepository;

  @Override
  public ListOrder order(UUID accountId) {
    return OWN_FIRST_THEN_BY_NAME;
  }

  @Override
  public List<Row<Household>> rowsBeyond(UUID accountId, Seek seek) {
    return householdRepository.rowsUsableBy(accountId, seek);
  }
}
