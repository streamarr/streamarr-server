package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.DgsTypeResolver;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.ProfileShareLine;
import com.streamarr.server.domain.pagination.OrderMediaBy;
import com.streamarr.server.domain.pagination.Position;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.graphql.cursor.InvalidCursorException;
import com.streamarr.server.graphql.cursor.PositionCursorCodec;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dataloaders.ReaderLoaderKey;
import com.streamarr.server.graphql.dataloaders.ShareHouseholdDataLoader;
import com.streamarr.server.graphql.dataloaders.ShareOffererNameDataLoader;
import com.streamarr.server.graphql.dataloaders.ShareProfileDataLoader;
import com.streamarr.server.graphql.dto.HouseholdSummary;
import com.streamarr.server.graphql.dto.NotificationDetails;
import com.streamarr.server.graphql.dto.NotificationsChangeDetails;
import com.streamarr.server.graphql.inputs.MarkAllNotificationsReadInput;
import com.streamarr.server.graphql.inputs.MarkNotificationReadInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.notification.MarkAllNotificationsReadPayload;
import com.streamarr.server.graphql.mutation.notification.MarkNotificationReadPayload;
import com.streamarr.server.graphql.mutation.notification.NotificationErrors;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.ShareReference;
import com.streamarr.server.services.identity.SharedProfile;
import com.streamarr.server.services.notification.NotificationService;
import com.streamarr.server.services.notification.NotificationsShown;
import com.streamarr.server.services.pagination.ListIdentity;
import com.streamarr.server.services.pagination.MediaOrder;
import com.streamarr.server.services.pagination.PaginationService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;

/**
 * The signed-in Account's Notifications (ADR 0063). Every name a Notification shows loads through
 * the share's own DataLoaders, for the reader who asks and under the share's read rules.
 */
@DgsComponent
@RequiredArgsConstructor
public class NotificationResolver {

  private static final String LIST = "notifications";

  private final NotificationService notificationService;
  private final AuthorizationService authorizationService;
  private final PaginationService paginationService;
  private final RelayConnectionAdapter connections;
  private final PositionCursorCodec cursors;
  private final Clock clock;

  @DgsQuery
  public Connection<NotificationDetails> notifications(DataFetchingEnvironment dfe) {
    int first = dfe.getArgumentOrDefault("first", 20);
    var pagination =
        paginationService.getPaginationOptions(first, dfe.getArgument("after"), 0, null);
    var identity = authorizationService.currentIdentity();
    var list = listOf(identity.accountId());
    var page = notificationService.notifications(identity, cursors.pageRequest(list, pagination));
    var now = clock.instant();
    return connections.toOrderedConnection(
        page.map(notification -> NotificationDetails.from(notification, now)),
        list,
        NotificationResolver::orderOf);
  }

  /** A change's shown Notifications as the schema shows them, each with the query's edge. */
  NotificationsChangeDetails changeDetails(NotificationsShown shown) {
    var now = clock.instant();
    return new NotificationsChangeDetails(
        shown.notifications().stream()
            .map(
                row -> {
                  var list = listOf(row.item().notification().getRecipientAccountId());
                  var details =
                      new Row<>(NotificationDetails.from(row.item(), now), row.position());
                  return new NotificationsChangeDetails.NotificationChangeDetails(
                      row.item().notification().getId(),
                      connections.toEdge(details, list, NotificationResolver::orderOf));
                })
            .toList(),
        shown.unreadCount());
  }

  @DgsQuery
  public int unreadNotificationCount() {
    return notificationService.unreadNotificationCount(authorizationService.currentIdentity());
  }

  @DgsMutation
  public MarkNotificationReadPayload markNotificationRead(
      @InputArgument MarkNotificationReadInput input) {
    return MutationPayloads.withUuid(
        input.notificationId(),
        notificationId -> {
          var now = clock.instant();
          return MutationPayloads.payload(
              notificationService
                  .markNotificationRead(authorizationService.currentIdentity(), notificationId)
                  .map(
                      read ->
                          new MarkNotificationReadPayload.MarkedNotification(
                              NotificationDetails.from(read.notification(), now),
                              read.unreadCount())),
              NotificationErrors::toMarkReadError,
              MarkNotificationReadPayload::of);
        },
        () ->
            MutationPayloads.inputError(
                NotificationErrors.invalidNotificationId(), MarkNotificationReadPayload::of));
  }

  @DgsMutation
  public MarkAllNotificationsReadPayload markAllNotificationsRead(
      @InputArgument MarkAllNotificationsReadInput input) {
    var identity = authorizationService.currentIdentity();
    return MutationPayloads.payload(
        notificationService.markAllNotificationsRead(
            identity, positionOf(listOf(identity.accountId()), input.through())),
        NotificationErrors::toMarkAllReadError,
        MarkAllNotificationsReadPayload::of);
  }

