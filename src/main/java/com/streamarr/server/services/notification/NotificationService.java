package com.streamarr.server.services.notification;

import com.streamarr.server.domain.notification.ShareNotification;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Pager;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the signed-in Account reads and marks of its own Notifications (ADR 0063). Every read and
 * mark is refused to a device-bound session, and shows only the Notifications that the guard shows
 * the Account, decided from its stored relationships.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

  private final AuthorizationService authorizationService;
  private final NotificationsList notificationsList;

  /** The Account's Notifications, newest latest event first. */
  @Transactional(readOnly = true)
  public ListPage<ShareNotification> notifications(
      @NonNull AuthenticatedIdentity identity, @NonNull PageRequest request) {
    authorizationService.requireAllowed(identity, new Intent.ViewNotifications());
    return Pager.page(notificationsList, identity.accountId(), request);
  }
}
