package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.services.pagination.ListOrder;
import com.streamarr.server.services.pagination.PagedList;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.ServerScope;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValueKind;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Every Household on the server, by name without regard to case. */
@Component
@RequiredArgsConstructor
public class HouseholdsList implements PagedList<Household, ServerScope> {

  private static final ListOrder BY_NAME =
      ListOrder.builder().valueKind(SortValueKind.TEXT).direction(SortDirection.ASC).build();

  private final HouseholdRepository householdRepository;

  @Override
  public ListOrder order(ServerScope scope) {
    return BY_NAME;
  }

  @Override
  public List<Row<Household>> rowsBeyond(ServerScope scope, Seek seek) {
    return householdRepository.rowsBeyond(seek);
  }
}
