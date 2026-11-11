package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.graphql.inputs.ChangeNotificationRetentionInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.notification.ChangeNotificationRetentionPayload;
import com.streamarr.server.graphql.mutation.notification.NotificationErrors;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.serversettings.NotificationRetentionSetting;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class NotificationRetentionResolver {

  private final AuthorizationService authorizationService;
  private final ServerSettingsService serverSettingsService;

  @DgsQuery
  public NotificationRetentionSetting notificationRetentionSetting() {
    return serverSettingsService.readNotificationRetentionSetting(
        authorizationService.currentIdentity());
  }

  @DgsMutation
  public ChangeNotificationRetentionPayload changeNotificationRetention(
      @InputArgument ChangeNotificationRetentionInput input) {
    return MutationPayloads.payload(
        serverSettingsService.changeNotificationRetention(
            authorizationService.currentIdentity(), input.days()),
        NotificationErrors::toChangeRetentionError,
        ChangeNotificationRetentionPayload::new);
  }
}