  // A cursor that GraphQL accepts but that names no position in this Account's list is a value
  // the server cannot use, which the mutation answers as InvalidCursorError.
  private Optional<Position> positionOf(ListIdentity list, String cursor) {
    try {
      return Optional.of(cursors.decode(list, cursor));
    } catch (InvalidCursorException _) {
      return Optional.empty();
    }
  }

  @DgsTypeResolver(name = "Notification")
  public String notificationType(NotificationDetails notification) {
    return typeOf(notification.kind());
  }

  @DgsTypeResolver(name = "RequestNotification")
  public String requestNotificationType(NotificationDetails notification) {
    return typeOf(notification.kind());
  }

  @DgsData(parentType = "ProfileShareOfferedNotification", field = "profileName")
  @DgsData(parentType = "ProfileShareAnsweredNotification", field = "profileName")
  @DgsData(parentType = "ProfileShareEndedNotification", field = "profileName")
  public CompletableFuture<String> profileName(DataFetchingEnvironment dfe) {
    NotificationDetails notification = dfe.getSource();
    return profileNameOf(dfe, notification, keyOf(dfe));
  }

  @DgsData(parentType = "ProfileShareOfferedNotification", field = "message")
  @DgsData(parentType = "ProfileShareAnsweredNotification", field = "message")
  @DgsData(parentType = "ProfileShareEndedNotification", field = "message")
  public CompletableFuture<String> message(DataFetchingEnvironment dfe) {
    NotificationDetails notification = dfe.getSource();
    var key = keyOf(dfe);
    DataLoader<ReaderLoaderKey<ShareReference>, String> offerers =
        dfe.getDataLoader(ShareOffererNameDataLoader.NAME);
    DataLoader<ReaderLoaderKey<ShareReference>, HouseholdSummary> households =
        dfe.getDataLoader(ShareHouseholdDataLoader.NAME);
    var profileName = profileNameOf(dfe, notification, key);
    // Each kind's line names one other party, so only that party's loader is asked.
    var offererName =
        notification.kind() == NotificationKind.PROFILE_SHARE_OFFERED
            ? offerers.load(key)
            : CompletableFuture.<String>completedFuture(null);
    var household =
        notification.kind() == NotificationKind.PROFILE_SHARE_OFFERED
            ? CompletableFuture.<HouseholdSummary>completedFuture(null)
            : households.load(key);
    return CompletableFuture.allOf(profileName, offererName, household)
        .thenApply(
            _ ->
                ProfileShareLine.builder()
                    .kind(notification.kind())
                    .profileName(profileName.join())
                    .offererName(Optional.ofNullable(offererName.join()))
                    .householdName(
                        Optional.ofNullable(household.join()).map(HouseholdSummary::name))
                    .answer(notification.answer())
                    .endedBy(notification.endedBy())
                    .build()
                    .text());
  }

  // A reader who may see the share's Profile reads its current name, and any other reader the name
  // that the Notification kept from its latest event.
  private static CompletableFuture<String> profileNameOf(
      DataFetchingEnvironment dfe,
      NotificationDetails notification,
      ReaderLoaderKey<ShareReference> key) {
    DataLoader<ReaderLoaderKey<ShareReference>, SharedProfile> profiles =
        dfe.getDataLoader(ShareProfileDataLoader.NAME);
    return profiles
        .load(key)
        .thenApply(
            shared -> shared == null ? notification.keptProfileName() : shared.profile().getName());
  }

  private ReaderLoaderKey<ShareReference> keyOf(DataFetchingEnvironment dfe) {
    NotificationDetails notification = dfe.getSource();
    return new ReaderLoaderKey<>(
        authorizationService.currentIdentity(), notification.share().parties());
  }

  private static ListIdentity listOf(UUID accountId) {
    return new ListIdentity(LIST, accountId);
  }

  // The list sorts by an instant, newest first, as the Library's ADDED order does, so the edge
  // reuses that order's key.
  private static MediaOrder orderOf(Position position) {
    return MediaOrder.at(OrderMediaBy.ADDED, SortDirection.DESC, position);
  }

  private static String typeOf(NotificationKind kind) {
    return switch (kind) {
      case PROFILE_SHARE_OFFERED -> "ProfileShareOfferedNotification";
      case PROFILE_SHARE_ANSWERED -> "ProfileShareAnsweredNotification";
      case PROFILE_SHARE_ENDED -> "ProfileShareEndedNotification";
    };
  }
}
