package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.cursor.ConnectionArguments;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dto.ProfileShareDetails;
import com.streamarr.server.graphql.dto.ProfileSharePreview;
import com.streamarr.server.graphql.inputs.AcceptProfileShareInput;
import com.streamarr.server.graphql.inputs.CancelProfileShareInput;
import com.streamarr.server.graphql.inputs.EndProfileShareInput;
import com.streamarr.server.graphql.inputs.ForceEndProfileShareInput;
import com.streamarr.server.graphql.inputs.OfferProfileShareInput;
import com.streamarr.server.graphql.inputs.RejectProfileShareInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.sharing.AcceptProfileSharePayload;
import com.streamarr.server.graphql.mutation.sharing.CancelProfileSharePayload;
import com.streamarr.server.graphql.mutation.sharing.EndProfileSharePayload;
import com.streamarr.server.graphql.mutation.sharing.ForceEndProfileSharePayload;
import com.streamarr.server.graphql.mutation.sharing.OfferProfileSharePayload;
import com.streamarr.server.graphql.mutation.sharing.RejectProfileSharePayload;
import com.streamarr.server.graphql.mutation.sharing.ShareErrors;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.ProfileSharingService;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.PaginationService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class ProfileSharingResolver {

  private static final int DEFAULT_PAGE_SIZE = 100;

  private final AuthorizationService authorizationService;
  private final ProfileSharingService profileSharingService;
  private final PaginationService paginationService;
  private final CursorUtil cursorUtil;
  private final RelayConnectionAdapter relayConnectionAdapter;

  @DgsQuery
  public ProfileSharePreview profileSharePreview(
      @InputArgument String profileId, @InputArgument String householdId) {
    return profileSharingService
        .sharePreflight(
            authorizationService.currentIdentity(),
            Ids.parseUuid(profileId),
            Ids.parseUuid(householdId))
        .map(preview -> new ProfileSharePreview(preview.wouldLock(), preview.nameConflict()))
        .orElse(null);
  }

  @DgsQuery
  public Connection<ProfileShareDetails> pendingShareOffers(
      @InputArgument String householdId, DataFetchingEnvironment dfe) {
    var options = ConnectionArguments.paginationOptions(paginationService, dfe, DEFAULT_PAGE_SIZE);
    var page =
        profileSharingService.pendingShareOffers(
            authorizationService.currentIdentity(),
            Ids.parseUuid(householdId),
            cursorUtil.decodeKeysetCursor(options));
    return toConnection(page);
  }

  @DgsQuery
  public Connection<ProfileShareDetails> profileShares(
      @InputArgument String profileId, DataFetchingEnvironment dfe) {
    var options = ConnectionArguments.paginationOptions(paginationService, dfe, DEFAULT_PAGE_SIZE);
    var page =
        profileSharingService.profileShares(
            authorizationService.currentIdentity(),
            Ids.parseUuid(profileId),
            cursorUtil.decodeKeysetCursor(options));
    return toConnection(page);
  }

  @DgsMutation
  public OfferProfileSharePayload offerProfileShare(@InputArgument OfferProfileShareInput input) {
    return MutationPayloads.payload(
        profileSharingService
            .offerProfileShare(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.profileId()),
                Ids.parseUuid(input.householdId()))
            .map(ProfileShareDetails::from),
        ShareErrors::toOfferError,
        OfferProfileSharePayload::new);
  }

  @DgsMutation
  public AcceptProfileSharePayload acceptProfileShare(
      @InputArgument AcceptProfileShareInput input) {
    return MutationPayloads.payload(
        profileSharingService
            .acceptProfileShare(
                authorizationService.currentIdentity(), Ids.parseUuid(input.shareId()))
            .map(ProfileShareDetails::from),
        ShareErrors::toAcceptError,
        AcceptProfileSharePayload::new);
  }

  @DgsMutation
  public RejectProfileSharePayload rejectProfileShare(
      @InputArgument RejectProfileShareInput input) {
    return MutationPayloads.payload(
        profileSharingService
            .rejectProfileShare(
                authorizationService.currentIdentity(), Ids.parseUuid(input.shareId()))
            .map(ProfileShareDetails::from),
        ShareErrors::toRejectError,
        RejectProfileSharePayload::new);
  }

  @DgsMutation
  public CancelProfileSharePayload cancelProfileShare(
      @InputArgument CancelProfileShareInput input) {
    return MutationPayloads.payload(
        profileSharingService
            .cancelProfileShare(
                authorizationService.currentIdentity(), Ids.parseUuid(input.shareId()))
            .map(ProfileShareDetails::from),
        ShareErrors::toCancelError,
        CancelProfileSharePayload::new);
  }

  @DgsMutation
  public EndProfileSharePayload endProfileShare(@InputArgument EndProfileShareInput input) {
    return MutationPayloads.payload(
        profileSharingService
            .endProfileShare(authorizationService.currentIdentity(), Ids.parseUuid(input.shareId()))
            .map(ProfileShareDetails::from),
        ShareErrors::toEndError,
        EndProfileSharePayload::new);
  }

  @DgsMutation
  public ForceEndProfileSharePayload forceEndProfileShare(
      @InputArgument ForceEndProfileShareInput input) {
    return MutationPayloads.payload(
        profileSharingService
            .forceEndProfileShare(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.shareId()),
                input.reason())
            .map(ProfileShareDetails::from),
        ShareErrors::toForceEndError,
        ForceEndProfileSharePayload::new);
  }

  private Connection<ProfileShareDetails> toConnection(MediaPage<ProfileHouseholdShare> page) {
    return relayConnectionAdapter.toConnection(
        page,
        item -> ProfileShareDetails.from(item.item()),
        item -> cursorUtil.encodeKeysetCursor(item.item().getId()));
  }
}
