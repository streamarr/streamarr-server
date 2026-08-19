package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsQuery;
import com.streamarr.server.graphql.cursor.ListConnections;
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

  private final AuthorizationService authorizationService;
  private final IdentityQueryService identityQueryService;
  private final PaginationService paginationService;

  @DgsQuery
  public Me me() {
    var identity = authorizationService.currentIdentity();
    var view = identityQueryService.meView(identity);

    return Me.builder()
        .accountId(view.account().getId())
        .email(view.account().getEmail())
        .displayName(view.account().getDisplayName())
        .serverAdmin(view.account().isServerAdmin())
        .scope(view.scope().claimValue())
        .household(toSummary(view.household()))
        .householdRole(view.householdRole())
        .contextHousehold(toSummary(view.contextHousehold()))
        .usableHouseholds(view.usableHouseholds().stream().map(MeResolver::toUsable).toList())
        .selectableProfiles(
            view.selectableProfiles().stream().map(MeResolver::toSelectable).toList())
        .selectedProfile(
            view.selectedProfile() == null ? null : toSelectable(view.selectedProfile()))
        .deviceBound(view.deviceBound())
        .build();
  }

  @DgsData(parentType = "Me", field = "usableHouseholds")
  public Connection<UsableHousehold> usableHouseholds(DataFetchingEnvironment dfe) {
    Me me = dfe.getSource();
    return ListConnections.page(
        me.usableHouseholds(), usable -> usable.household().id().toString(), options(dfe));
  }

  @DgsData(parentType = "Me", field = "selectableProfiles")
  public Connection<SelectableProfile> selectableProfiles(DataFetchingEnvironment dfe) {
    Me me = dfe.getSource();
    return ListConnections.page(
        me.selectableProfiles(), profile -> profile.id().toString(), options(dfe));
  }

  private PaginationOptions options(DataFetchingEnvironment dfe) {
    int first = dfe.getArgumentOrDefault("first", 0);
    String after = dfe.getArgument("after");
    int last = dfe.getArgumentOrDefault("last", 0);
    String before = dfe.getArgument("before");
    return paginationService.getPaginationOptions(
        firstOrDefault(first, last, before), after, last, before);
  }

  /** A picker-sized default when the client names no page size. */
  private static int firstOrDefault(int first, int last, String before) {
    if (first == 0 && last == 0 && before == null) {
      return 100;
    }
    return first;
  }

  private static HouseholdSummary toSummary(IdentityQueryService.HouseholdSummaryView view) {
    return new HouseholdSummary(view.id(), view.name());
  }

  private static UsableHousehold toUsable(IdentityQueryService.UsableHouseholdView view) {
    return new UsableHousehold(toSummary(view.household()), view.membership());
  }

  private static SelectableProfile toSelectable(IdentityQueryService.SelectableProfileView view) {
    return SelectableProfile.builder()
        .id(view.id())
        .name(view.name())
        .picture(view.picture().orElse(null))
        .kind(view.kind())
        .personal(view.personal())
        .pinConfigured(view.pinConfigured())
        .locked(view.locked())
        .selected(view.selected())
        .build();
  }
}
