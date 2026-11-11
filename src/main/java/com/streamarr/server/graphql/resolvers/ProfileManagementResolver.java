package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.graphql.cursor.ConnectionArguments;
import com.streamarr.server.graphql.cursor.PositionCursorCodec;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dto.ProfileAdministration;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.ProfileManagementQueryService;
import com.streamarr.server.services.pagination.ListIdentity;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.PaginationService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;

/** The reads behind the Profile management pages of Household settings and Profile settings. */
@DgsComponent
@RequiredArgsConstructor
public class ProfileManagementResolver {

  private static final int DEFAULT_PAGE_SIZE = 100;

  private final AuthorizationService authorizationService;
  private final ProfileManagementQueryService profileManagementQueryService;
  private final PaginationService paginationService;
  private final PositionCursorCodec positionCursors;
  private final RelayConnectionAdapter relayConnectionAdapter;

  @DgsData(parentType = "Me", field = "managedProfiles")
  public Connection<ProfileAdministration> managedProfiles(DataFetchingEnvironment dfe) {
    var identity = authorizationService.currentIdentity();
    var list = new ListIdentity("managed-profiles", identity.accountId());
    var page = profileManagementQueryService.managedProfiles(identity, pageRequest(list, dfe));
    return relayConnectionAdapter.toConnection(
        page, list, details -> ProfileAdministration.from(details.profile(), details.linked()));
  }

  private PageRequest pageRequest(ListIdentity list, DataFetchingEnvironment dfe) {
    return positionCursors.pageRequest(
        list, ConnectionArguments.paginationOptions(paginationService, dfe, DEFAULT_PAGE_SIZE));
  }
}
