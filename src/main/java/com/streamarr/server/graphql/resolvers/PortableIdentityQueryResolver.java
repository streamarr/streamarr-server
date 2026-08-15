package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.streamarr.server.graphql.dto.PortableProfileManagerInvitationSummary;
import com.streamarr.server.graphql.dto.PortableProfileManagerSummary;
import com.streamarr.server.graphql.dto.PortableProfileShareSummary;
import com.streamarr.server.services.auth.PortableIdentityQueryService;
import com.streamarr.server.services.authorization.AuthorizationService;
import java.util.List;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class PortableIdentityQueryResolver {

  private final AuthorizationService authorizationService;
  private final PortableIdentityQueryService queryService;

  @DgsQuery
  public List<PortableProfileShareSummary> portableProfileShares() {
    return queryService.shares(authorizationService.currentIdentity()).stream()
        .map(PortableProfileShareSummary::from)
        .toList();
  }

  @DgsQuery
  public List<PortableProfileManagerInvitationSummary> portableProfileManagerInvitations() {
    return queryService.invitations(authorizationService.currentIdentity()).stream()
        .map(PortableProfileManagerInvitationSummary::from)
        .toList();
  }

  @DgsQuery
  public List<PortableProfileManagerSummary> portableProfileManagers() {
    return queryService.managers(authorizationService.currentIdentity()).stream()
        .map(PortableProfileManagerSummary::from)
        .toList();
  }
}
