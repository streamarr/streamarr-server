package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.Ids;
import com.streamarr.server.graphql.cursor.ListConnections;
import com.streamarr.server.graphql.dto.IssuedManagerInvitation;
import com.streamarr.server.graphql.dto.ManagerInvitationView;
import com.streamarr.server.graphql.inputs.AcceptManagerInvitationInput;
import com.streamarr.server.graphql.inputs.CancelManagerInvitationInput;
import com.streamarr.server.graphql.inputs.DeclineManagerInvitationInput;
import com.streamarr.server.graphql.inputs.GrantProfileManagerOverrideInput;
import com.streamarr.server.graphql.inputs.InviteProfileManagerInput;
import com.streamarr.server.graphql.inputs.RelinquishProfileManagementInput;
import com.streamarr.server.graphql.inputs.RemoveProfileManagerInput;
import com.streamarr.server.graphql.inputs.RemoveProfileManagerOverrideInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.managers.AcceptManagerInvitationPayload;
import com.streamarr.server.graphql.mutation.managers.CancelManagerInvitationPayload;
import com.streamarr.server.graphql.mutation.managers.DeclineManagerInvitationPayload;
import com.streamarr.server.graphql.mutation.managers.GrantProfileManagerOverridePayload;
import com.streamarr.server.graphql.mutation.managers.InviteProfileManagerPayload;
import com.streamarr.server.graphql.mutation.managers.ManagerErrors;
import com.streamarr.server.graphql.mutation.managers.RelinquishProfileManagementPayload;
import com.streamarr.server.graphql.mutation.managers.RemoveProfileManagerOverridePayload;
import com.streamarr.server.graphql.mutation.managers.RemoveProfileManagerPayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.ProfileManagerAdministrationService;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class ProfileManagerResolver {

  private final AuthorizationService authorizationService;
  private final ProfileManagerAdministrationService managerService;
  private final PaginationService paginationService;

  @DgsQuery
  public Connection<ManagerInvitationView> managerInvitations(
      @InputArgument String profileId, DataFetchingEnvironment dfe) {
    var pending =
        managerService
            .managerInvitations(authorizationService.currentIdentity(), Ids.parseUuid(profileId))
            .stream()
            .map(ManagerInvitationView::from)
            .toList();
    return ListConnections.page(pending, invitation -> invitation.id().toString(), options(dfe));
  }

  @DgsQuery
  public Connection<ManagerInvitationView> pendingManagerInvitations(DataFetchingEnvironment dfe) {
    var pending =
        managerService.pendingManagerInvitations(authorizationService.currentIdentity()).stream()
            .map(ManagerInvitationView::from)
            .toList();
    return ListConnections.page(pending, invitation -> invitation.id().toString(), options(dfe));
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
                        ManagerInvitationView.from(issued.invitation()), issued.code())),
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
            .map(ManagerInvitationView::from),
        ManagerErrors::toCancelError,
        CancelManagerInvitationPayload::new);
  }

  @DgsMutation
  public AcceptManagerInvitationPayload acceptManagerInvitation(
      @InputArgument AcceptManagerInvitationInput input) {
    return MutationPayloads.payload(
        managerService
            .acceptManagerInvitation(authorizationService.currentIdentity(), input.code())
            .map(ManagerInvitationView::from),
        ManagerErrors::toAcceptError,
        AcceptManagerInvitationPayload::new);
  }

  @DgsMutation
  public DeclineManagerInvitationPayload declineManagerInvitation(
      @InputArgument DeclineManagerInvitationInput input) {
    return MutationPayloads.payload(
        managerService
            .declineManagerInvitation(authorizationService.currentIdentity(), input.code())
            .map(ManagerInvitationView::from),
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
  public GrantProfileManagerOverridePayload grantProfileManagerOverride(
      @InputArgument GrantProfileManagerOverrideInput input) {
    return MutationPayloads.payload(
        managerService
            .grantProfileManagerOverride(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.profileId()),
                Ids.parseUuid(input.accountId()),
                input.reason())
            .map(UUID::toString),
        ManagerErrors::toGrantOverrideError,
        GrantProfileManagerOverridePayload::new);
  }

  @DgsMutation
  public RemoveProfileManagerOverridePayload removeProfileManagerOverride(
      @InputArgument RemoveProfileManagerOverrideInput input) {
    return MutationPayloads.payload(
        managerService
            .removeProfileManagerOverride(
                authorizationService.currentIdentity(),
                Ids.parseUuid(input.profileId()),
                Ids.parseUuid(input.accountId()),
                input.reason())
            .map(UUID::toString),
        ManagerErrors::toRemoveOverrideError,
        RemoveProfileManagerOverridePayload::new);
  }

  private PaginationOptions options(DataFetchingEnvironment dfe) {
    int first = dfe.getArgumentOrDefault("first", 0);
    String after = dfe.getArgument("after");
    int last = dfe.getArgumentOrDefault("last", 0);
    String before = dfe.getArgument("before");
    if (first == 0 && last == 0 && before != null) {
      return paginationService.getPaginationOptions(first, after, 100, before);
    }
    return paginationService.getPaginationOptions(
        first == 0 && last == 0 ? 100 : first, after, last, before);
  }
}
