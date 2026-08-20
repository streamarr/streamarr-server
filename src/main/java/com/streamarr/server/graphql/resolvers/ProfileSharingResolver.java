package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.cursor.ListConnections;
import com.streamarr.server.graphql.dto.ProfileShareView;
import com.streamarr.server.graphql.dto.SharePreflightView;
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
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class ProfileSharingResolver {

  private final AuthorizationService authorizationService;
  private final ProfileSharingService profileSharingService;
  private final PaginationService paginationService;

  @DgsQuery
  public SharePreflightView sharePreflight(
      @InputArgument String profileId, @InputArgument String householdId) {
    return profileSharingService
        .sharePreflight(
            authorizationService.currentIdentity(),
            Ids.parseUuid(profileId),
            Ids.parseUuid(householdId))
        .map(preflight -> new SharePreflightView(preflight.wouldLock(), preflight.nameConflict()))
        .orElse(null);
  }

  @DgsQuery
  public Connection<ProfileShareView> pendingShareOffers(
      @InputArgument String householdId, DataFetchingEnvironment dfe) {
    var offers =
        profileSharingService
            .pendingShareOffers(authorizationService.currentIdentity(), Ids.parseUuid(householdId))
            .stream()
            .map(ProfileShareView::from)
            .toList();
    return ListConnections.page(offers, share -> share.id().toString(), options(dfe));
  }

  @DgsQuery
  public Connection<ProfileShareView> profileShares(
      @InputArgument String profileId, DataFetchingEnvironment dfe) {
    var sharesOfProfile =
        profileSharingService
            .profileShares(authorizationService.currentIdentity(), Ids.parseUuid(profileId))
            .stream()
            .map(ProfileShareView::from)
            .toList();
    return ListConnections.page(sharesOfProfile, share -> share.id().toString(), options(dfe));
  }

  @DgsMutation
  public OfferProfileSharePayload offerProfileShare(@InputArgument OfferProfileShareInput input) {
    return MutationPayloads.payload(
        profileSharingService
            .offerProfileShare(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.profileId()),
                Ids.parseUuid(input.householdId()))
            .map(ProfileShareView::from),
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
            .map(ProfileShareView::from),
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
            .map(ProfileShareView::from),
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
            .map(ProfileShareView::from),
        ShareErrors::toCancelError,
        CancelProfileSharePayload::new);
  }

  @DgsMutation
  public EndProfileSharePayload endProfileShare(@InputArgument EndProfileShareInput input) {
    return MutationPayloads.payload(
        profileSharingService
            .endProfileShare(authorizationService.currentIdentity(), Ids.parseUuid(input.shareId()))
            .map(ProfileShareView::from),
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
            .map(ProfileShareView::from),
        ShareErrors::toForceEndError,
        ForceEndProfileSharePayload::new);
  }

  private PaginationOptions options(DataFetchingEnvironment dfe) {
    int first = dfe.getArgumentOrDefault("first", 0);
    String after = dfe.getArgument("after");
    int last = dfe.getArgumentOrDefault("last", 0);
    String before = dfe.getArgument("before");
    return paginationService.getPaginationOptions(
        first == 0 && last == 0 && before == null ? 100 : first, after, last, before);
  }
}
