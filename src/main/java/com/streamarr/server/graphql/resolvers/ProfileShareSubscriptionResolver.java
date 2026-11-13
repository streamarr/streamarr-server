package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsSubscription;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.cursor.ShareListCursors;
import com.streamarr.server.graphql.dto.KeyedEdge;
import com.streamarr.server.graphql.dto.ProfileShareChangeDetails;
import com.streamarr.server.graphql.dto.ProfileShareDetails;
import com.streamarr.server.graphql.subscriptions.SubscriptionBroadcaster;
import com.streamarr.server.services.auth.events.ProfileShareChangedEvent;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.HouseholdSharesScope;
import com.streamarr.server.services.identity.ProfileSharingService;
import com.streamarr.server.services.identity.ShareListOrder;
import com.streamarr.server.services.identity.ShareListScope;
import com.streamarr.server.services.pagination.ListIdentity;
import graphql.schema.DataFetchingEnvironment;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;

@DgsComponent
@RequiredArgsConstructor
public class ProfileShareSubscriptionResolver {

  private final AuthorizationService authorizationService;
  private final ProfileSharingService profileSharingService;
  private final SubscriptionBroadcaster<ProfileShareChangedEvent> profileShareChangeBroadcaster;
  private final RelayConnectionAdapter relayConnectionAdapter;
  private final Clock clock;

  // Each change is decided again as it is delivered, so authority that ends stops the changes,
  // and an Account that may no longer follow share changes ends the operation with the error.
  @DgsSubscription
  public Publisher<ProfileShareChangeDetails> profileShareChanged() {
    var identity = authorizationService.currentIdentity();
    profileSharingService.requireViewProfileShareChanges(identity);
    return profileShareChangeBroadcaster
        .register()
        .<ProfileShareChangeDetails>handle(
            (event, sink) ->
                profileSharingService
                    .shareChange(identity, event)
                    .map(change -> ProfileShareChangeDetails.from(change, clock.instant()))
                    .ifPresent(sink::next));
  }

  @DgsData(parentType = "ProfileShareChange")
  public Optional<KeyedEdge<ProfileShareDetails>> householdSharesEdge(
      @InputArgument List<ProfileShareStatus> statuses,
      @InputArgument Boolean visitorsOnly,
      DataFetchingEnvironment dfe) {
    return changedShare(dfe)
        .flatMap(
            share ->
                householdSharesEdge(
                    ShareListCursors.householdScope(share.householdId(), statuses, visitorsOnly),
                    share.id()));
  }

  @DgsData(parentType = "ProfileShareChange")
  public Optional<KeyedEdge<ProfileShareDetails>> profileSharesEdge(
      @InputArgument List<ProfileShareStatus> statuses, DataFetchingEnvironment dfe) {
    return changedShare(dfe)
        .flatMap(
            share ->
                profileSharesEdge(
                    ShareListCursors.profileScope(share.profileId(), statuses), share.id()));
  }

  // A share deleted with its Profile or its Household is in no list.
  private static Optional<ProfileShareDetails> changedShare(DataFetchingEnvironment dfe) {
    ProfileShareChangeDetails change = dfe.getSource();
    return Optional.ofNullable(change.share());
  }

  private Optional<KeyedEdge<ProfileShareDetails>> householdSharesEdge(
      HouseholdSharesScope household, UUID shareId) {
    return profileSharingService
        .householdShareRow(authorizationService.currentIdentity(), household, shareId)
        .map(row -> edge(row, ShareListCursors.listOf(household)));
  }

  private Optional<KeyedEdge<ProfileShareDetails>> profileSharesEdge(
      ShareListScope profile, UUID shareId) {
    return profileSharingService
        .profileShareRow(authorizationService.currentIdentity(), profile, shareId)
        .map(row -> edge(row, ShareListCursors.listOf(profile)));
  }

  private KeyedEdge<ProfileShareDetails> edge(Row<ProfileHouseholdShare> row, ListIdentity list) {
    return relayConnectionAdapter.toKeyedEdge(
        new Row<>(ProfileShareDetails.from(row.item(), clock.instant()), row.position()),
        list,
        ShareListOrder.NEWEST_FIRST);
  }
}
