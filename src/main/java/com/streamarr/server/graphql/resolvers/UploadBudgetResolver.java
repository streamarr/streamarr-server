package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.inputs.ChangeUploadBudgetInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.streaming.ChangeUploadBudgetErrors;
import com.streamarr.server.graphql.mutation.streaming.ChangeUploadBudgetPayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import com.streamarr.server.services.serversettings.UploadBudgetSetting;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class UploadBudgetResolver {

  private final AuthorizationService authorizationService;
  private final ServerSettingsService serverSettingsService;

  @DgsQuery
  public UploadBudgetSetting uploadBudgetSetting() {
    return serverSettingsService.readUploadBudgetSetting(authorizationService.currentIdentity());
  }

  @DgsMutation
  public ChangeUploadBudgetPayload changeUploadBudget(
      @InputArgument ChangeUploadBudgetInput input) {
    return MutationPayloads.payload(
        serverSettingsService.changeUploadBudget(
            authorizationService.currentIdentity(),
            Optional.ofNullable(input.internetUploadMegabitsPerSecond())),
        ChangeUploadBudgetErrors::toError,
        ChangeUploadBudgetPayload::new);
  }
}
