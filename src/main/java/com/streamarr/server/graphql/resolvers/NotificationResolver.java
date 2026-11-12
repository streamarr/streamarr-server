package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.DgsTypeResolver;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.StoredNotification;
import com.streamarr.server.domain.pagination.OrderMediaBy;
import com.streamarr.server.domain.pagination.Position;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.cursor.ConnectionArguments;
import com.streamarr.server.graphql.cursor.PositionCursorCodec;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dto.NotificationDetails;
import com.streamarr.server.graphql.dto.OrderedEdge;
import com.streamarr.server.graphql.inputs.MarkNotificationsReadInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.notification.MarkNotificationsReadPayload;
import com.streamarr.server.graphql.mutation.notification.NotificationErrors;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.events.notification.ProfileManagementNotificationKinds;
import com.streamarr.server.services.events.notification.SharingNotificationKinds;
import com.streamarr.server.services.notification.NotificationService;
import com.streamarr.server.services.notification.NotificationsList;
import com.streamarr.server.services.pagination.ListIdentity;
import com.streamarr.server.services.pagination.MediaOrder;
import com.streamarr.server.services.pagination.PaginationService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;

/**
 * The signed-in Account's Notifications (ADR 0063). A Notification shows what its recipient was
 * told and its subject's id, never the subject: a client opens the subject through its own queries.
 */
@DgsComponent
@RequiredArgsConstructor
public class NotificationResolver {

  private static final String LIST = "notifications";
  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final Map<String, String> TYPES =
      Map.of(
          SharingNotificationKinds.PROFILE_SHARE_OFFERED.name(), "ProfileShareOfferedNotification",
          SharingNotificationKinds.PROFILE_SHARE_ANSWERED.name(),
              "ProfileShareAnsweredNotification",
          SharingNotificationKinds.PROFILE_SHARE_ENDED.name(), "ProfileShareEndedNotification",
          ProfileManagementNotificationKinds.PROFILE_MANAGER_INVITATION_RECEIVED.name(),
              "ProfileManagerInvitationReceivedNotification");

  private final NotificationService notificationService;
  private final AuthorizationService authorizationService;
  private final PaginationService paginationService;
  private final RelayConnectionAdapter connections;
  private final PositionCursorCodec cursors;
  private final Clock clock;

  @DgsQuery
  public Connection<NotificationDetails> notifications(DataFetchingEnvironment dfe) {
    var pagination =
        ConnectionArguments.paginationOptions(paginationService, dfe, DEFAULT_PAGE_SIZE);
    var identity = authorizationService.currentIdentity();
    var list = listOf(identity.accountId());
    var page = notificationService.notifications(identity, cursors.pageRequest(list, pagination));
    var now = clock.instant();
    return connections.toOrderedConnection(
        page.map(notification -> NotificationDetails.from(notification, now)),
        list,
        NotificationResolver::orderOf);
  }

  /** The edge the notifications query gives the Notification, read for the Account at once. */
  OrderedEdge<NotificationDetails> edgeOf(UUID accountId, Row<StoredNotification> row) {
    return connections.toEdge(
        new Row<>(NotificationDetails.from(row.item(), clock.instant()), row.position()),
        listOf(accountId),
        NotificationResolver::orderOf);
  }

  @DgsQuery
  public int unreadNotificationCount() {
    return notificationService.unreadNotificationCount(authorizationService.currentIdentity());
  }

  @DgsMutation
  public MarkNotificationsReadPayload markNotificationsRead(
      @InputArgument MarkNotificationsReadInput input) {
    var identity = authorizationService.currentIdentity();
    notificationService.requireMarkNotificationsRead(identity);
    var ids = input.notificationIds();
    var malformed = firstMalformed(ids);
    if (malformed.isPresent()) {
      return MutationPayloads.inputError(
          NotificationErrors.invalidNotificationId(malformed.getAsInt()),
          MarkNotificationsReadPayload::of);
    }

    return MutationPayloads.payload(
        notificationService.markNotificationsRead(
            identity, ids.stream().map(Ids::parseUuid).toList()),
        NotificationErrors::toMarkReadError,
        MarkNotificationsReadPayload::of);
  }

  private static OptionalInt firstMalformed(List<String> ids) {
    return IntStream.range(0, ids.size())
        .filter(index -> !MutationPayloads.withUuid(ids.get(index), _ -> true, () -> false))
        .findFirst();
  }

  @DgsTypeResolver(name = "Notification")
  public String notificationType(NotificationDetails notification) {
    return typeOf(notification.kind());
  }

  @DgsTypeResolver(name = "RequestNotification")
  public String requestNotificationType(NotificationDetails notification) {
    return typeOf(notification.kind());
  }

  private static ListIdentity listOf(UUID accountId) {
    return new ListIdentity(LIST, accountId);
  }

  // The list sorts by an instant, as the Library's ADDED order does, so the edge reuses that
  // order's key in the list's own direction.
  private static MediaOrder orderOf(Position position) {
    return MediaOrder.at(OrderMediaBy.ADDED, NotificationsList.NEWEST_FIRST.direction(), position);
  }

  // Each kind the server sends has its concrete type. A kind with none fails its own node alone.
  private static String typeOf(NotificationKind kind) {
    var type = TYPES.get(kind.name());
    if (type == null) {
      throw new IllegalStateException("No schema type shows the %s kind".formatted(kind.name()));
    }

    return type;
  }
}
