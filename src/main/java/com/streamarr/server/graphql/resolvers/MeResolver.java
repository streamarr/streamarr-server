package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsQuery;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dto.HouseholdSummary;
import com.streamarr.server.graphql.dto.Me;
import com.streamarr.server.graphql.dto.SelectableProfile;
import com.streamarr.server.graphql.dto.UsableHousehold;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.IdentityQueryService;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class MeResolver {

  private static final int DEFAULT_PAGE_SIZE = 100;

  private final AuthorizationService authorizationService;
  private final IdentityQueryService identityQueryService;
  private final PaginationService paginationService;
  private final CursorUtil cursorUtil;
  private final RelayConnectionAdapter relayConnectionAdapter;

  @DgsQuery
  public Me me() {
    var identity = authorizationService.currentIdentity();
    var details = identityQueryService.meDetails(identity);

    return Me.builder()
        .accountId(details.account().getId())
        .email(details.account().getEmail())
        .displayName(details.account().getDisplayName())
        .serverAdmin(details.account().isServerAdmin())
        .scope(details.scope().claimValue())
        .household(toSummary(details.household()))
        .householdRole(details.householdRole())
        .contextHousehold(toSummary(details.contextHousehold()))
        .deviceBound(details.deviceBound())
        .build();
  }

  @DgsData(parentType = "Me", field = "usableHouseholds")
  public Connection<UsableHousehold> usableHouseholds(DataFetchingEnvironment dfe) {
    var identity = authorizationService.currentIdentity();
    var options = options(dfe);
    var page =
        identityQueryService.usableHouseholds(identity, cursorUtil.decodeKeysetCursor(options));
    return relayConnectionAdapter.toConnection(
        page,
        item -> toUsable(item.item()),
        item -> cursorUtil.encodeKeysetCursor(item.item().household().id()));
  }

  @DgsData(parentType = "Me", field = "selectableProfiles")
  public Connection<SelectableProfile> selectableProfiles(DataFetchingEnvironment dfe) {
    var identity = authorizationService.currentIdentity();
    var options = options(dfe);
    var page =
        identityQueryService.selectableProfiles(identity, cursorUtil.decodeKeysetCursor(options));
    return relayConnectionAdapter.toConnection(
        page,
        item -> toSelectable(item.item()),
        item -> cursorUtil.encodeKeysetCursor(item.item().id()));
  }

  @DgsData(parentType = "Me", field = "selectedProfile")
  public SelectableProfile selectedProfile() {
    var identity = authorizationService.currentIdentity();
    return identityQueryService
        .selectedProfile(identity)
        .map(MeResolver::toSelectable)
        .orElse(null);
  }

  private PaginationOptions options(DataFetchingEnvironment dfe) {
    int first = dfe.getArgumentOrDefault("first", 0);
    String after = dfe.getArgument("after");
    int last = dfe.getArgumentOrDefault("last", 0);
    String before = dfe.getArgument("before");
    if (first == 0 && last == 0 && before != null) {
      return paginationService.getPaginationOptions(first, after, DEFAULT_PAGE_SIZE, before);
    }

    return paginationService.getPaginationOptions(
        firstOrDefault(first, last, before), after, last, before);
  }

  /** A picker-sized default when the client names no page size. */
  private static int firstOrDefault(int first, int last, String before) {
    if (first == 0 && last == 0 && before == null) {
      return DEFAULT_PAGE_SIZE;
    }

    return first;
  }

  private static HouseholdSummary toSummary(IdentityQueryService.HouseholdSummaryDetails details) {
    return new HouseholdSummary(details.id(), details.name());
  }

  private static UsableHousehold toUsable(IdentityQueryService.UsableHouseholdDetails details) {
    return new UsableHousehold(toSummary(details.household()), details.membership());
  }

  private static SelectableProfile toSelectable(
      IdentityQueryService.SelectableProfileDetails details) {
    return SelectableProfile.builder()
        .id(details.id())
        .name(details.name())
        .picture(details.picture().orElse(null))
        .kind(details.kind())
        .personal(details.personal())
        .pinConfigured(details.pinConfigured())
        .locked(details.locked())
        .selected(details.selected())
        .build();
  }
}
