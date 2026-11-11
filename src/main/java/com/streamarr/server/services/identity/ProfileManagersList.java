package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.domain.pagination.ListOrder;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.domain.pagination.SortValueKind;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.pagination.PagedList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The Accounts that manage one Profile: the Account whose own Personal Profile it is first, then
 * the direct managers by display name without regard to case.
 */
@Component
@RequiredArgsConstructor
public class ProfileManagersList implements PagedList<UserAccount, UUID> {

  private static final ListOrder OWN_ACCOUNT_THEN_BY_NAME =
      ListOrder.builder().valueKind(SortValueKind.TEXT).direction(SortDirection.ASC).build();

  private final UserAccountRepository userAccountRepository;

  @Override
  public ListOrder order(UUID profileId) {
    return OWN_ACCOUNT_THEN_BY_NAME;
  }

  @Override
  public List<Row<UserAccount>> rowsBeyond(UUID profileId, Seek seek) {
    return userAccountRepository.rowsManaging(profileId, seek);
  }
}
