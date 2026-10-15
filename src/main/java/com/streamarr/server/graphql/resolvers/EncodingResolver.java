package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.inputs.ChangeEncoderPresetInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.encoding.ChangeEncoderPresetErrors;
import com.streamarr.server.graphql.mutation.encoding.ChangeEncoderPresetPayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.serversettings.EncoderPresetSetting;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class EncodingResolver {

  private final AuthorizationService authorizationService;
  private final ServerSettingsService serverSettingsService;

  @DgsQuery
  public EncoderPresetSetting encoderPresetSetting() {
    return serverSettingsService.readEncoderPresetSetting(authorizationService.currentIdentity());
  }

  @DgsMutation
  public ChangeEncoderPresetPayload changeEncoderPreset(
      @InputArgument ChangeEncoderPresetInput input) {
    return MutationPayloads.payload(
        serverSettingsService.changeEncoderPreset(
            authorizationService.currentIdentity(), input.preset()),
        ChangeEncoderPresetErrors::toError,
        ChangeEncoderPresetPayload::new);
  }
}
