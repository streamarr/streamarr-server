package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.inputs.ChangeInvitationLifetimeInput;
import com.streamarr.server.graphql.inputs.ChangeShareCodeLifetimeInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.sharing.ChangeInvitationLifetimePayload;
import com.streamarr.server.graphql.mutation.sharing.ChangeShareCodeLifetimePayload;
import com.streamarr.server.graphql.mutation.sharing.ShareErrors;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.serversettings.LifetimeSetting;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import lombok.RequiredArgsConstructor;

/** The Server settings section "Sharing and invitations", for a ServerAdmin (ADR 0024). */
@DgsComponent
@RequiredArgsConstructor
public class SharingAndInvitationSettingsResolver {

  private final AuthorizationService authorizationService;
  private final ServerSettingsService serverSettingsService;

  @DgsQuery
  public LifetimeSetting shareCodeLifetimeSetting() {
    return serverSettingsService.readShareCodeLifetimeSetting(
        authorizationService.currentIdentity());
  }

  @DgsMutation
  public ChangeShareCodeLifetimePayload changeShareCodeLifetime(
      @InputArgument ChangeShareCodeLifetimeInput input) {
    return MutationPayloads.payload(
        serverSettingsService.changeShareCodeLifetime(
            authorizationService.currentIdentity(), input.hours()),
        ShareErrors::toLifetimeError,
        ChangeShareCodeLifetimePayload::new);
  }

  @DgsQuery
  public LifetimeSetting invitationLifetimeSetting() {
    return serverSettingsService.readInvitationLifetimeSetting(
        authorizationService.currentIdentity());
  }

  @DgsMutation
  public ChangeInvitationLifetimePayload changeInvitationLifetime(
      @InputArgument ChangeInvitationLifetimeInput input) {
    return MutationPayloads.payload(
        serverSettingsService.changeInvitationLifetime(
            authorizationService.currentIdentity(), input.hours()),
        ShareErrors::toLifetimeError,
        ChangeInvitationLifetimePayload::new);
  }
}
