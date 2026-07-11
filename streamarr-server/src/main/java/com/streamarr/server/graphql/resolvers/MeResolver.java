package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.streamarr.server.graphql.dto.Me;
import com.streamarr.server.graphql.dto.Membership;
import com.streamarr.server.graphql.dto.SelectableProfile;
import com.streamarr.server.services.auth.IdentityQueryService;
import com.streamarr.server.services.authorization.AuthorizationService;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class MeResolver {

  private final AuthorizationService authorizationService;
  private final IdentityQueryService identityQueryService;

  @DgsQuery
  public Me me() {
    var identity = authorizationService.currentIdentity();
    var view = identityQueryService.meView(identity);

    return Me.builder()
        .accountId(view.account().getId())
        .email(view.account().getEmail())
        .displayName(view.account().getDisplayName())
        .role(view.account().getAccountRole().name())
        .scope(view.scope().claimValue())
        .memberships(view.memberships().stream().map(MeResolver::toMembership).toList())
        .build();
  }

  private static Membership toMembership(IdentityQueryService.MembershipView view) {
    return Membership.builder()
        .householdId(view.householdId())
        .householdName(view.householdName())
        .householdRole(view.householdRole().name())
        .profiles(
            view.profiles().stream()
                .map(
                    profile ->
                        new SelectableProfile(profile.id(), profile.name(), profile.active()))
                .toList())
        .build();
  }
}
