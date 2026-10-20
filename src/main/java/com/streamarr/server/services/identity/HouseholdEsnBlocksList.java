package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.EsnBlock;
import com.streamarr.server.repositories.auth.EsnBlockRepository;
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

/** The ESN blocks of one Household, newest first. */
@Component
@RequiredArgsConstructor
public class HouseholdEsnBlocksList implements PagedList<EsnBlock, UUID> {

  private static final ListOrder NEWEST_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.DESC).build();

  private final EsnBlockRepository esnBlockRepository;

  @Override
  public ListOrder order(UUID householdId) {
    return NEWEST_FIRST;
  }

  @Override
  public List<Row<EsnBlock>> rowsBeyond(UUID householdId, Seek seek) {
    return esnBlockRepository.rowsInHousehold(householdId, seek);
  }
}
