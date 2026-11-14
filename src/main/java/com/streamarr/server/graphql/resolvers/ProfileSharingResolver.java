package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.graphql.ClientIpAddressGraphQlInterceptor;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.cursor.ConnectionArguments;
import com.streamarr.server.graphql.cursor.PositionCursorCodec;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.cursor.ShareListCursors;
import com.streamarr.server.graphql.dto.ProfileShareDetails;
import com.streamarr.server.graphql.dto.ProfileSharePreview;
import com.streamarr.server.graphql.inputs.AcceptProfileShareInput;
import com.streamarr.server.graphql.inputs.AdministrativelyEndProfileShareInput;
import com.streamarr.server.graphql.inputs.CancelProfileShareInput;
import com.streamarr.server.graphql.inputs.EndProfileShareInput;
import com.streamarr.server.graphql.inputs.OfferProfileShareInput;
import com.streamarr.server.graphql.inputs.RejectProfileShareInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.sharing.AcceptProfileSharePayload;
import com.streamarr.server.graphql.mutation.sharing.AdministrativelyEndProfileSharePayload;
import com.streamarr.server.graphql.mutation.sharing.CancelProfileSharePayload;
import com.streamarr.server.graphql.mutation.sharing.EndProfileSharePayload;
import com.streamarr.server.graphql.mutation.sharing.OfferProfileSharePayload;
import com.streamarr.server.graphql.mutation.sharing.RejectProfileSharePayload;
import com.streamarr.server.graphql.mutation.sharing.ShareErrors;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.ProfileSharingService;
import com.streamarr.server.services.identity.ShareCodeCommand;
import com.streamarr.server.services.identity.ShareListOrder;
import com.streamarr.server.services.pagination.ListIdentity;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.PaginationService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class ProfileSharingResolver {

  private static final int DEFAULT_PAGE_SIZE = 100;

  private final AuthorizationService authorizationService;
  private final ProfileSharingService profileSharingService;
  private final PaginationService paginationService;
  private final PositionCursorCodec positionCursors;
  private final RelayConnectionAdapter relayConnectionAdapter;
  private final Clock clock;

  @DgsQuery
  public ProfileSharePreview profileSharePreview(
      @InputArgument String profileId,
      @InputArgument String shareCode,
      DataFetchingEnvironment dfe) {
    return profileSharingService
        .sharePreflight(
            authorizationService.currentIdentity(),
            Ids.parseUuid(profileId),
            presented(shareCode, dfe))
        .map(preview -> new ProfileSharePreview(preview.wouldLock(), preview.nameConflict()))
        .orElse(null);
  }

  @DgsQuery
  public Connection<ProfileShareDetails> householdShares(
      @InputArgument String householdId,
      @InputArgument List<ProfileShareStatus> statuses,
      @InputArgument Boolean visitorsOnly,
      DataFetchingEnvironment dfe) {
    var household =
        ShareListCursors.householdScope(Ids.parseUuid(householdId), statuses, visitorsOnly);
    var list = ShareListCursors.listOf(household);
    var page =
        profileSharingService.householdShares(
            authorizationService.currentIdentity(), household, pageRequest(list, dfe));
    return relayConnectionAdapter.toKeyedConnection(
        page.map(this::details), list, ShareListOrder.NEWEST_FIRST);
  }

  @DgsQuery
  public Connection<ProfileShareDetails> profileShares(
      @InputArgument String profileId,
      @InputArgument List<ProfileShareStatus> statuses,
      DataFetchingEnvironment dfe) {
    var profile = ShareListCursors.profileScope(Ids.parseUuid(profileId), statuses);
    var list = ShareListCursors.listOf(profile);
    var page =
        profileSharingService.profileShares(
            authorizationService.currentIdentity(), profile, pageRequest(list, dfe));
    return relayConnectionAdapter.toKeyedConnection(
        page.map(this::details), list, ShareListOrder.NEWEST_FIRST);
  }

  @DgsMutation
  public OfferProfileSharePayload offerProfileShare(
      @InputArgument OfferProfileShareInput input, DataFetchingEnvironment dfe) {
    return MutationPayloads.payload(
        profileSharingService
            .offerProfileShare(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.profileId()),
                presented(input.shareCode(), dfe))
            .map(this::details),
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
            .map(this::details),
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
            .map(this::details),
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
            .map(this::details),
        ShareErrors::toCancelError,
        CancelProfileSharePayload::new);
  }

  @DgsMutation
  public EndProfileSharePayload endProfileShare(@InputArgument EndProfileShareInput input) {
    return MutationPayloads.payload(
        profileSharingService
            .endProfileShare(authorizationService.currentIdentity(), Ids.parseUuid(input.shareId()))
            .map(this::details),
        ShareErrors::toEndError,
        EndProfileSharePayload::new);
  }

  @DgsMutation
  public AdministrativelyEndProfileSharePayload administrativelyEndProfileShare(
      @InputArgument AdministrativelyEndProfileShareInput input) {
    return MutationPayloads.payload(
        profileSharingService
            .administrativelyEndProfileShare(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.shareId()),
                input.reason())
            .map(this::details),
        ShareErrors::toAdministrativelyEndError,
        AdministrativelyEndProfileSharePayload::new);
  }

  private static ShareCodeCommand presented(String shareCode, DataFetchingEnvironment dfe) {
    return ShareCodeCommand.builder()
        .code(shareCode)
        .ipAddress(ClientIpAddressGraphQlInterceptor.resolve(dfe))
        .build();
  }

  private ProfileShareDetails details(ProfileHouseholdShare share) {
    return ProfileShareDetails.from(share, clock.instant());
  }

  private PageRequest pageRequest(ListIdentity list, DataFetchingEnvironment dfe) {
    return positionCursors.pageRequest(
        list, ConnectionArguments.paginationOptions(paginationService, dfe, DEFAULT_PAGE_SIZE));
  }
}
