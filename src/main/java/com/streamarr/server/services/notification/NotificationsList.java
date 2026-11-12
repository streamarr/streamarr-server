package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.RecipientReference;
import com.streamarr.server.domain.notification.StoredNotification;
import com.streamarr.server.domain.pagination.ListOrder;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.domain.pagination.SortValueKind;
import com.streamarr.server.repositories.notification.NotificationRepository;
import com.streamarr.server.services.pagination.PagedList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * One Account's Notifications, newest latest event first (ADR 0063 §The list's order and paging).
 * Its rows are the Account's own, read in SQL in list order.
 */
@Component
@RequiredArgsConstructor
public class NotificationsList implements PagedList<StoredNotification, UUID> {

  public static final ListOrder NEWEST_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.DESC).build();

  private final NotificationRepository notificationRepository;

  @Override
  public ListOrder order(UUID recipientAccountId) {
    return NEWEST_FIRST;
  }

  @Override
  public List<Row<StoredNotification>> rowsBeyond(UUID recipientAccountId, Seek seek) {
    return notificationRepository.rowsOfRecipient(
        RecipientReference.account(recipientAccountId), seek);
  }
}
