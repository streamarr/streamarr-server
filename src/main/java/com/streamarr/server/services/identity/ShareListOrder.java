package com.streamarr.server.services.identity;

import com.streamarr.server.domain.pagination.ListOrder;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.domain.pagination.SortValueKind;

/** The order of every list of Profile shares: the newest share first, by when it was created. */
public final class ShareListOrder {

  public static final ListOrder NEWEST_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.DESC).build();

  private ShareListOrder() {}
}
