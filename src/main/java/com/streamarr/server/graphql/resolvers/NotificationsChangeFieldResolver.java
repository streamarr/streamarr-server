package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.graphql.dto.NotificationDetails;
import com.streamarr.server.graphql.dto.NotificationsChangeDetails;
import com.streamarr.server.graphql.dto.NotificationsChangeDetails.NotificationChangeDetails;
import com.streamarr.server.graphql.dto.OrderedEdge;
import com.streamarr.server.services.notification.NotificationService;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;

/**
 * The fields of a Notifications change, each read on a delivery thread when the change reaches its
 * operation. A read that fails nulls its own field and reports an error at its path, so the
 * operation goes on to the next change.
 */
@DgsComponent
@RequiredArgsConstructor
public class NotificationsChangeFieldResolver {

  private final NotificationService notificationService;
  private final NotificationResolver notificationResolver;

  @DgsData(parentType = "NotificationsChange", field = "unreadNotificationCount")
  public Integer unreadNotificationCount(DataFetchingEnvironment dfe) {
    NotificationsChangeDetails change = dfe.getSource();
    return notificationService.unreadCountAfterChange(change.reader());
  }

  // A Notification that no longer exists has a null edge and no error.
  @DgsData(parentType = "NotificationChange", field = "edge")
  public OrderedEdge<NotificationDetails> edge(DataFetchingEnvironment dfe) {
    NotificationChangeDetails changed = dfe.getSource();
    var reader = changed.reader();
    return notificationService
        .changedNotification(reader, changed.notificationId())
        .map(row -> notificationResolver.edgeOf(reader.accountId(), row))
        .orElse(null);
  }
}
