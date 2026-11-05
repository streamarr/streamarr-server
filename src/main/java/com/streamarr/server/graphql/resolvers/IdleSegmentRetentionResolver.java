package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.inputs.ChangeIdleSegmentRetentionInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.streaming.ChangeIdleSegmentRetentionErrors;
import com.streamarr.server.graphql.mutation.streaming.ChangeIdleSegmentRetentionPayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.serversettings.IdleSegmentRetentionSetting;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class IdleSegmentRetentionResolver {

  private final AuthorizationService authorizationService;
  private final ServerSettingsService serverSettingsService;

  @DgsQuery
  public IdleSegmentRetentionSetting idleSegmentRetentionSetting() {
    return serverSettingsService.readIdleSegmentRetentionSetting(
        authorizationService.currentIdentity());
  }

  @DgsMutation
  public ChangeIdleSegmentRetentionPayload changeIdleSegmentRetention(
      @InputArgument ChangeIdleSegmentRetentionInput input) {
    return MutationPayloads.payload(
        serverSettingsService.changeIdleSegmentRetention(
            authorizationService.currentIdentity(), input.minutes()),
        ChangeIdleSegmentRetentionErrors::toError,
        ChangeIdleSegmentRetentionPayload::new);
  }
}
