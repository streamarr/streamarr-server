package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfilePolicyTarget;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.cursor.ConnectionArguments;
import com.streamarr.server.graphql.cursor.PositionCursorCodec;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dto.HouseholdProfile;
import com.streamarr.server.graphql.dto.ProfileAdministration;
import com.streamarr.server.graphql.dto.ProfileLockPreview;
import com.streamarr.server.graphql.dto.ProfileManagerDetails;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.ProfileLockPreviewService;
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
  private final ProfileLockPreviewService profileLockPreviewService;
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

  @DgsQuery
  public Connection<HouseholdProfile> householdProfiles(
      @InputArgument String householdId, DataFetchingEnvironment dfe) {
    var household = Ids.parseUuid(householdId);
    var list = new ListIdentity("household-profiles", household);
    var page =
        profileManagementQueryService.householdProfiles(
            authorizationService.currentIdentity(), household, pageRequest(list, dfe));
    return relayConnectionAdapter.toConnection(page, list, HouseholdProfile::from);
  }

  @DgsQuery
  public Connection<ProfileManagerDetails> profileManagers(
      @InputArgument String profileId, DataFetchingEnvironment dfe) {
    var profile = Ids.parseUuid(profileId);
    var list = new ListIdentity("profile-managers", profile);
    var page =
        profileManagementQueryService.profileManagers(
            authorizationService.currentIdentity(), profile, pageRequest(list, dfe));
    return relayConnectionAdapter.toConnection(page, list, ProfileManagerDetails::from);
  }

  @DgsQuery
  public ProfileLockPreview profilePolicyChangePreview(
      @InputArgument String profileId,
      @InputArgument ProfileKind kind,
      @InputArgument Integer maximumAllowedRatingAge) {
    return profileLockPreviewService
        .policyChangePreview(
            authorizationService.currentIdentity(),
            Ids.parseUuid(profileId),
            new ProfilePolicyTarget(kind, maximumAllowedRatingAge))
        .map(ProfileLockPreview::from)
        .orElse(null);
  }

  @DgsQuery
  public ProfileLockPreview profileCreationPreview(
      @InputArgument String householdId,
      @InputArgument ProfileKind kind,
      @InputArgument Integer maximumAllowedRatingAge) {
    return profileLockPreviewService
        .creationPreview(
            authorizationService.currentIdentity(),
            Ids.parseUuid(householdId),
            new ProfilePolicyTarget(kind, maximumAllowedRatingAge))
        .map(ProfileLockPreview::from)
        .orElse(null);
  }

  private PageRequest pageRequest(ListIdentity list, DataFetchingEnvironment dfe) {
    return positionCursors.pageRequest(
        list, ConnectionArguments.paginationOptions(paginationService, dfe, DEFAULT_PAGE_SIZE));
  }
}
