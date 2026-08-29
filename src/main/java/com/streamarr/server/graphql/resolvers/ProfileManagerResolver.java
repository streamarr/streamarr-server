package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.cursor.ConnectionArguments;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dto.IssuedManagerInvitation;
import com.streamarr.server.graphql.dto.ManagerInvitationDetails;
import com.streamarr.server.graphql.inputs.AcceptManagerInvitationInput;
import com.streamarr.server.graphql.inputs.AdministrativelyGrantProfileManagerInput;
import com.streamarr.server.graphql.inputs.AdministrativelyRemoveProfileManagerInput;
import com.streamarr.server.graphql.inputs.CancelManagerInvitationInput;
import com.streamarr.server.graphql.inputs.DeclineManagerInvitationInput;
import com.streamarr.server.graphql.inputs.InviteProfileManagerInput;
import com.streamarr.server.graphql.inputs.RelinquishProfileManagementInput;
import com.streamarr.server.graphql.inputs.RemoveProfileManagerInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.managers.AcceptManagerInvitationPayload;
import com.streamarr.server.graphql.mutation.managers.AdministrativelyGrantProfileManagerPayload;
import com.streamarr.server.graphql.mutation.managers.AdministrativelyRemoveProfileManagerPayload;
import com.streamarr.server.graphql.mutation.managers.CancelManagerInvitationPayload;
import com.streamarr.server.graphql.mutation.managers.DeclineManagerInvitationPayload;
import com.streamarr.server.graphql.mutation.managers.InviteProfileManagerPayload;
import com.streamarr.server.graphql.mutation.managers.ManagerErrors;
import com.streamarr.server.graphql.mutation.managers.RelinquishProfileManagementPayload;
import com.streamarr.server.graphql.mutation.managers.RemoveProfileManagerPayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.ProfileManagerAdministrationService;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.PaginationService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class ProfileManagerResolver {

  private static final int DEFAULT_PAGE_SIZE = 100;

  private final AuthorizationService authorizationService;
  private final ProfileManagerAdministrationService managerService;
  private final PaginationService paginationService;
  private final CursorUtil cursorUtil;
  private final RelayConnectionAdapter relayConnectionAdapter;
  private final Clock clock;

  @DgsQuery
  public Connection<ManagerInvitationDetails> pendingManagerInvitationsForProfile(
      @InputArgument String profileId, DataFetchingEnvironment dfe) {
    var options = ConnectionArguments.paginationOptions(paginationService, dfe, DEFAULT_PAGE_SIZE);
    var page =
        managerService.pendingManagerInvitationsForProfile(
            authorizationService.currentIdentity(),
            Ids.parseUuid(profileId),
            cursorUtil.decodeKeysetCursor(options));
    return toConnection(page);
  }

  @DgsQuery
  public Connection<ManagerInvitationDetails> pendingManagerInvitations(
      DataFetchingEnvironment dfe) {
    var options = ConnectionArguments.paginationOptions(paginationService, dfe, DEFAULT_PAGE_SIZE);
    var page =
        managerService.pendingManagerInvitations(
            authorizationService.currentIdentity(), cursorUtil.decodeKeysetCursor(options));
    return toConnection(page);
  }

  @DgsMutation
  public InviteProfileManagerPayload inviteProfileManager(
      @InputArgument InviteProfileManagerInput input) {
    return MutationPayloads.payload(
        managerService
            .inviteProfileManager(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.profileId()),
                Ids.parseUuid(input.recipientAccountId()))
            .map(
                issued ->
                    new IssuedManagerInvitation(
                        invitationDetails(issued.invitation()), issued.code())),
        ManagerErrors::toInviteError,
        InviteProfileManagerPayload::new);
  }

  @DgsMutation
  public CancelManagerInvitationPayload cancelManagerInvitation(
      @InputArgument CancelManagerInvitationInput input) {
    return MutationPayloads.payload(
        managerService
            .cancelManagerInvitation(
                authorizationService.currentIdentity(), Ids.parseUuid(input.invitationId()))
            .map(this::invitationDetails),
        ManagerErrors::toCancelError,
        CancelManagerInvitationPayload::new);
  }

  @DgsMutation
  public AcceptManagerInvitationPayload acceptManagerInvitation(
      @InputArgument AcceptManagerInvitationInput input) {
    return MutationPayloads.payload(
        managerService
            .acceptManagerInvitation(authorizationService.currentIdentity(), input.code())
            .map(this::invitationDetails),
        ManagerErrors::toAcceptError,
        AcceptManagerInvitationPayload::new);
  }

  @DgsMutation
  public DeclineManagerInvitationPayload declineManagerInvitation(
      @InputArgument DeclineManagerInvitationInput input) {
    return MutationPayloads.payload(
        managerService
            .declineManagerInvitation(authorizationService.currentIdentity(), input.code())
            .map(this::invitationDetails),
        ManagerErrors::toDeclineError,
        DeclineManagerInvitationPayload::new);
  }

  @DgsMutation
  public RelinquishProfileManagementPayload relinquishProfileManagement(
      @InputArgument RelinquishProfileManagementInput input) {
    return MutationPayloads.payload(
        managerService
            .relinquishProfileManagement(
                authorizationService.currentIdentity(), Ids.parseUuid(input.profileId()))
            .map(UUID::toString),
        ManagerErrors::toRelinquishError,
        RelinquishProfileManagementPayload::new);
  }

  @DgsMutation
  public RemoveProfileManagerPayload removeProfileManager(
      @InputArgument RemoveProfileManagerInput input) {
    return MutationPayloads.payload(
        managerService
            .removeProfileManager(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.profileId()),
                Ids.parseUuid(input.accountId()))
            .map(UUID::toString),
        ManagerErrors::toRemoveError,
        RemoveProfileManagerPayload::new);
  }

  @DgsMutation
  public AdministrativelyGrantProfileManagerPayload administrativelyGrantProfileManager(
      @InputArgument AdministrativelyGrantProfileManagerInput input) {
    return MutationPayloads.payload(
        managerService
            .administrativelyGrantProfileManager(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.profileId()),
                Ids.parseUuid(input.accountId()),
                input.reason())
            .map(UUID::toString),
        ManagerErrors::toAdministrativelyGrantError,
        AdministrativelyGrantProfileManagerPayload::new);
  }

  @DgsMutation
  public AdministrativelyRemoveProfileManagerPayload administrativelyRemoveProfileManager(
      @InputArgument AdministrativelyRemoveProfileManagerInput input) {
    return MutationPayloads.payload(
        managerService
            .administrativelyRemoveProfileManager(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.profileId()),
                Ids.parseUuid(input.accountId()),
                input.reason())
            .map(UUID::toString),
        ManagerErrors::toAdministrativelyRemoveError,
        AdministrativelyRemoveProfileManagerPayload::new);
  }

  private Connection<ManagerInvitationDetails> toConnection(
      MediaPage<ProfileManagerInvitation> page) {
    return relayConnectionAdapter.toConnection(
        page,
        item -> invitationDetails(item.item()),
        item -> cursorUtil.encodeKeysetCursor(item.item().getId()));
  }

  private ManagerInvitationDetails invitationDetails(ProfileManagerInvitation invitation) {
    return ManagerInvitationDetails.from(invitation, clock.instant());
  }
}
