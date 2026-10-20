package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
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

/** Every invitation on the server, newest first. */
@Component
@RequiredArgsConstructor
public class AccountInvitationsList implements PagedList<AccountInvitation, ServerScope> {

  private static final ListOrder NEWEST_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.DESC).build();

  private final AccountInvitationRepository accountInvitationRepository;

  @Override
  public ListOrder order(ServerScope scope) {
    return NEWEST_FIRST;
  }

  @Override
  public List<Row<AccountInvitation>> rowsBeyond(ServerScope scope, Seek seek) {
    return accountInvitationRepository.rowsBeyond(seek);
  }
}
